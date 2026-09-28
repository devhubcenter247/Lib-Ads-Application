# Kế hoạch tối ưu kiến trúc `gma-lib` theo chuẩn GMA Next-Gen SDK

> Tài liệu đối chiếu module `gma-lib` (thư mục `gma-lib/src/main/java/com/lib/ads/application/ads/`) với repo mẫu chính thức của Google:
> [`googleads/gma-next-gen-sdk-android-examples`](https://github.com/googleads/gma-next-gen-sdk-android-examples) (branch `main`, cả `java/` và `kotlin/NextGenExample`).
>
> **Kết luận quan trọng nhất:** `gma-lib` **đã** dùng đúng SDK (`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`, hiện là **1.3.1** theo `gma-lib/build.gradle.kts:76` — GMA Next-Gen SDK), **không phải** SDK cũ `com.google.android.gms.ads`. Vì vậy đây **không phải là việc migrate SDK**, mà là bài toán **dọn kiến trúc / giảm trùng lặp / bắt kịp các API mới** mà Next-Gen SDK cung cấp nhưng `gma-lib` chưa dùng tới.

> ## 📌 Cập nhật 2026-08-28 — soát lại trạng thái, trọng tâm mới: show rate / impression / new user
>
> Doc này được viết lần đầu ngày 2026-08-16. Từ đó tới nay code đã tiến triển đáng kể (xem commit `271602d`, `0bd3340`, `3a1a2f9`, `f5f6284`, `7a057da`) — nhiều mục ở **Giai đoạn 1-2** bên dưới **đã xong**, nên đọc doc này cùng với bảng trạng thái ngay sau đây thay vì coi mục 2/4 là còn nguyên. Phiên phân tích này (đối chiếu thêm với `kotlin/NextGenExample` bản mới nhất + **giải mã bytecode thật của `ads-mobile-sdk-1.3.1.aar`** để xác nhận chữ ký API, không đoán) tập trung riêng vào mục tiêu **tăng show rate quảng cáo và impression/new user**. Trọng tâm mới nằm ở **mục 7** — đọc mục đó trước nếu chỉ quan tâm show-rate/new-user, các mục 1-6 vẫn là tài liệu tham chiếu kiến trúc tổng quát còn giá trị.
>
> | Hạng mục trong kế hoạch gốc | Trạng thái 2026-08-28 |
> |---|---|
> | 2.1 Gộp 3 file `AppOpenManager*.kt` | **Một phần** — còn 2 file (`manager/AppOpenAdManager.kt` = splash app-open, `helper/appopen/AppOpenManager.kt` = resume app-open) + **thêm** `helper/appopen/AppOpenAdHelper.kt` (coroutine, 381 dòng) là cách thứ 3 để chạy resume ad → xem mục 7.1, vẫn là gap thật, không phải đã xong. |
> | 2.2 `AdsCallback` god-interface | **Đã không còn tồn tại** — không tìm thấy file `AdsCallback.kt` nào trong `gma-lib` nữa (đã grep toàn bộ). Coi mục 2.2 là lịch sử, không cần làm gì thêm. |
> | 2.3 Gộp facade `AdsManager` / `InterstitialAdManager` / `RewardAdManager` | **Chưa làm** — cả 3 vẫn tồn tại riêng, `AdsManager` gọi thẳng vào 2 manager kia (đúng hướng "1 facade gọi Engine", không phải 2 facade song song như lo ngại ban đầu) — hạ độ ưu tiên, không phải gap cấp bách. |
> | 2.4 Preload native/banner bằng SDK Preloader | **Xong** — `BannerAdManager.kt`, `RewardAdManager.kt`, `InterstitialAdManager.kt` đều đã dùng `*Preloader.start()/pollAd()`. Native vẫn còn `helper/adnative/preload/NativeAdPreload.kt` tự viết — chưa xác minh lại trong phiên này. |
> | 2.7 API Next-Gen mới (Ad Placements, Configurable Init, debug menu) | **Ad Placements: xong** — `placementId` giờ xuyên suốt từ `AdsManager`'s 1-liner tới `AdRequest`/`NativeAdRequest`/`BannerAdRequest` thật cho mọi định dạng, cả XML lẫn Compose (mục 8.5). **Configurable Init cho splash: xong ở mức opt-in** qua `AdSdkConfig.skipUninitializedAdaptersForSplash` (mục 8.3) — publisher vẫn phải tự cấu hình `adapterInitializationConfig` phù hợp. **Debug menu: đã có từ trước** (`AdsManager.openDebugMenu`, `manager/AdsManager.kt:79`), không đổi trong phiên này. |
>
> **Đã thực hiện trong phiên này** — 2 đợt:
> 1. Chuyển cả 2 đường App Open (`helper/appopen/AppOpenManager.kt` — resume, và `manager/AppOpenAdManager.kt` — splash) từ `AppOpenAd.load()` thủ công sang `AppOpenAdPreloader` có sẵn trong SDK, dùng chung 1 pool theo id nên nếu 1 trong 2 đường đã preload sẵn thì đường kia poll thấy ngay lập tức. Bump version publish `gma-lib` (`gma-lib/build.gradle.kts:160`) từ `1.0.3` lên `1.0.4`.
> 2. Làm toàn bộ mục 7.6 (trừ #8) — new-user eligibility gate, preload splash sớm tại `Ads.init()`, `skipUninitializedAdapters` cho splash, 1-liner splash app-open trong `AdsManager`, và **Ad Placements (`placementId`) xuyên suốt mọi định dạng quảng cáo** (interstitial/rewarded/banner/native/app-open, cả nhánh XML lẫn Compose). Chi tiết cách dùng: **mục 8**. Toàn bộ đã build pass (`./gradlew :gma-lib:assembleDebug` → `BUILD SUCCESSFUL`), 19 file, +315/-148 dòng.

---

## 0. Số liệu hiện trạng

| Chỉ số | Giá trị |
|---|---|
| Số file Kotlin trong `ads/` | 124 file |
| Tổng dòng code (không tính test, res) | ~7.840 dòng |
| File lớn nhất | `helper/adnative/NativeAdHelper.kt` (371 dòng), `helper/banner/BannerAdHelper.kt` (364), `gma/AppOpenManager.kt` (349), `gma/AdmobReward.kt` (237), `gma/AdmobSplash.kt` (212), `helper/adnative/NativeAdEngine.kt` (209), `gma/Admob.kt` (209) |
| Package con dưới `helper/` | 9 (`adnative`, `appopen`, `banner`, `extension`, `interstitial`, `nativebanner`, `params`, `reward`, `splash`) |
| Riêng `helper/adnative/preload/` | 12 file, 566 dòng — bộ máy preload **tự viết tay** cho native ads |
| Các "manager" song song | `engine/Ads.kt`, `manager/AdsManager.kt`, `manager/InterstitialAdManager.kt`, `manager/RewardAdManager.kt`, `manager/AdSdkInitializer.kt`, `gma/AppOpenManager.kt`, `admob/AppOpenManager.kt` (typealias) |

So sánh: app mẫu `NextGenExample` của Google (bản Kotlin) toàn bộ logic ad chỉ nằm trong **11 file** theo định dạng phẳng (`appopen/`, `banner/`, `interstitial/`, `nativead/`, `rewarded/`, `preloading/`, `snippets/`), mỗi định dạng quảng cáo là **một singleton `object` duy nhất** (~150–250 dòng), không có tầng "manager gọi manager" hay "engine gọi helper gọi engine".

---

## 1. Kiến trúc tham chiếu của Google (rút ra từ repo mẫu)

Đọc trực tiếp các file mẫu quan trọng để trích ra pattern chuẩn:

### 1.1. Một ad format = một `object` singleton, phẳng, không kế thừa
`appopen/AppOpenAdManager.kt` (Kotlin) — toàn bộ vòng đời App Open Ad (load / cache / hết hạn sau 4h / show) nằm gọn trong **1 object, ~200 dòng**, không interface trung gian, không "Engine + Helper + Manager" 3 tầng:

```kotlin
object AppOpenAdManager {
  private var appOpenAd: AppOpenAd? = null
  private var isLoadingAd = false
  var isShowingAd = false
  private var loadTime: Long = 0

  fun loadAd(context: Context) { /* AppOpenAd.load(...) */ }
  private fun isAdAvailable(): Boolean = appOpenAd != null && wasLoadTimeLessThanNHoursAgo(4)
  fun showAdIfAvailable(activity: Activity, onShowAdCompleteListener: OnShowAdCompleteListener?) { ... }
}
```

### 1.2. `MyApplication` tự hiển thị App Open ad qua `ProcessLifecycleOwner`, không qua lớp trung gian
`MyApplication.kt` implement thẳng `Application.ActivityLifecycleCallbacks` + `DefaultLifecycleObserver`, gọi thẳng `AppOpenAdManager.showAdIfAvailable(...)` trong `onStart`. Không có khái niệm "AppOpenManager.init() + isEnableList + disabledAppOpenList + dialog loading" như `gma-lib`.

### 1.3. Consent Manager: 1 class, wrap UMP trực tiếp, không có tầng phụ
`GoogleMobileAdsConsentManager.kt` ~120 dòng: 1 singleton, 4 method public (`gatherConsent`, `showPrivacyOptionsForm`, `canRequestAds`, `isPrivacyOptionsRequired`), không đọc `SharedPreferences` IABTCF thủ công, không có các hàm tiện ích trùng lặp (`getConsentResult`, `isCMPConsent` — hai hàm tĩnh làm cùng một việc).

### 1.4. Preloading dùng API **có sẵn trong SDK**, không tự viết
Repo mẫu có hẳn thư mục `preloading/` (`AppOpenPreloadFragment`, `BannerPreloadFragment`, `NativePreloadFragment`) minh hoạ cách dùng **Preloader API built-in** của Next-Gen SDK (`InterstitialAdPreloader`, `RewardedAdPreloader`, `AppOpenAdPreloader`, `NativeAdPreloader`, class `PreloadConfiguration`) — không tự cài đặt hàng chục lớp quản lý hàng đợi, timeout, template như cách `gma-lib` đang làm cho native ads.

### 1.5. Các API Next-Gen SDK mới hoàn toàn chưa xuất hiện trong `gma-lib`
Từ các file snippet chính thức (`snippets/*.kt` trong repo mẫu — đây là nguồn snippet cho developer guide chính thức của Google, phản ánh đúng API mới nhất của SDK `1.x`):

| API mới | File snippet minh hoạ | Dùng để làm gì |
|---|---|---|
| `AdRequest.Builder(id).setPlacementId(id)` / `ad.placementId` | `AdPlacementsSnippets.kt` | Ad Placements — theo dõi hiệu suất theo vị trí hiển thị trong app, tách khỏi ad unit |
| `AdapterInitializationConfig` + `InitializationConfig.Builder(appId).setAdapterInitializationConfig(...)` + `AdRequest.Builder(...).skipUninitializedAdapters()` | `ConfigurableInitSnippets.kt` | Khởi tạo SDK có kiểm soát: giới hạn adapter/ad-format cần init trước khi cho phép gọi ad đầu tiên → giảm TTFA (time-to-first-ad) |
| `RequestConfiguration.Builder().setAgeRestrictedTreatment(...)` + `MobileAds.setRequestConfiguration(...)` | `RequestConfigurationSnippets.kt` | Khai báo child/teen/unspecified age treatment cho toàn bộ request |
| `MobileAds.openDebugMenu(activity, adUnitId)` | `DebuggingSnippets.kt` | Mở debug menu built-in để test mediation/ad unit ngay trong app, không cần công cụ ngoài |
| Ad format mới: Swipeable Interstitial, Full-Screen Native, Icon Ad, Inline Banner (RecyclerView) | `swipeableinterstitial/`, `fullscreennative/`, `icon*/`, `inlinebanner/` | Các định dạng quảng cáo mới chỉ Next-Gen SDK hỗ trợ |

`gma-lib` hiện **không dùng bất kỳ API nào ở trên** (đã grep toàn bộ `ads/` — 0 kết quả cho `setPlacementId`, `AgeRestrictedTreatment`, `AdapterInitializationConfig`, `openDebugMenu`, `RequestConfiguration`).

---

## 2. Vấn đề cụ thể trong `gma-lib` (đối chiếu file:line)

### 2.1. Trùng tên `AppOpenManager` ở 2 package, chia làm 4 file cho 1 concept

- `gma/AppOpenManager.kt` (349 dòng) — class chính: state, lifecycle callback, dialog loading, danh sách activity bị disable...
- `gma/AppOpenManagerResume.kt` (127 dòng) — các hàm extension `loadAppOpenResume`, `showResumeAds` tách rời khỏi class chính
- `gma/AppOpenManagerSplash.kt` (208 dòng) — thêm một bộ load/show riêng cho splash (`loadOpenAppAdSplash`, `loadOpenAppAdSplashList`, `showAppOpenSplash`), **dùng chung state** (`splashAd`, `isTimeout`, `isTimeDelay`, `handlerTimeout`, `rdTimeout`) với class ở file đầu tiên nhưng lại tách vật lý sang file khác
- `admob/AppOpenManager.kt` (4 dòng) — chỉ là `typealias AppOpenManager = com.lib.ads.application.ads.gma.AppOpenManager` để tương thích ngược

**Vấn đề:** 1 khái niệm nghiệp vụ ("app open ad") bị chẻ thành 3 file phụ thuộc chéo bằng extension function trên cùng 1 mutable state (`internal var` fields công khai qua toàn bộ package `gma`), cộng thêm alias package thứ 4. So với `AppOpenAdManager.kt` của Google (1 file, 1 object, ~200 dòng, không có khái niệm splash riêng — splash chỉ là 1 use-case gọi `loadAd()`/`showAdIfAvailable()` bình thường).

**Đề xuất:**
- Gộp `AppOpenManager.kt` + `AppOpenManagerResume.kt` + `AppOpenManagerSplash.kt` thành 1 class duy nhất (giữ nguyên gói `gma`), dùng **1 hàm `load(adId, onResult)` và 1 hàm `show(activity, onResult)`** dùng chung cho cả "resume" và "splash" — khác biệt duy nhất giữa 2 use-case là *thời điểm gọi* và *tham số timeout/delay*, không cần 2 bộ field + 2 bộ callback riêng.
- Xoá `admob/AppOpenManager.kt` (typealias) nếu không còn code ngoài `gma-lib` phụ thuộc vào package `admob.AppOpenManager`; nếu còn, note rõ trong changelog để bên dùng migrate rồi xoá ở version sau.
- Bỏ các state dạng `internal var` public toàn package (`isTimeout`, `isTimeDelay`, `handlerTimeout`, `rdTimeout`, `dialog`, `dialogSplash`...) — đóng gói lại thành `private` bên trong class, chỉ expose qua method.

### 2.2. `AdsCallback` là "god interface" — 20 method cho mọi định dạng quảng cáo

`callback/AdsCallback.kt:18-42` khai báo **20 `open fun`** dùng chung cho banner, native, interstitial, rewarded, splash, app-open (`onNextAction`, `onAdClosed`, `onRewardedAdClosed`, `onAdFailedToLoad`, `onAdFailedToShow`, `onAdLoaded`, `onRewardAdLoaded`, `onAdSplashReady`, `onInterstitialLoad`, `onAdClicked`, `onAdOpened`, `onAdImpression`, `onNativeAdLoaded`, `onUserEarnedReward`, `onInterstitialShow`, `onBannerLoaded`, + 6 hàm `internal onGma...` bọc thẳng type của SDK: `LoadAdError`, `FullScreenContentError`, `InterstitialAd`, `RewardedAd`, `RewardedInterstitialAd`, `NativeAd`).

**Hệ quả đã thấy trong code:**
- `manager/InterstitialAdManager.kt:59-67` phải viết hàm `bridge()` chỉ để convert 1 `AdsCallback` cụt (chỉ implement `onNextAction`) sang 1 `AdsCallback` khác đầy đủ — thuần tuý là boilerplate chuyển tiếp.
- Next-Gen SDK đã tách sẵn interface theo định dạng (`AppOpenAdEventCallback`, `AdLoadCallback<T>`, `InterstitialAdEventCallback`...) — đúng nguyên tắc Interface Segregation mà repo mẫu tuân theo triệt để (mỗi callback interface chỉ có 3-5 method của đúng 1 định dạng quảng cáo).

**Đề xuất:** tách `AdsCallback` thành các sealed event class theo định dạng — module đã có sẵn khuôn mẫu đúng hướng ở `model/wrapper/InterstitialAdEvent.kt` và `RewardAdEvent.kt` (sealed class theo sự kiện). Nên nhân rộng pattern này cho banner/native/splash/app-open thay vì tiếp tục mở rộng `AdsCallback`, rồi cho `AdsCallback` cũ tồn tại như 1 lớp *adapter* duy nhất ở tầng public API (không phải nguồn sự thật nội bộ).

### 2.3. 3 tầng "ai chịu trách nhiệm load/show ad" chồng chéo: `engine/`, `manager/`, `helper/*`

- `engine/Ads.kt` — singleton trung tâm, chứa init SDK, Adjust setup, `runWhenReady` hàng đợi callback.
- `manager/AdsManager.kt` (149 dòng) — "façade" gọi vào `helper/*` (banner/interstitial/native/reward) — comment ngay trong code: `// Small facade with the same intent as adlib's "AdsManager"`.
- `manager/InterstitialAdManager.kt`, `manager/RewardAdManager.kt` — **façade thứ hai**, cũng gọi vào `Ads.getInstance().loadInterstitial/showInterstitial/loadReward/showReward` — tức là làm lại gần như đúng việc `AdsManager` đã làm, chỉ khác chữ ký tham số.
- `helper/banner/BannerAdEngine.kt`, `helper/interstitial/InterstitialAdEngine.kt`, `helper/reward/RewardedAdEngine.kt`, `helper/adnative/NativeAdEngine.kt`, `helper/splash/SplashAdEngine.kt` — tầng "Engine" thứ 3, là nơi thật sự gọi SDK Next-Gen (`InterstitialAd.load`, `BannerAd.load`...).
- `helper/banner/BannerAdHelper.kt` (364 dòng) — tầng "Helper" thứ 4, wrap `BannerAdEngine` thêm state (`StateFlow<AdBannerState>`), lifecycle, waterfall.

**Ví dụ đường đi của 1 lệnh "load banner":**
`AdsManager.showBanner()` → `BannerAdHelper(...).requestAds()` → (nội bộ) `Ads.requestLoadBanner()` (`BannerAdEngine.kt`) → `BannerAdWaterfallExt.kt` (retry theo danh sách id) → SDK `BannerAd.load(...)`.
Đi qua **5 file, 4 tầng gọi nhau**, trong khi bản thân waterfall + lifecycle-aware là hợp lý (nên giữ), nhưng có 2 tầng facade song song (`AdsManager` vs `InterstitialAdManager`/`RewardAdManager`) không cộng thêm giá trị — cả 2 đều gọi thẳng vào `Ads.getInstance()`.

**Đề xuất:**
- Giữ đúng 3 tầng: **SDK Next-Gen → `helper/<format>/*Engine.kt` (gọi SDK trực tiếp) → 1 facade public duy nhất** (`AdsManager`). Xoá `manager/InterstitialAdManager.kt` và `manager/RewardAdManager.kt`, hoặc nếu cần giữ API cũ cho tương thích ngược thì chuyển 2 file này thành **hàm mỏng gọi thẳng `AdsManager`** (deprecated wrapper), không tự gọi lại `Ads.getInstance()`.
- `manager/AdSdkInitializer.kt` (32 dòng) thực chất chỉ là 1 wrapper mỏng quanh `GmaSdk.initialize` — cân nhắc gộp thẳng vào `GmaSdk` (đổi tên method) thay vì giữ 1 object riêng chỉ để đổi tên.

### 2.4. Native Ad preload: tự viết 566 dòng thay vì dùng `Preloader` có sẵn trong SDK

- `helper/adnative/preload/` gồm các thành phần preload buffer và `NativeAdBufferState` — chịu trách nhiệm hàng đợi, timeout và gắn tag ad unit.
- Trong khi đó, `gma/AdmobInterstitial.kt:17-33` và `gma/AdmobReward.kt:19-47` **đã dùng đúng API preload built-in của SDK**: `PreloadConfiguration`, `InterstitialAdPreloader.start()/pollAd()`, `RewardedAdPreloader`, `RewardedInterstitialAdPreloader`. Chỉ ~10 dòng cho mỗi định dạng.
- Banner (`helper/banner/`) thì **không có preload nào cả** — chỉ có waterfall load-on-demand.

**Vấn đề:** không nhất quán — cùng 1 SDK, cùng 1 khái niệm "preload", nhưng 3 cách làm khác nhau cho 3 định dạng (SDK-native cho Interstitial/Reward, tự chế 566 dòng cho Native, không có gì cho Banner). Next-Gen SDK có `AppOpenAdPreloader`, `BannerAdPreloader`, `NativeAdPreloader` y hệt pattern `InterstitialAdPreloader`/`RewardedAdPreloader` (xem `preloading/BannerPreloadFragment.kt`, `preloading/NativePreloadFragment.kt`, `preloading/AppOpenPreloadFragment.kt` trong repo mẫu — cả 3 định dạng đều dùng chung 1 pattern preloader).

**Đề xuất (impact cao nhất, nên làm đầu tiên):**
1. Xác nhận version `ads-mobile-sdk:1.0.1` đã export `NativeAdPreloader`/`BannerAdPreloader`/`AppOpenAdPreloader` (kiểm tra changelog / decompile artifact nếu cần — repo mẫu dùng nó nên gần như chắc chắn có).
2. Thay toàn bộ 566 dòng trong `helper/adnative/preload/` bằng lớp mỏng gọi `NativeAdPreloader.start(id, PreloadConfiguration(...))` / `pollAd(id)`, theo đúng mẫu đã áp dụng thành công ở `AdmobInterstitial.kt`/`AdmobReward.kt`.
3. Áp dụng `BannerAdPreloader` cho banner nếu nghiệp vụ cần preload trước khi hiển thị (đặc biệt hữu ích cho luồng splash/onboarding hiện đang tự làm bằng `Handler.postDelayed` timeout thủ công ở `AppOpenManagerSplash.kt`).
4. `NativeAdPreloadTemplate.kt` (202 dòng, quản lý preload theo từng layout template) — nếu nghiệp vụ thực sự cần "preload N ad theo N layout khác nhau" (mà SDK preloader không hỗ trợ multi-template per id), giữ lại **chỉ phần orchestration** đó, không giữ lại phần tự quản lý hàng đợi/timeout cấp thấp (phần này SDK preloader đã làm).

### 2.5. Model wrapper `Ap*` — cân nhắc lại mức độ cần thiết

`model/wrapper/ApAdBase.kt`, `ApInterstitialAd.kt`, `ApNativeAd.kt`, `ApRewardAd.kt`, `ApRewardItem.kt`, `ApAdError.kt` (tổng 182 dòng) bọc quanh type gốc của SDK Next-Gen (`InterstitialAd`, `NativeAd`, `RewardedAd`, `LoadAdError`...).

Đây là pattern **hợp lý và nên giữ** (repo mẫu Google không cần làm vì họ là sample app dùng thẳng SDK type, nhưng `gma-lib` là *library* nên việc có 1 lớp trừu tượng chống rò rỉ SDK ra ngoài API public là đúng đắn — tránh phá vỡ ABI khi Google đổi API). **Không đề xuất xoá**, chỉ đề xuất:
- Đảm bảo `Ap*` chỉ được tạo ra ở đúng 1 nơi (tầng `Engine`), không rải rác — hiện tại đã đúng hướng này ở phần lớn code đã đọc.
- Khi thêm Ad Placements API (mục 3.3 bên dưới), nhớ expose `placementId` qua các lớp `Ap*` này để giữ tính nhất quán của tầng trừu tượng.

### 2.6. Cấu hình rải rác nhiều nơi, không có 1 nguồn sự thật

| File | Dòng | Vai trò |
|---|---|---|
| `config/AdSdkConfig.kt` | 31 | Config cấp SDK (theo kiểu "adlib") |
| `config/AdsConfig.kt` | 32 | Config cấp SDK (theo kiểu khác — `Ads.init()` dùng cái này) |
| `config/IntegrationConfigs.kt` | 15 | Config tích hợp bên thứ 3 |
| `config/AdjustConfig.kt` | 12 | Config riêng Adjust |
| `config/TaichiConfig.kt` | 7 | Config riêng Taichi |
| `helper/IAdsConfig.kt` | 7 | Interface config cấp helper |
| `helper/banner/BannerAdConfig.kt` | 39 | Config riêng banner |
| `helper/adnative/NativeAdConfig.kt` | 53 | Config riêng native |

**Vấn đề chính không phải số lượng file** (chia nhỏ theo domain là hợp lý), mà là **có 2 config cấp SDK song song** (`AdSdkConfig` dùng bởi `AdSdkInitializer`/`GmaSdk`, `AdsConfig` dùng bởi `engine/Ads.kt`) — không rõ cái nào là nguồn sự thật, dễ gây lệch cấu hình khi 1 app dùng nhầm entrypoint init (`AdSdkInitializer.init()` vs `Ads.getInstance().init()`).

**Đề xuất:** xác định **1 entrypoint init duy nhất** cho `gma-lib` (khuyến nghị giữ `Ads.getInstance().init(application, AdsConfig)` vì đây là nơi thật sự gọi `Admob.getInstance().init()` và setup Adjust/Taichi), biến `AdSdkInitializer` + `AdSdkConfig` thành **deprecated alias mỏng** gọi vào entrypoint kia, tránh 2 luồng khởi tạo độc lập.

### 2.7. Chưa áp dụng các API mới của Next-Gen SDK

Xem bảng ở mục 1.5. Đề xuất bổ sung theo thứ tự ưu tiên (impact vs effort):

1. **`RequestConfiguration.setAgeRestrictedTreatment`** — effort thấp (1 lần setup ở `Admob.init()`), impact cao nếu app có target trẻ em/tuổi teen (compliance).
2. **`MobileAds.openDebugMenu(activity, adUnitId)`** — effort rất thấp, nên thêm 1 hàm tiện ích debug-only vào `AdsManager` hoặc màn hình test có sẵn (`app/.../TestNativeProviderActivity.kt`...), thay thế cách test ad unit thủ công hiện tại.
3. **`AdapterInitializationConfig` (Configurable Init)** — effort trung bình, cần đo TTFA hiện tại trước/sau; hữu ích nếu app có nhiều mediation adapter và muốn ad đầu tiên hiển thị nhanh hơn khi cold start.
4. **Ad Placements (`setPlacementId`)** — effort trung bình (cần model hoá "placement" trong `Ap*`/`AdsCallback` mới ở mục 2.2, cộng thêm việc khai báo placement trên AdMob console) — nên làm sau khi có nhu cầu đo hiệu suất theo vị trí hiển thị cụ thể trong app, không phải ưu tiên kỹ thuật thuần tuý.

### 2.8. File lớn nên tách theo trách nhiệm đơn lẻ (Single Responsibility)

| File | Dòng | Đang gộp bao nhiêu trách nhiệm |
|---|---|---|
| `helper/adnative/NativeAdHelper.kt` | 371 | Load + populate view + preload orchestration + state |
| `helper/banner/BannerAdHelper.kt` | 364 | Load + waterfall + shimmer view + lifecycle + state |
| `gma/AppOpenManager.kt` | 349 | State + lifecycle callback + dialog UI + resume logic (xem 2.1) |
| `gma/AdmobReward.kt` | 237 | Reward + Rewarded Interstitial (2 định dạng trong 1 file) |
| `gma/AdmobSplash.kt` | 212 | Splash-specific interstitial logic |

Đối chiếu: file tương đương lớn nhất trong repo mẫu Google (`AppOpenAdManager.kt`) chỉ ~200 dòng và xử lý đúng 1 định dạng. Đề xuất: sau khi xử lý xong mục 2.1 và 2.4 (gộp/xoá bớt), tách `NativeAdHelper.kt` và `BannerAdHelper.kt` theo ranh giới **state management** (StateFlow, lifecycle) vs **view population** (populate layout, shimmer) — 2 mối quan tâm độc lập hiện đang nằm chung 1 class.

---

## 3. Việc **không nên đổi** (đã đúng hướng, giữ nguyên)

Để tránh refactor lan man, liệt kê rõ các phần đã tốt và không cần động vào:

- Cách `gma/AdmobInterstitial.kt` và `gma/AdmobReward.kt` dùng `PreloadConfiguration` + `*Preloader.start()/pollAd()` — **đây chính là chuẩn cần nhân rộng sang Native/Banner**, không phải chuẩn cần sửa.
- `model/wrapper/InterstitialAdEvent.kt`, `RewardAdEvent.kt` — sealed class theo sự kiện, đúng pattern cần nhân rộng thay cho `AdsCallback` (mục 2.2).
- Waterfall pattern (id đầu tiên load được thắng, banner timeout 10s) đã ghi trong `CLAUDE.md` — giữ nguyên, đây là yêu cầu nghiệp vụ hợp lý không liên quan tới việc SDK có preloader hay không.
- Tầng `Ap*` model wrapper (mục 2.5) — giữ, chỉ mở rộng thêm field khi cần.
- `AdsHelper.kt` (72 dòng, lifecycle-aware base class dùng `StateFlow` + `AtomicBoolean`) — gọn, đúng trách nhiệm, không cần đổi.

---

## 4. Lộ trình đề xuất (ưu tiên theo impact/effort)

### Giai đoạn 1 — Dọn trùng lặp, không đổi hành vi (rủi ro thấp)
1. Gộp 3 file `AppOpenManager*.kt` thành 1 class (mục 2.1).
2. Xoá `manager/InterstitialAdManager.kt` / `manager/RewardAdManager.kt` (hoặc rút gọn thành 1-line deprecated wrapper gọi `AdsManager`) (mục 2.3).
3. Gộp `AdSdkInitializer` + `AdSdkConfig` vào 1 entrypoint init duy nhất (mục 2.6).

### Giai đoạn 2 — Thay preload tự chế bằng SDK Preloader (impact cao nhất)
4. Kiểm tra `NativeAdPreloader`/`BannerAdPreloader`/`AppOpenAdPreloader` có trong `ads-mobile-sdk:1.0.1`.
5. Refactor `helper/adnative/preload/` (566 dòng → dự kiến còn ~100-150 dòng orchestration, xoá phần hàng đợi/timeout tự chế) theo mẫu `AdmobInterstitial.kt`.
6. Thêm preload cho banner nếu nghiệp vụ splash/onboarding cần (thay thế `Handler.postDelayed` timeout thủ công trong `AppOpenManagerSplash.kt`).

### Giai đoạn 3 — Tách `AdsCallback` god-interface (rủi ro trung bình, ảnh hưởng API public)
7. Thiết kế sealed event class cho Banner/Native/Splash/AppOpen theo mẫu `InterstitialAdEvent`/`RewardAdEvent` đã có.
8. Giữ `AdsCallback` cũ như adapter tương thích ngược ở tầng public, không dùng nội bộ nữa.

### Giai đoạn 4 — Bổ sung API Next-Gen SDK mới (theo nhu cầu nghiệp vụ, không gấp về kỹ thuật)
9. `RequestConfiguration.setAgeRestrictedTreatment` (nếu có yêu cầu compliance).
10. `MobileAds.openDebugMenu` cho màn hình test nội bộ.
11. `AdapterInitializationConfig` (Configurable Init) nếu đo được TTFA là vấn đề thực tế.
12. Ad Placements (`setPlacementId`) nếu cần đo hiệu suất theo vị trí hiển thị.

### Giai đoạn 5 — Tách file lớn theo Single Responsibility
13. `NativeAdHelper.kt`, `BannerAdHelper.kt` — tách state management ra khỏi view population (mục 2.8), thực hiện **sau** giai đoạn 2 vì preload refactor sẽ tự nhiên rút gọn 2 file này trước.

---

## 5. Tham chiếu chéo tới repo mẫu (dùng khi implement từng giai đoạn)

| Việc cần làm | File mẫu tương ứng trong `googleads/gma-next-gen-sdk-android-examples` |
|---|---|
| Gộp AppOpenManager thành 1 object | `kotlin/NextGenExample/app/.../appopen/AppOpenAdManager.kt` |
| MyApplication hiển thị app-open qua ProcessLifecycleOwner | `kotlin/NextGenExample/app/.../MyApplication.kt` |
| Consent manager gọn nhẹ | `kotlin/NextGenExample/app/.../GoogleMobileAdsConsentManager.kt` |
| Preload Banner/Native/AppOpen bằng SDK Preloader | `kotlin/NextGenExample/app/.../preloading/{BannerPreloadFragment,NativePreloadFragment,AppOpenPreloadFragment,PreloadMainFragment}.kt` |
| Configurable Init (`AdapterInitializationConfig`) | `kotlin/NextGenExample/app/.../snippets/ConfigurableInitSnippets.kt` |
| Ad Placements (`setPlacementId`) | `kotlin/NextGenExample/app/.../snippets/AdPlacementsSnippets.kt` |
| Age-restricted treatment | `kotlin/NextGenExample/app/.../snippets/RequestConfigurationSnippets.kt` |
| Debug menu built-in | `kotlin/NextGenExample/app/.../snippets/DebuggingSnippets.kt` |
| Impression-level ad revenue | `kotlin/NextGenExample/app/.../snippets/ImpressionLevelAdRevenueSnippets.kt` |
| SCAR (signal collection cho mediation) | `kotlin/NextGenExample/app/.../snippets/{AdMobSCARSnippets,AdManagerSCARSnippets}.kt` |

Ghi chú: repo mẫu có cả bản `java/` song song với `kotlin/` — cùng logic, khác ngôn ngữ, nên nếu cần tra cứu chữ ký API bằng Java thì dùng `java/NextGenExample/app/.../<cùng đường dẫn>.java`.

---

## 6. Rủi ro & lưu ý khi thực hiện

- **API public của `gma-lib`**: nhiều class ở mục 2.1/2.3 (`AppOpenManager.getInstance()`, `InterstitialAdManager`, `RewardAdManager`) có khả năng đang được app ngoài (`app/` sample hoặc app khác dùng `gma-lib` như dependency) gọi trực tiếp — **phải grep toàn bộ `app/` và mọi app tiêu thụ `gma-lib` trước khi xoá/đổi chữ ký**, không chỉ đổi trong `gma-lib`.
- **`AppOpenManager` dùng mutable state qua nhiều file bằng `internal var`**: khi gộp 3 file thành 1, cẩn thận thứ tự khởi tạo (`isInitialized`, `isEnableList`) — có nhánh logic dựa vào cờ này để chọn `init(id)` hay `init(idList)`.
- **Preloader API mới** (`NativeAdPreloader`, `BannerAdPreloader`) cần xác nhận đã có trong version SDK `1.0.1` đang dùng — nếu chưa có, cần nâng version SDK trước, đây sẽ là một bước riêng cần test kỹ (khác phạm vi "dọn kiến trúc" thuần tuý).
- Toàn bộ đề xuất trong tài liệu này ưu tiên **hành vi runtime không đổi** ở giai đoạn 1-2; giai đoạn 3 (đổi `AdsCallback`) mới thực sự chạm vào bề mặt API public và cần kế hoạch deprecation rõ ràng (giữ API cũ ít nhất 1 minor version trước khi xoá).

---

## 7. [Trọng tâm 2026-08-28] Tăng show rate & impression/new user — spec cho codex

> Phạm vi: **chỉ `gma-lib`**, không đụng `adlib`. Mọi API/chữ ký nhắc tới bên dưới đã được xác nhận bằng `javap` trên chính `ads-mobile-sdk-1.3.1.aar` (version thật `gma-lib` dùng, `gma-lib/build.gradle.kts:76`) — không suy đoán từ sample. Đối chiếu thêm với `kotlin/NextGenExample` mới nhất trên GitHub cho phần pattern/best-practice.

### 7.1 App Open: 3 cách triển khai song song — mới sửa xong 1/3 (đường resume)

`gma-lib` hiện có **3 lớp độc lập** cùng lo việc "app open ad", không lớp nào biết tới 2 lớp kia:

| File | Vai trò | Cơ chế load (trước phiên này) |
|---|---|---|
| `helper/appopen/AppOpenManager.kt` | **Resume ad** (quay lại app từ background), đăng ký `Application.ActivityLifecycleCallbacks` | ~~`AppOpenAd.load()` thủ công mỗi lần resume~~ → **đã đổi sang `AppOpenAdPreloader` trong phiên này (xem bên dưới)** |
| `manager/AppOpenAdManager.kt` | **Splash ad** (màn hình chờ khi mở app), gọi tường minh từ `SplashActivity` | ~~`AppOpenAd.load()` thủ công theo waterfall id~~ → **đã đổi sang `AppOpenAdPreloader`, poll theo chu kỳ `SPLASH_POLL_INTERVAL_MS=150ms` thay vì chờ 1 callback load duy nhất, giữ nguyên `timeOut`/`timeDelay`** |
| `helper/appopen/AppOpenAdHelper.kt` (381 dòng, coroutine/`StateFlow`) | Một API thứ 3 cho splash app-open (`AppOpenAdConfig`, `waitLoadAndShow(...)`) — gọi `loadResumeAd()` để uỷ quyền phần resume sang `AppOpenManager`, nhưng tự làm lại phần load/show splash bằng coroutine riêng | `AppOpenAd.load()` thủ công — **chưa đổi** |

Tên hai file `AppOpenManager` (resume) và `AppOpenAdManager` (splash) chỉ khác nhau chữ "Ad" — cực dễ nhầm khi đọc code hoặc khi có người mới join.

**Đã làm trong phiên này:**
- `helper/appopen/AppOpenManager.kt` (đường resume) đổi từ `AppOpenAd.load()` gọi phản ứng mỗi lần resume, sang `AppOpenAdPreloader.start(id, PreloadConfiguration(...))` (gọi 1 lần, có guard bằng `preloadStartedIds`) + `AppOpenAdPreloader.pollAd(id)` khi cần hiển thị.
- `manager/AppOpenAdManager.kt` (đường splash) đổi từ `AppOpenAd.load()` waterfall thủ công (thử từng id tuần tự, id sau chỉ được thử khi id trước fail) sang: gọi `AppOpenAdPreloader.start(...)` cho **toàn bộ** id trong danh sách ngay lập tức (song song, không tuần tự), rồi poll `AppOpenAdPreloader.pollAd(id)` theo chu kỳ 150ms (`SPLASH_POLL_INTERVAL_MS`) cho tới khi có ad hoặc hết `timeOut` — giữ nguyên hành vi `timeDelay` (delay tối thiểu trước khi gọi `listener.onReady()`) và `timeOut` (gọi `listener.onNextAction()` nếu quá hạn) như cũ. Vì dùng chung 1 pool theo `adUnitId` với đường resume ở trên, nếu resume-preload đã kịp preload sẵn ad cho cùng 1 id trước khi splash chạy, splash sẽ thấy ad **ngay lập tức** ở lần poll đầu tiên.
- Bump version publish `gma-lib` (`gma-lib/build.gradle.kts:160`) từ `1.0.3` → `1.0.4`.
- Build pass cả 2 thay đổi: `./gradlew :gma-lib:compileDebugKotlin` → `BUILD SUCCESSFUL`.

Lý do đây là đòn bẩy show-rate lớn nhất trong toàn bộ đợt rà soát: kiểu cũ chỉ bắt đầu `AppOpenAd.load()` **tại đúng thời điểm** `onActivityResumed` bắn ra — nếu người dùng bật lại app trong vòng vài giây, ad gần như chắc chắn **chưa load xong** → show rate của resume ad rất thấp trong các phiên "app switcher" ngắn (chuyển qua app khác rồi quay lại ngay). `AppOpenAdPreloader` giữ sẵn 1 hàng đợi và tự refill nền liên tục, nên xác suất có ad sẵn sàng đúng lúc `onActivityResumed` cao hơn hẳn — đây chính là lý do Google cung cấp API preloader riêng cho từng định dạng thay vì để publisher tự quản lý vòng đời load.

**Việc còn lại cho codex:**
1. **[Chưa làm — rủi ro cao hơn, cần đo TTFA A/B trước/sau, không làm vội]** Bắt đầu `AppOpenAdPreloader.start(...)` cho id splash **sớm hơn**, ngay tại `Application.onCreate`/`Ads.init()`, thay vì đợi `SplashActivity` gọi `loadSplashAppOpenAds` như hiện tại — rút ngắn thêm thời gian từ cold start tới khi có ad sẵn sàng cho user mới. Bước này khác bước đã làm ở trên: bước đã làm chỉ đổi *cơ chế* load (Preloader thay vì `AppOpenAd.load`) nhưng vẫn giữ nguyên *thời điểm* bắt đầu gọi (khi `SplashActivity` chạy); bước này mới thực sự dịch thời điểm bắt đầu sớm hơn.
2. Hợp nhất/đổi tên rõ ràng để tránh nhầm `AppOpenManager` (resume) ↔ `AppOpenAdManager` (splash) ↔ `AppOpenAdHelper` (API thứ 3, coroutine). Đề xuất: đổi tên `manager/AppOpenAdManager.kt` thành `SplashAppOpenManager` (hoặc gộp logic của nó vào `AppOpenAdHelper.waitLoadAndShow` — hiện đang làm gần như cùng việc bằng 2 cơ chế coroutine khác nhau, xem bảng trên) — đây là thay đổi chạm API public, cần khảo sát call site trước như mục 6 đã cảnh báo.

**Giả định cần theo dõi sau khi rollout (không thể xác minh chỉ bằng đọc bytecode):** `AppOpenAd` (interface `common.Ad` mà nó kế thừa) **không** expose bất kỳ getter thời gian-load/tuổi-ad nào ra ngoài — nghĩa là app không thể tự kiểm tra "ad này có quá 4 tiếng chưa" sau khi `pollAd()` trả về. Team đang tin tưởng `AppOpenAdPreloader` tự quản lý đúng chính sách 4 giờ của AdMob nội bộ (đây cũng là cách `InterstitialAdPreloader`/`RewardedAdPreloader` đã được tin tưởng và dùng production trong chính `gma-lib` từ trước). Khuyến nghị: theo dõi show-rate và mọi cảnh báo policy trong AdMob console vài ngày sau khi đổi, thay vì coi đây là đã được chứng minh tuyệt đối.

### 7.2 Chưa có khái niệm "new user" cho App Open — hiện chỉ có "đã từng background" — ✅ Đã làm, xem mục 8.1

`helper/appopen/AppOpenManager.onStart` (dòng ~281-287) có 1 guard: bỏ qua resume ad nếu app **chưa từng vào background** (`hasBeenInBackground == false`) — tức là không bắn resume ad ngay lần mở app đầu tiên trong phiên đó. Đây là bảo vệ đúng nhưng **không phải** là "new user": user cài app hôm nay và user đã dùng app 6 tháng đều được đối xử giống hệt nhau ngay khi cả hai cùng chuyển sang app khác rồi quay lại lần đầu trong phiên.

Google khuyến nghị cân nhắc trì hoãn/giảm tần suất quảng cáo toàn màn hình (đặc biệt app-open) trong vài phiên đầu của user mới để không làm hỏng trải nghiệm/rớt retention D1 trước khi user thấy được giá trị của app — điều này **ảnh hưởng tốt tới impression/new-user về dài hạn** dù có thể giảm show rate tức thời.

**Spec đề xuất cho codex** (opt-in, không tự áp đặt hành vi mặc định — lib không nên tự đọc "số phiên đã dùng" của publisher):
```kotlin
// helper/appopen/AppOpenManager.kt
fun setEligibilityGate(gate: () -> Boolean) { eligibilityGate = gate }
// showResumeAds() / showAdIfAvailable() kiểm tra thêm: eligibilityGate?.invoke() != false
```
Publisher tự cung cấp lambda đọc session-count/first-install-time của họ (ví dụ `SharedPreferences`) — `gma-lib` chỉ expose điểm gắn hook, không tự ý định nghĩa "new user" (mỗi app có ngưỡng khác nhau). Áp dụng tương tự cho `manager/AppOpenAdManager` (splash) nếu publisher muốn.

### 7.3 Ad Placements (`setPlacementId`) đã có ở tầng thấp nhưng chưa ai dùng — ✅ Đã làm, xem mục 8.5

`AdsManager.getAdRequest(adUnitId, placementId: Long? = null, skipUninitializedAdapters: Boolean = false)` (`manager/AdsManager.kt:39-46`) đã support `setPlacementId` — xác nhận `Ad` interface của SDK (`common.Ad`) còn có cả `getPlacementId()/setPlacementId(long)` ngay trên đối tượng ad đã load, không chỉ trên `AdRequest`. Nhưng rà toàn bộ 12 call site đang gọi `AdsManager.getAdRequest(id)` (banner/native/interstitial/reward/app-open) thì **không có call site nào truyền `placementId` khác `null`** — hạ tầng đã sẵn sàng nhưng chưa expose ra config publisher-facing nào.

**Spec cho codex:** thêm `val placementId: Long? = null` vào các data class config theo định dạng (`AppOpenAdConfig`, `BannerAdConfig`, `InterstitialAdConfig`/tương đương, `RewardedAdConfig`) và truyền xuống đúng lệnh gọi `getAdRequest(id, placementId = config.placementId)` tại nơi mỗi helper hiện đang gọi `getAdRequest(id)` trơn. Lợi ích: publisher đo được show rate/eCPM theo **từng vị trí hiển thị trong app** (banner trong feed so với banner cuối màn hình, interstitial sau level 1 so với sau level 5...) ngay trên AdMob console, mà không phải tạo riêng 1 ad unit cho mỗi vị trí — tách biệt rõ "ad unit" (network/mediation) khỏi "placement" (vị trí UI), đúng đúng mô hình `AdPlacementsSnippets.kt` trong sample của Google.

### 7.4 Configurable Init: hạ tầng có sẵn (`AdSdkConfig.adapterInitializationConfig`) nhưng mặc định tắt và không ai tự bật cho đường splash — ✅ Bước 2 đã làm, xem mục 8.3

`config/AdSdkConfig.kt:26` đã có field `var adapterInitializationConfig: AdapterInitializationConfig? = null`, và `engine/Ads.kt:141-176` (`initGma`) đã truyền nó vào `InitializationConfig.Builder(appAdId).setAdapterInitializationConfig(...)` đúng chuẩn Next-Gen SDK — nhưng mặc định `null`, nghĩa là **toàn bộ adapter mediation phải init xong trước khi bất kỳ ad nào (kể cả splash ad open của user mới) được phép load**. Đây trực tiếp kéo dài thời gian tới ấn tượng quảng cáo đầu tiên (TTFA) của user mới trên máy yếu hoặc app có nhiều network mediation.

**Spec cho codex** (rủi ro trung bình — cần đo TTFA A/B trước/sau, KHÔNG bật mặc định cho mọi app dùng `gma-lib`):
1. Cho phép publisher truyền `AdapterInitializationConfig.Builder().setAllowedAdFormats(setOf(AdFormat.APP_OPEN_AD)).build()` (hoặc `setAllowedAdUnitIds`) qua `AdSdkConfig` như đã có sẵn — việc cần làm chỉ là **viết doc hướng dẫn + ví dụ** (infra đã đủ, không cần code mới) trong `Document/app-open-ads.md`.
2. Đảm bảo request splash đầu tiên gọi `AdsManager.getAdRequest(id, skipUninitializedAdapters = true)` (tham số đã tồn tại, hiện không nơi nào truyền `true`) — cần thêm 1 flag trong `AppOpenAdConfig`/lệnh gọi splash để publisher bật được, tương tự mục 7.3.
3. Sau khi splash bắn request xong, gọi `MobileAds.initializeAdapters(AdapterInitializationConfig.Builder().build())` để hoàn tất phần adapter còn lại trong nền — publisher tự quyết định thời điểm gọi (ví dụ sau khi splash dismiss), `gma-lib` chỉ cần expose 1 hàm tiện ích cho việc này thay vì để publisher tự import `MobileAds` trực tiếp.

### 7.5 Ergonomics: 2 khái niệm "splash" gây nhầm, App Open chưa có 1-liner trong `AdsManager` — ✅ Đã làm, xem mục 8.2

`AdsManager.loadSplashInterstitialAds` / `loadSplashListAds` / `onShowSplash` / `onCheckShowSplashWhenFail` (`manager/AdsManager.kt:211-234`) đều route vào `InterstitialAdManager` — tức là **splash bằng interstitial**, không phải app-open. Splash bằng **app-open** (`manager/AppOpenAdManager.loadSplashAppOpenAd`/`showSplashAppOpen`) **không có 1-liner nào trong `AdsManager`** — publisher phải tự `import` thẳng `manager.AppOpenAdManager`, phá vỡ tính nhất quán "mọi thứ đi qua `AdsManager`" mà banner/native/interstitial/reward đang có.

**Spec cho codex:** thêm vào `AdsManager`:
```kotlin
fun loadSplashAppOpenAd(context: Context, id: String, timeOut: Long, timeDelay: Long, listener: AppOpenAdListener) =
    AppOpenAdManager.loadSplashAppOpenAd(context, id, timeOut, timeDelay, listener)
fun showSplashAppOpen(activity: Activity, listener: AppOpenAdListener) =
    AppOpenAdManager.showSplashAppOpen(activity, listener)
```
đặt tên rõ `...AppOpen...` (không phải `...Splash...` trơn) để phân biệt ngay từ tên hàm với các hàm splash-interstitial đã có.

### 7.6 Danh sách việc cho codex — ưu tiên theo impact/effort

| # | Việc | Impact với show-rate/new-user | Effort | Trạng thái |
|---|---|---|---|---|
| 1 | Preloader cho resume app-open | Cao | Thấp | ✅ **Xong** |
| 2 | Preloader cho splash app-open | **Cao nhất** — impression đầu tiên của user mới | Thấp-Trung bình | ✅ **Xong** — đã bump `gma-lib` lên 1.0.4 |
| 3 | Ad Placements qua config (7.3) | Trung bình — chất lượng đo lường, không trực tiếp tăng show rate | Trung bình (nhiều file config) | ✅ **Xong** — xem mục 8.5, phủ mọi định dạng + XML + Compose |
| 4 | 1-liner `AdsManager.loadSplashAppOpenAd/showSplashAppOpen` (7.5) | Thấp trực tiếp, cao cho "dễ tích hợp đúng" | Rất thấp | ✅ **Xong** — xem mục 8.2 |
| 5 | Flag `skipUninitializedAdapters` cho splash (7.4, bước 2) | Cao cho TTFA/new-user trên máy yếu | Trung bình | ✅ **Xong** — xem mục 8.3 (publisher vẫn phải tự cấu hình `adapterInitializationConfig` phù hợp, đây chỉ là bước 2 "bật cờ trên request") |
| 6 | Preload sớm tại `Ads.init()` thay vì đợi `SplashActivity` | Cao cho TTFA/new-user | Trung bình | ✅ **Xong** — xem mục 8.4 |
| 7 | New-user eligibility gate (7.2) | Gián tiếp (retention → LTV impression dài hạn), giảm show rate ngắn hạn | Thấp | ✅ **Xong** — xem mục 8.1 |
| 8 | Đổi tên/gộp 3 class App Open (7.1, bước 2) | Không ảnh hưởng runtime, chỉ ergonomics | Trung bình-Cao | ⏸️ **Chưa làm — cố ý** | 

**Vì sao #8 vẫn chưa làm:** đây là thay đổi duy nhất trong danh sách chạm vào *cấu trúc* API public (đổi tên/gộp class), không phải thêm tham số optional. Mọi việc #1-#7 đều an toàn theo kiểu "thêm tham số có default, không đổi hành vi khi không truyền gì" — có thể build-verify đầy đủ mà không cần chạy app thật. #8 thì khác: cần trước tiên grep toàn bộ mọi app tiêu thụ `gma-lib` (kể cả ngoài repo này) để chắc `AppOpenManager.getInstance()`/`AppOpenAdManager.loadSplashAppOpenAd` không bị gọi trực tiếp ở nơi nào đó, rồi mới thiết kế deprecation path — đúng như mục 6 (Rủi ro) đã cảnh báo trước khi phiên này bắt đầu. Để dành cho một phiên riêng có thể chạy/test app thật.

---

## 8. Hướng dẫn dùng chi tiết các API mới (XML view-based & Jetpack Compose)

> Toàn bộ API dưới đây **build pass** (`./gradlew :gma-lib:assembleDebug`) và **tương thích ngược 100%** — mọi tham số mới đều optional với giá trị mặc định giữ nguyên hành vi cũ. Không cần sửa code hiện có để nâng cấp; chỉ thêm tham số ở nơi muốn dùng tính năng mới.

### 8.1 New-user eligibility gate — trì hoãn app-open ad cho user mới

**Chú giải:** Google khuyến nghị không "dội" quảng cáo toàn màn hình vào user ngay những phiên đầu tiên — dễ khiến user gỡ app trước khi kịp thấy giá trị. `gma-lib` **không tự định nghĩa** thế nào là "user mới" (mỗi app có tiêu chí khác nhau: số phiên, số ngày cài đặt, đã hoàn thành onboarding chưa...) — chỉ expose 1 điểm gắn hook, publisher tự quyết định logic. Không set gì thì hành vi giữ nguyên như trước (luôn cho phép hiển thị).

Đây là app-wide setting (không phụ thuộc XML hay Compose) — gọi 1 lần lúc khởi tạo app:

```kotlin
// Application.onCreate(), sau khi đã init Ads/AdsManager
AdsManager.setAppOpenEligibilityGate {
    val sessionCount = prefs.getInt("session_count", 0)
    sessionCount >= 3   // chỉ cho phép resume app-open ad từ phiên thứ 3 trở đi
}

// Gỡ bỏ giới hạn (quay lại hành vi mặc định):
AdsManager.setAppOpenEligibilityGate(null)
```

**Lưu ý quan trọng:** gate chỉ chặn việc *hiển thị* — preload vẫn chạy nền bình thường qua `AppOpenAdPreloader` bất kể gate trả về gì, nên ad luôn sẵn sàng đúng lúc gate mở, không mất thời gian chờ load lại. Gate hiện chỉ áp dụng cho **resume ad** (`AppOpenManager`), chưa áp dụng cho splash ad — vì splash thường là cơ hội monetize chính đáng ngay từ lần mở đầu tiên, khác với resume (có thể xảy ra dồn dập nếu user chuyển qua lại giữa các app).

### 8.2 1-liner cho splash app-open trong `AdsManager`

**Chú giải:** trước đây `AdsManager.loadSplashInterstitialAds`/`onShowSplash` chỉ phục vụ "splash bằng interstitial". Muốn splash bằng **app-open** phải import thẳng `manager.AppOpenAdManager`, dễ nhầm 2 khái niệm. Giờ cả hai đều gọi được qua `AdsManager`, tên hàm có chữ `AppOpen` để phân biệt rõ.

```kotlin
// SplashActivity.onCreate() — dùng được y hệt dù Activity dùng XML layout hay setContent { } Compose,
// vì đây là lệnh gọi imperative thuần Kotlin, không phụ thuộc UI toolkit.
AdsManager.loadSplashAppOpenAd(
    context = this,
    id = "ca-app-pub-xxx/splash-app-open",
    timeOut = 8_000L,
    timeDelay = 1_500L,        // hiển thị loading tối thiểu 1.5s dù ad load nhanh hơn, tránh nháy màn hình
    listener = object : AppOpenAdListener {
        override fun onReady() { AdsManager.showSplashAppOpen(this@SplashActivity, this) }
        override fun onNextAction() { goToMainScreen() }   // ad không sẵn sàng / lỗi / hết timeout
        override fun onDismissed() { goToMainScreen() }    // user đã xem xong
    },
)
```

### 8.3 Configurable Init — giảm TTFA cho splash ad của user mới

**Chú giải:** mặc định, `MobileAds.initialize()` phải init **xong toàn bộ** adapter mediation trước khi bất kỳ ad nào — kể cả splash ad của user mới — được phép load. Với app dùng nhiều network mediation, đây có thể là vài trăm ms tới vài giây tính từ cold start. `skipUninitializedAdapters()` cho phép bắn request splash ngay khi (các) adapter nó thật sự cần đã sẵn sàng, không đợi các adapter khác.

**Cần cả 2 bước sau mới có tác dụng** (chỉ bật 1 trong 2 sẽ không thay đổi gì):

```kotlin
// Application.onCreate(), TRƯỚC khi gọi Ads.getInstance().initialize(...)
val adSdkConfig = AdSdkConfig(application = this).apply {
    appAdId = "ca-app-pub-xxx~yyyy"
    // Bước 1: giới hạn adapter cần init trước, ví dụ chỉ AdMob network (không đợi các mediation adapter khác)
    adapterInitializationConfig = AdapterInitializationConfig.Builder()
        .setAllowedAdFormats(setOf(AdFormat.APP_OPEN_AD))
        .build()
    // Bước 2: request splash bật cờ "đừng đợi adapter chưa init xong"
    skipUninitializedAdaptersForSplash = true
}
Ads.getInstance().initialize(this, adSdkConfig)
```

**Rủi ro cần biết trước khi bật:** `setAllowedAdFormats` nghĩa là các network mediation khác (banner/interstitial/reward nếu dùng adapter khác AdMob) có thể **chưa init xong** khi app show splash — nếu splash ad của bạn cần 1 mediation adapter cụ thể mà không nằm trong danh sách allowed, nó sẽ không init kịp và splash có thể fail. Nên đo TTFA (time-to-first-ad) trước/sau bằng A/B thực tế, không bật mặc định cho mọi app.

### 8.4 Preload splash sớm — ngay khi SDK init xong, trước cả khi `SplashActivity` chạy

**Chú giải:** trước đây, việc preload app-open ad cho splash chỉ bắt đầu khi `SplashActivity` tự gọi `loadSplashAppOpenAd(s)` — nghĩa là toàn bộ thời gian `Application.onCreate()` → SDK init xong → Android dựng xong `SplashActivity` đều là thời gian "chết", không có request nào chạy. Khai báo `splashAdId`/`splashAdIdList` trong `AdSdkConfig` sẽ bắt đầu preload **ngay khi `Ads.initialize()` xong** — cùng lúc với việc resume-ad cũng được `init()`. `SplashActivity` vẫn gọi `loadSplashAppOpenAd(s)` như bình thường (mục 8.2); nó chỉ nhiều khả năng thấy ad **đã sẵn sàng** ngay từ lần poll đầu tiên thay vì phải chờ.

```kotlin
val adSdkConfig = AdSdkConfig(application = this).apply {
    appAdId = "ca-app-pub-xxx~yyyy"
    splashAdId = "ca-app-pub-xxx/splash-app-open"   // hoặc splashAdIdList = listOf(...) cho waterfall nhiều id
    placementIdSplash = 1234567890L                  // optional — xem mục 8.5
    skipUninitializedAdaptersForSplash = true         // optional — kết hợp mục 8.3 nếu muốn
}
Ads.getInstance().initialize(this, adSdkConfig)
```

An toàn 100% nếu không set `splashAdId`/`splashAdIdList` — hành vi giữ nguyên như cũ (không preload gì thêm).

### 8.5 Ad Placements (`placementId`) — đo show rate/eCPM theo từng vị trí hiển thị

**Chú giải:** `placementId` là khái niệm **khác** `adUnitId`. Một `adUnitId` là 1 đơn vị quảng cáo khai báo trên AdMob console (gắn với network/mediation). `placementId` gắn thêm nhãn "vị trí hiển thị trong app" vào từng request/impression, cho phép so sánh show rate/eCPM giữa các vị trí **dùng chung 1 ad unit** (banner đầu feed vs banner cuối feed, interstitial sau level dễ vs sau level khó...) mà không cần tạo riêng ad unit cho mỗi vị trí. Đây thuần là cải thiện chất lượng đo lường/báo cáo, **không trực tiếp làm tăng show rate** — nhưng giúp tối ưu show rate *dựa trên dữ liệu* thay vì đoán.

`placementId` là số nguyên do chính bạn tự quy ước (không cần khai báo trước trên AdMob console) — ví dụ dùng một hằng số duy nhất cho mỗi vị trí trong app.

**XML / imperative — dùng qua `AdsManager` 1-liner (cách khuyến nghị, giống hệt cho mọi định dạng):**
```kotlin
// Banner
AdsManager.showBanner(
    activity = this, container = binding.flBanner, adUnitId = BANNER_ID,
    placementId = PLACEMENT_HOME_BOTTOM_BANNER,
)

// Interstitial
AdsManager.loadAndShowInterstitial(
    activity = this, adUnitId = INTERSTITIAL_ID,
    placementId = PLACEMENT_AFTER_LEVEL_COMPLETE,
) { goToNextScreen() }

// Rewarded
AdsManager.loadAndShowRewarded(
    activity = this, adUnitId = REWARDED_ID, callback = rewardListener,
    placementId = PLACEMENT_DOUBLE_COINS_BUTTON,
)

// Native
AdsManager.loadNative(
    activity = this, container = binding.flNative, adUnitId = NATIVE_ID, layoutId = R.layout.native_ad_small,
    placementId = PLACEMENT_FEED_ITEM,
)

// Splash / resume app-open — xem mục 8.2/8.4 (tham số placementId đã có sẵn ở đó)
```

**XML / imperative — dùng trực tiếp qua `*Config` (khi cần kiểm soát chi tiết hơn 1-liner, ví dụ dùng thẳng `InterstitialAdHelper`):**
```kotlin
val config = InterstitialAdConfig(adId, canShowAds = true, placementId = PLACEMENT_AFTER_LEVEL_COMPLETE)
InterstitialAdHelper(activity, activity, config).waitLoadAndShow(activity, callback = listener)
```
Áp dụng tương tự cho `RewardedAdConfig`, `BannerAdConfig`, `AppOpenAdConfig` (đều có field `placementId` ở constructor).

**Compose — banner** (`rememberBannerAd`/`BannerAdCard`, `gma-lib/.../compose/BannerAdComposeUI.kt`):
```kotlin
val bannerConfig = remember {
    BannerAdConfig(listOf(BANNER_ID), canShowAds = true, canReloadAds = true, placementId = PLACEMENT_HOME_BOTTOM_BANNER)
}
val holder = rememberBannerAd(config = bannerConfig)
BannerAdCard(holder = holder, modifier = Modifier.fillMaxWidth())
// placementId sống trong BannerAdConfig — cùng 1 config object dùng được cho cả XML (BannerAdHelper) lẫn Compose (rememberBannerAd).
```

**Compose — native (preload buffer, dùng chung với XML qua `NativeAdPreload`):**
```kotlin
val holder = rememberNativeAdPreload(
    tag = "feed_native",
    options = NativeAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(NATIVE_ID),
        autoRequestOnStart = true,
        requestOptions = NativeAdRequestOptions().setPlacementId(PLACEMENT_FEED_ITEM),
    ),
)
NativeAdSection(holder = holder)

// Nếu tự warm buffer trước bằng NativeAdPreload trực tiếp (ví dụ ở màn hình trước đó), truyền
// CÙNG placementId để ad lấy từ buffer và ad load-fresh mang cùng nhãn:
NativeAdPreload.getInstance().preload(
    activity, NATIVE_ID, layoutId = R.layout.native_ad_feed, buffer = 3,
    options = NativeAdRequestOptions().setPlacementId(PLACEMENT_FEED_ITEM),
)
```

**Giới hạn hiện tại (trung thực để codex/dev sau biết mà bổ sung nếu cần):** các composable UI cấp cao có sẵn (`NativeAdCard`, `NativeAdWithPreload`...) chưa có tham số `placementId` riêng ở signature — phải đi qua `options`/`NativeAdPreloadHolderOptions.requestOptions` như ví dụ trên. Đây là lựa chọn có chủ đích (tránh nhân bản tham số ra mọi composable) chứ không phải thiếu sót, nhưng nếu muốn ergonomics cao hơn nữa, bước tiếp theo hợp lý là thêm `placementId: Long? = null` trực tiếp vào chữ ký `NativeAdCard`/`NativeAdWithPreload` cho tiện gọi.

### 8.6 Native ad preload buffer giờ dùng đúng loại request (sửa kèm, không phải tính năng mới)

**Chú giải kỹ thuật:** khi thêm `placementId` cho native, phát hiện `NativeAdPreload.kt` (buffer preload cho Compose) trước đây build request bằng `AdRequest.Builder(id).build()` — một `AdRequest` **trơn**, không mang bất kỳ tuỳ chỉnh native nào (`nativeAdTypes`, `customFormatIds`, kích thước media...) dù `NativeAdRequestOptions` đã tồn tại sẵn cho đường load trực tiếp (`NativeAdManager.loadNativeAdRaw`). Đã hợp nhất 2 đường này qua 1 hàm dùng chung `NativeAdRequestOptions.toNativeAdRequest(adUnitId)`, nên giờ buffer preload cũng tôn trọng đầy đủ `NativeAdRequestOptions` — không chỉ `placementId` mà cả `customFormatIds`, `mediaAspectRatio`, `adChoicesPlacement`... trước đây bị bỏ qua hoàn toàn khi ad tới từ buffer thay vì load trực tiếp.

---

## 9. Việc chưa làm — để lại nguyên vẹn cho phiên sau

- **#8 (mục 7.6):** đổi tên/gộp `AppOpenManager` (resume) / `AppOpenAdManager` (splash) / `AppOpenAdHelper` (API thứ 3, coroutine) — lý do trì hoãn: xem giải thích ngay dưới bảng 7.6.
- **Migrate `helper/appopen/AppOpenAdHelper.kt` sang `AppOpenAdPreloader`:** đây là API thứ 3 cho app-open (coroutine-based, tự quản lý `appOpenAdLoadTime`/`isPreloadedAdFresh()` riêng) — khác cấu trúc với 2 API kia (không phải singleton, có `Deferred`-based preload + `ForegroundGateHandle`), nên việc chuyển sang Preloader không đơn thuần là đổi lệnh gọi mà cần thiết kế lại phần quản lý trạng thái freshness. Đã thêm `placementId` cho class này (mục 8.5) nhưng **chưa** đổi cơ chế load — vẫn dùng `AppOpenAd.load()` thủ công.
- **Frequency cap toàn cục (số interstitial tối đa/ngày):** không nằm trong phạm vi phiên này, publisher hiện tự làm bằng cờ `enabled` truyền vào 1-liner nếu cần.
- **Composable cấp cao (`NativeAdCard`, `NativeAdWithPreload`...) chưa có tham số `placementId` riêng ở signature** — xem giới hạn nêu ở mục 8.5.
