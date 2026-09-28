# Interstitial Ads

Tài liệu đối chiếu với code trong repo. Chọn ví dụ theo module đang tích hợp:

| API | `adlib` | `gma-lib` (Next-Gen) |
|---|---|---|
| Callback | `AdsCallback()` | `InterstitialAdListener` |
| `lifecycleOwner` ở extension | Tùy chọn, mặc định `null` | Bắt buộc |
| Interval / auto-reload ở extension | Có tham số trực tiếp | Cấu hình qua `InterstitialAdConfig` |
| Preload application-wide theo tag | Không có trong helper | `preload(tag)` / `preloadAds(tag)` |
| `forceShow` | `forceShow(callback, activity)` | `forceShow(activity, interstitialAd, callback)` |

Hai module có nhiều class trùng package/tên nhưng khác chữ ký API; không dùng lẫn ví dụ.
Khởi tạo SDK và hoàn thành consent trước khi yêu cầu quảng cáo. Với Next-Gen, xem
[gma-lib.md](gma-lib.md).

## `adlib`: triển khai trong Activity

```kotlin
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.helper.interstitial.interstitialAd

private val inter by interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = this,
    autoLoad = false,
    intervalBetweenAds = 15, // giây, áp dụng trong helper này
)

// Load trước tại điểm phù hợp trong lifecycle:
inter.requestAds(InterstitialAdParam.Request)

// Tại điểm chuyển màn: dùng ad đã có, hoặc bắt đầu/chờ load nếu chưa có.
inter.waitLoadAndShow(
    activity = this,
    timeoutMs = 15_000L,
    callback = object : AdsCallback() {
        override fun onNextAction() {
            // Tiếp tục luồng khi ad đóng, bị bỏ qua hoặc không có ad.
        }
    },
)
```

Import thêm `com.lib.ads.gma.ads.helper.interstitial.InterstitialAdParam` khi gọi `requestAds`.
`interstitialAd(...)` trả về `Lazy<InterstitialAdHelper>`: `autoLoad = true` (mặc định)
chỉ bắt đầu load khi truy cập helper lần đầu, không phải lúc khai báo property.

Các tham số extension: `adUnitId`, `autoLoad = true`, `autoReloadAfterShow = true`,
`intervalBetweenAds = 0L`, `loadTimeoutMs = 30_000L`, `canShowAds = true`,
`canReloadAds = true`, `lifecycleOwner = null`, `adCallback = null`.
`interstitialAdWaterfall(adUnitIds = listOf(idHigh, idLow), ...)` nhận các tùy chọn tương tự.

Truyền `lifecycleOwner` để helper tự hủy khi destroy và có thể reload khi resume.
Nếu bỏ owner, phải tự gọi `inter.destroy()` khi không còn dùng helper.

Trong Fragment, tạo helper sau khi view được tạo để dùng đúng `viewLifecycleOwner`:

```kotlin
// Trong onViewCreated; giữ helper ở field nếu cần dùng lại.
val inter = requireActivity().interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = viewLifecycleOwner,
).value
```

### Các cách show của `adlib`

```kotlin
// Chỉ show ad đã có; chờ request đang chạy nhưng KHÔNG tự bắt đầu request mới.
inter.forceShow(
    callback = object : AdsCallback() {
        override fun onNextAction() { /* tiếp tục luồng */ }
    },
    activity = this,
)

// Chỉ dùng ad đã có và Activity lấy từ Context khi tạo helper.
// Nhận sự kiện qua listener đã đăng ký.
inter.requestAds(InterstitialAdParam.Show)

inter.isAdLoaded()
inter.getLoadedAd()
```

`forceShow` vẫn kiểm tra interval. `waitLoadAndShow` có thêm `enabled = true`,
`timeoutMs = config.loadTimeout` và `showWhenReturnFromBackground = true`.
Nếu ad sẵn sàng khi app đang ở background, mặc định chờ app trở lại foreground;
đặt `false` để bỏ lần show đó và tiếp tục luồng.

### Manager và splash của `adlib`

```kotlin
InterstitialAdManager.getInterstitialAds(
    context = this,
    id = BuildConfig.ad_interstitial,
    adCallback = object : AdsCallback() {
        override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
            // Giữ ad để show sau.
        }
        override fun onAdFailedToLoad(adError: ApAdError?) {}
    },
)

InterstitialAdManager.getInterstitialAdsList(this, listOf(idHigh, idLow), callback)
InterstitialAdManager.showInterstitial(this, loadedAd, callback, checkInterval = true)
```

Wrapper của `adlib` nằm trong `com.lib.ads.gma.ads.ads.wrapper`.
`checkInterval` của manager mặc định là `false`; khi bật, dùng cấu hình SDK
`intervalInterstitialAd` và thời điểm impression được lưu, khác interval riêng của helper.

```kotlin
InterstitialAdManager.loadSplashInterstitialAds(
    context = this,
    id = BuildConfig.ad_interstitial_splash,
    enabled = true,
    timeOut = 30_000L,
    timeDelay = 3_000L,
    adListener = object : AdsCallback() {
        override fun onAdSplashReady() {
            InterstitialAdManager.onShowSplash(
                activity = this@SplashActivity,
                adListener = object : AdsCallback() {
                    override fun onNextAction() { /* mở màn tiếp theo */ }
                },
            )
        }
        override fun onNextAction() { /* tiếp tục khi không có ad */ }
    },
)
```

Đổi `SplashActivity` thành tên Activity thực tế. Waterfall splash dùng
`loadSplashListAds(context, listId, enabled, timeOut, timeDelay, adCallback)`.
`onCheckShowSplashWhenFail(activity, callback, timeDelay)` kiểm tra ad splash đến muộn;
`timeDelay` của hàm này là `Int`, đơn vị mili giây.

## `gma-lib`: triển khai trong Activity

```kotlin
import com.lib.ads.gma.ads.helper.interstitial.interstitialAd
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener

private val inter by interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = this,
    autoLoad = false,
    loadTimeoutMs = 30_000L,
    preloadTag = "after_onboarding",
)

// Tại điểm chuyển màn; guard riêng cho mỗi lần chuyển màn.
var continued = false
inter.waitLoadAndShow(
    activity = this,
    timeoutMs = 15_000L,
    callback = object : InterstitialAdListener {
        override fun onNextAction() {
            if (continued) return
            continued = true
            // Tiếp tục luồng.
        }
    },
)
```

**Thời điểm callback trong code hiện tại:** helper Next-Gen gọi `onNextAction()` ngay
trước `interstitialAd.show(activity)`, rồi gọi lại khi ad đóng hoặc SDK báo lỗi show.
Vì vậy callback này không có nghĩa là “ad đã đóng” và có thể được gọi nhiều lần.
Ví dụ trên chỉ chống lặp hành động; nếu cần xử lý sau khi đóng ad, dùng `onDismissed(ad)`.
Manager Next-Gen cũng gọi `onNextAction()` trước show, nhưng khi đóng chỉ gọi `onDismissed(ad)`.

Các tham số của `interstitialAd` Next-Gen: `adUnitId`, `lifecycleOwner`,
`autoLoad = true`, `loadTimeoutMs = 30_000L`, `canShowAds = true`,
`adCallback = null`, `preloadTag = null`.
`interstitialAdWaterfall` thay `adUnitId` bằng `adUnitIds: List<String>`.

### Config thủ công: interval, reload, placement

```kotlin
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdConfig
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdHelper
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdParam

private val inter by lazy {
    val config = InterstitialAdConfig.waterfall(
        adUnitIds = listOf(idHigh, idLow),
        intervalBetweenAds = 15L,
        autoReloadAfterShow = true,
        loadTimeoutMs = 30_000L,
        canShowAds = true,
        canReloadAds = true,
    ).setPreloadTag("after_onboarding")
    InterstitialAdHelper(this, this, config)
}

inter.requestAds(InterstitialAdParam.Request)
inter.waitLoadAndShow(activity = this, callback = listener)

// forceShow yêu cầu truyền raw ad và helper phải có ad đã load.
inter.getLoadedAd()?.let { ad ->
    inter.forceShow(activity = this, interstitialAd = ad, callback = listener)
}
```

`simple(adUnitId, ...)` có cùng tùy chọn với `waterfall(adUnitIds, ...)`.
Các setter: `setListId`, `setIntervalBetweenAds` (giây), `setAutoReloadAfterShow`,
`setLoadTimeout` (mili giây), `setPreloadTag`.
Nếu cần `placementId`, truyền vào constructor
`InterstitialAdConfig(listId = ids, canShowAds = true, placementId = placementId)`.

### Preload giữa các màn hình bằng tag (chỉ `gma-lib`)

```kotlin
// Màn trước: autoLoad=false để không tạo thêm request load cục bộ khi lấy helper.
private val preloadInter by interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = this,
    autoLoad = false,
    preloadTag = "after_onboarding",
)

// Gọi khi SDK/consent đã sẵn sàng.
preloadInter.preload() // alias: preloadAds(); cũng có thể truyền tag trực tiếp

// Màn sau:
private val inter by interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = this,
    autoLoad = false,
    preloadTag = "after_onboarding",
)

inter.waitLoadAndShow(activity = this, callback = listener)
```

Preload đi qua manager vào cache application-wide, không gắn với lifecycle màn trước.
Ad được lấy khỏi cache một lần qua `Request` hoặc `waitLoadAndShow`.
Tag bỏ trống dùng ad unit ID không rỗng cuối cùng trong `listId` làm fallback.
`preload("custom_tag")` yêu cầu màn sau dùng đúng tag đó.

`preload()` không đưa ad vào field cục bộ: `isAdLoaded()` của helper preload có thể vẫn
là `false`. Listener của helper nhận `onLoaded` khi lấy ad từ cache, không phải khi
preload vừa hoàn tất. Nếu cache chưa có ad lúc show, helper có thể bắt đầu request
cục bộ; nó không chờ/join request preload đang chạy.

### Manager và splash của `gma-lib`

```kotlin
import com.lib.ads.gma.ads.manager.InterstitialAdManager
import com.lib.ads.gma.ads.model.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.model.wrapper.ApAdError

// Load một ID, trả wrapper cho listener; không tự lưu vào cache theo tag.
InterstitialAdManager.getInterstitialAds(BuildConfig.ad_interstitial, listener)

// Waterfall, lưu ad thắng vào cache theo tag.
InterstitialAdManager.loadInterstitial("after_onboarding", listOf(idHigh, idLow), listener)
// Tương đương: getInterstitialAdsList(tag, ids, listener, placementId = null)
InterstitialAdManager.showInterstitial(this, "after_onboarding", listener)

// Hoặc show wrapper đã giữ từ callback load:
InterstitialAdManager.forceShowInterstitial(this, loadedAd, listener)
```

Manager Next-Gen không có tham số `checkInterval`. Với ad không ready,
`forceShowInterstitial` gọi `onFailedToShow`, không gọi `onNextAction` ở nhánh này.

```kotlin
InterstitialAdManager.loadSplashInterstitialAds(
    id = BuildConfig.ad_interstitial_splash,
    timeOut = 30_000L,
    timeDelay = 3_000L,
    listener = object : InterstitialAdListener {
        override fun onReady() {
            InterstitialAdManager.onShowSplash(this@SplashActivity, showListener)
        }
        override fun onFailed(error: ApAdError) { /* xử lý load thất bại */ }
        override fun onNextAction() { /* xử lý timeout hoặc bỏ qua */ }
    },
)
```

Waterfall splash dùng `loadSplashListAds(listId, timeOut, timeDelay, listener)`.
`timeOut`/`timeDelay` là mili giây. Nhánh load lỗi gọi `onFailed`; timeout không có ad
mới gọi `onNextAction`. Ad đến sau timeout vẫn có thể gọi `onReady`, nên màn splash
cần kiểm tra đã chuyển màn hay chưa trước khi gọi show/điều hướng.

## Hành vi chung của helper và các điểm cần phân biệt

- Waterfall helper thử tuần tự, dừng ở ID đầu tiên load thành công. `loadTimeoutMs`
  áp dụng cho **mỗi ID**. Manager waterfall chuyển ID khi callback load lỗi và
  không có tham số timeout riêng cho mỗi ID.
- `waitLoadAndShow.timeoutMs` là thời gian chờ kết quả load của lần gọi show.
  Hết thời gian chờ không hủy load đang chạy; ad đến muộn có thể được giữ cho lần sau.
- Interval thuộc từng instance helper, tính từ lúc SDK báo ad đã show;
  `forceShow` không bỏ qua interval.
- Sau khi đóng ad, helper reload nếu `autoReloadAfterShow && canReloadAd()`.
  Reload khi resume là nhánh riêng, kiểm tra helper còn active, chưa có ad và được phép request/reload.
- `requestAds(Show)` không tự load/chờ load. Nếu `canRequestAds()` trả `false`,
  `requestAds(...)` thoát sớm, không bảo đảm gọi callback điều hướng.
- Cả hai helper có `isAdLoaded()`, `getLoadedAd()`, `cancel()`, `registerAdListener`,
  `unregisterAdListener`, `unregisterAllAdListeners`; không có public `adState`.
- Listener truyền vào một lần show được gỡ ở các nhánh hoàn tất. Listener đăng ký
  lâu dài nhận nhiều lần show; tránh điều hướng cùng lúc ở cả hai nơi.

Nguồn đối chiếu:
[adlib extension](../adlib/src/main/java/com/lib/ads/gma/ads/helper/interstitial/InterstitialAdExt.kt),
[adlib helper](../adlib/src/main/java/com/lib/ads/gma/ads/helper/interstitial/InterstitialAdHelper.kt),
[adlib manager](../adlib/src/main/java/com/lib/ads/gma/ads/manager/InterstitialAdManager.kt),
[gma-lib extension](../gma-lib/src/main/java/com/lib/ads/gma/ads/helper/interstitial/InterstitialAdExt.kt),
[gma-lib helper](../gma-lib/src/main/java/com/lib/ads/gma/ads/helper/interstitial/InterstitialAdHelper.kt),
[gma-lib manager](../gma-lib/src/main/java/com/lib/ads/gma/ads/manager/InterstitialAdManager.kt).
