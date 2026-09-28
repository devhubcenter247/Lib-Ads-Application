# Tài liệu hướng dẫn sử dụng AdLib

**Phiên bản:** 1.0.0-alpha-01
**Package:** `com.lib:adlib`
**Yêu cầu tối thiểu:** Android API 24 (Android 7.0) | JVM Target 17

---

## Mục lục

1. [Cài đặt thư viện](#1-cài-đặt-thư-viện)
2. [Khởi tạo SDK](#2-khởi-tạo-sdk)
3. [Quảng cáo Interstitial](#3-quảng-cáo-interstitial)
4. [Quảng cáo Banner](#4-quảng-cáo-banner)
5. [Quảng cáo Native](#5-quảng-cáo-native)
6. [Quảng cáo App Open (Resume)](#6-quảng-cáo-app-open-resume)
7. [Quảng cáo Splash](#7-quảng-cáo-splash)
8. [Quảng cáo Reward](#8-quảng-cáo-reward)
9. [Preload Native Ad](#9-preload-native-ad)
10. [In-App Purchase (Thanh toán)](#10-in-app-purchase-thanh-toán)
11. [Cấu hình Analytics](#11-cấu-hình-analytics)
12. [UMP Consent (GDPR)](#12-ump-consent-gdpr)
13. [Callback và State](#13-callback-và-state)
14. [Lưu ý quan trọng](#14-lưu-ý-quan-trọng)

---

## 1. Cài đặt thư viện

### Bước 1: Thêm repository GitHub Packages

Trong file `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://maven.pkg.github.com/Dong0610/AdsApplication") {
            credentials {
                username = "GITHUB_USERNAME"
                password = "GITHUB_TOKEN" // Cần quyền read:packages
            }
        }
    }
}
```

### Bước 2: Thêm dependency

Trong file `build.gradle.kts` (module app):

```kotlin
dependencies {
    implementation("com.lib:adlib:1.0.0-alpha-01")
}
```

### Bước 3: Đồng bộ Gradle

Nhấn **Sync Now** trong Android Studio.

---

## 2. Khởi tạo SDK

### 2.1. Tạo lớp Application

Kế thừa `AdsMultiDexApplication` và cấu hình trong `onCreate()`:

```kotlin
class MyApplication : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        // 1. Thiết lập môi trường
        val environment = if (BuildConfig.env_dev) {
            AdsConfig.ENVIRONMENT_DEVELOP
        } else {
            AdsConfig.ENVIRONMENT_PRODUCTION
        }
        val adConfig = AdsConfig(this, environment)

        // 2. Thiết bị test (không tính impression thật)
        adConfig.listDeviceTest = listOf("DEVICE_HASH_CUA_BAN")

        // 3. Khoảng cách tối thiểu giữa 2 lần hiển thị interstitial (giây)
        adConfig.intervalInterstitialAd = 15

        // 4. Cấu hình quảng cáo resume (App Open)
        adConfig.idAdResume = "ca-app-pub-xxx/xxx"
        // Hoặc dùng danh sách waterfall:
        // adConfig.listIdAdResume = listOf("id_cao", "id_thap")

        // 5. Khởi tạo SDK
        AdSdkInitializer.init(this, adConfig)

        // 6. Cấu hình toàn cục
        AdsManager.disableAdResumeWhenClickAds = true

        // 7. Tắt resume ads cho màn hình cụ thể
        AdsManager.excludeAppOpen(SplashActivity::class.java)
    }
}
```

### 2.2. Đăng ký Application trong AndroidManifest.xml

```xml
<application
    android:name=".MyApplication"
    ... >
</application>
```

---

## 3. Quảng cáo Interstitial

### 3.1. Sử dụng InterstitialAdHelper (Khuyến nghị)

```kotlin
class MyActivity : AppCompatActivity() {

    private lateinit var interstitialHelper: InterstitialAdHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tạo cấu hình
        val config = InterstitialAdConfig(
            idAds = "ca-app-pub-xxx/xxx",
            canShowAds = true,
            canReloadAds = true
        ).apply {
            setListId(listOf("id_uu_tien_cao", "id_uu_tien_thap"))  // Waterfall
            setIntervalBetweenAds(15L)   // Khoảng cách tối thiểu 15 giây
            setAutoReloadAfterShow(true) // Tự động tải lại sau khi hiển thị
            setLoadTimeout(15_000L)      // Timeout tải quảng cáo (ms)
        }

        // Khởi tạo helper
        interstitialHelper = InterstitialAdHelper(this, this, config)

        // Tải quảng cáo
        interstitialHelper.requestAds(InterstitialAdParam.Request)
    }

    // Hiển thị quảng cáo
    private fun showInterstitial() {
        interstitialHelper.requestAds(InterstitialAdParam.Show)
    }

    // Theo dõi trạng thái bằng StateFlow
    private fun observeAdState() {
        lifecycleScope.launch {
            interstitialHelper.adState.collect { state ->
                when (state) {
                    is AdInterstitialState.Loading -> { /* Đang tải */ }
                    is AdInterstitialState.Loaded -> { /* Đã tải xong, sẵn sàng hiển thị */ }
                    is AdInterstitialState.Dismissed -> { /* Đã đóng */ }
                    is AdInterstitialState.LoadFailed -> { /* Tải thất bại */ }
                    is AdInterstitialState.ShowFailed -> { /* Hiển thị thất bại */ }
                    else -> {}
                }
            }
        }
    }
}
```

### 3.2. Sử dụng Callback

```kotlin
interstitialHelper.registerAdListener(object : AdsCallback() {
    override fun onAdLoaded() {
        // Quảng cáo đã tải xong
    }

    override fun onAdFailedToLoad(adError: ApAdError?) {
        // Tải thất bại
    }

    override fun onAdClosed() {
        // Người dùng đóng quảng cáo
    }

    override fun onNextAction() {
        // Chuyển sang hành động tiếp theo
    }

    override fun onAdClicked() {
        // Người dùng nhấn vào quảng cáo
    }
})
```

### 3.3. Sử dụng Legacy API

```kotlin
// Tải quảng cáo
InterstitialAdManager.getInterstitialAds(context, "ad_id", object : AdsCallback() {
    override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
        // Lưu lại interstitialAd để hiển thị sau
    }
})

// Tải với danh sách waterfall
InterstitialAdManager.getInterstitialAdsList(context, listOf("id1", "id2"), callback)
```

---

## 4. Quảng cáo Banner

### 4.1. Chuẩn bị layout XML

```xml
<!-- Layout chứa banner -->
<FrameLayout
    android:id="@+id/bannerContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />

<!-- Layout shimmer (loading) -->
<com.facebook.shimmer.ShimmerFrameLayout
    android:id="@+id/shimmerBanner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
    <!-- Thêm placeholder view bên trong -->
</com.facebook.shimmer.ShimmerFrameLayout>
```

### 4.2. Sử dụng BannerAdHelper

```kotlin
val bannerConfig = BannerAdConfig(
    idAds = "ca-app-pub-xxx/xxx",
    canShowAds = true,
    canReloadAds = true
).apply {
    setListId(listOf("id1", "id2"))       // Waterfall
    collapsibleGravity = "bottom"          // Banner co lại được (tuỳ chọn)
    // asInlineBanner(maxHeightDp = 50)    // Banner inline (tuỳ chọn)
}

val bannerHelper = BannerAdHelper(this, this, bannerConfig).apply {
    setBannerContentView(findViewById(R.id.bannerContainer))
    setShimmerLayoutView(findViewById(R.id.shimmerBanner))
}

// Tải và hiển thị
bannerHelper.requestAds(BannerAdParam.Request)
```

### 4.3. Theo dõi trạng thái

```kotlin
lifecycleScope.launch {
    bannerHelper.getBannerState().collect { state ->
        when (state) {
            is AdBannerState.Loading -> { /* Đang tải */ }
            is AdBannerState.Loaded -> { /* Đã hiển thị */ }
            is AdBannerState.Error -> { /* Thất bại */ }
            else -> {}
        }
    }
}
```

---

## 5. Quảng cáo Native

### 5.1. Tạo layout cho Native Ad

Tạo file `layout/native_ad_layout.xml`:

```xml
<com.google.android.gms.ads.nativead.NativeAdView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <!-- Tuỳ chỉnh layout hiển thị native ad -->
    <LinearLayout ...>
        <TextView android:id="@+id/ad_headline" ... />
        <TextView android:id="@+id/ad_body" ... />
        <ImageView android:id="@+id/ad_icon" ... />
        <com.google.android.gms.ads.nativead.MediaView
            android:id="@+id/ad_media" ... />
        <Button android:id="@+id/ad_call_to_action" ... />
    </LinearLayout>
</com.google.android.gms.ads.nativead.NativeAdView>
```

### 5.2. Sử dụng NativeAdProviderHelper

```kotlin
val nativeSpec = NativeAdSpec(
    tag = "home_native",
    adUnitIds = listOf("id1", "id2"),
    canShowAds = true,
    canReloadAds = true,
    defaultLayoutId = R.layout.native_ad_layout  // Layout tuỳ chỉnh
)

val nativeHelper = NativeAdProviderHelper(this, this, nativeSpec).apply {
    setNativeContentView(findViewById(R.id.nativeContainer))
    setShimmerLayoutView(findViewById(R.id.shimmerNative))
}

// Tải và hiển thị
nativeHelper.requestAds(NativeAdParam.Request.CreateRequest)

// Khi quay lại màn hình (resume)
nativeHelper.requestAds(NativeAdParam.Request.ResumeRequest)
```

### 5.3. Tuỳ chỉnh hiển thị

```kotlin
nativeHelper.setCustomContentView { apNativeAd ->
    // Tự render native ad theo ý muốn
}
```

---

## 6. Quảng cáo App Open (Resume)

### 6.1. Sử dụng AppOpenAdHelper (Khuyến nghị)

```kotlin
// Trong Application
val appOpenConfig = AppOpenAdConfig(
    idAds = "ca-app-pub-xxx/xxx",
    canShowAds = true
).apply {
    setListId(listOf("id1", "id2"))
    setMaxAdAgeHours(4)      // Quảng cáo hết hạn sau 4 giờ
    setLoadTimeout(15_000L)
}

val appOpenHelper = AppOpenAdHelper(application, appOpenConfig)
appOpenHelper.initialize()  // Bắt đầu lắng nghe lifecycle

// Tắt cho Activity cụ thể
appOpenHelper.disableAppResumeWithActivity(SplashActivity::class.java)
```

### 6.2. Sử dụng Legacy API

```kotlin
// Trong Application.onCreate()
AppOpenManager.getInstance().init(this, "ca-app-pub-xxx/xxx")

// Hoặc dùng danh sách waterfall
AppOpenManager.getInstance().init(this, listOf("id1", "id2"))

// Tắt cho Activity cụ thể
AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

// Tạm tắt/bật
AppOpenManager.getInstance().disableAppResume()
AppOpenManager.getInstance().enableAppResume()
```

---

## 7. Quảng cáo Splash

### 7.1. Sử dụng SplashAdHelper

```kotlin
class SplashActivity : AppCompatActivity() {

    private lateinit var splashHelper: SplashAdHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = SplashAdConfig(
            idAds = "ca-app-pub-xxx/xxx",
            canShowAds = true,
            timeout = 15_000L,  // Timeout chờ tải (ms)
            minDelay = 3_000L   // Thời gian chờ tối thiểu trước khi hiển thị (ms)
        ).apply {
            setListId(listOf("id_cao", "id_thap"))
        }

        splashHelper = SplashAdHelper(this, this, config)

        // Tải và hiển thị
        splashHelper.loadAndShow(
            onAdReady = {
                // Quảng cáo sẵn sàng hiển thị
            },
            onNextAction = {
                // Chuyển sang màn hình chính
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
            onAdClosed = {
                // Người dùng đóng quảng cáo
            },
            onAdFailedToLoad = { error ->
                // Tải thất bại, chuyển luôn
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        splashHelper.cancel()
    }
}
```

---

## 8. Quảng cáo Reward

### 8.1. Tải quảng cáo Reward

```kotlin
RewardAdManager.loadRewardAd(context, "ca-app-pub-xxx/xxx", object : AdsCallback() {
    override fun onRewardAdLoaded(apRewardAd: ApRewardAd?) {
        // Lưu lại apRewardAd để hiển thị
    }

    override fun onAdFailedToLoad(adError: ApAdError?) {
        // Tải thất bại
    }
})

// Tải với danh sách waterfall
RewardAdManager.loadRewardAdList(context, listOf("id1", "id2"), callback)
```

### 8.2. Hiển thị quảng cáo Reward

```kotlin
RewardAdManager.showRewardAd(activity, apRewardAd, object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) {
        // Người dùng xem xong, nhận thưởng
        val amount = rewardItem.amount
        val type = rewardItem.type
    }

    override fun onAdClosed() {
        // Quảng cáo đã đóng
    }

    override fun onRewardedAdClosed(earnedReward: Boolean) {
        // true nếu SDK đã gọi onUserEarnedReward trước khi đóng
    }

    override fun onNextAction() {
        // Tiếp tục luồng UI, không dùng callback này để trao thưởng
    }
})
```

Chỉ trao thưởng trong `onUserEarnedReward`. Nếu người dùng đóng sớm, `onNextAction`
vẫn có thể chạy để tiếp tục UI nhưng `onRewardedAdClosed(false)` cho biết chưa đủ điều kiện nhận thưởng.
`loadRewardAdList()` chỉ cần `Context`; `Activity` vẫn cần cho bước hiển thị.
`ssvCustomData` là payload SSV tùy chọn để backend xác thực điều kiện nhận thưởng.
Giá trị hay dùng cho `ssvCustomData`: `userId`, `sessionId`, `orderId`, hoặc `purchaseToken`.

Helper stateful cũng có sẵn:

```kotlin
private val reward by rewardedAd(adUnitId = "reward_id")
reward.forceShow(object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) { /* trao thưởng */ }
    override fun onRewardedAdClosed(earnedReward: Boolean) { }
})
```

### 8.3. Quảng cáo Reward Interstitial

```kotlin
RewardAdManager.loadRewardInterstitialAd(context, "ad_id", object : AdsCallback() {
    override fun onRewardAdLoaded(apRewardAd: ApRewardAd?) {
        // Hiển thị tương tự reward thường
        RewardAdManager.showRewardAd(activity, apRewardAd, callback)
    }
})

// Alias rõ nghĩa hơn:
RewardAdManager.loadRewardedInterstitialAd(context, "ad_id", callback)
RewardAdManager.showRewardedInterstitialAd(activity, apRewardAd, callback)
```

---

## 9. Preload Native Ad

Tải trước native ad để hiển thị nhanh hơn khi cần.

### 9.1. Đăng ký cấu hình (trong Application)

```kotlin
NativeAds.register(
    NativeAdSpec(
        tag = "home_native",                  // Tag định danh
        adUnitIds = listOf("id1", "id2"),     // Danh sách ID waterfall
        defaultLayoutId = R.layout.native_ad_layout,
        bufferSize = 2,                       // Số lượng tải sẵn
        ttlMs = 4 * 60 * 60 * 1000L,          // Thời gian sống: 4 giờ
        timeoutsMs = listOf(10_000L),         // Timeout mỗi ID
        autoRefill = true                     // Tự động tải bù
    )
)
```

### 9.2. Tải trước

```kotlin
NativeAds.preload(context, tag = "home_native")
```

### 9.3. Lấy quảng cáo đã tải

```kotlin
val preloadedAd = NativeAds.get("home_native")
// preloadedAd sẽ là null nếu chưa có quảng cáo sẵn sàng
```

### 9.4. Sử dụng trong NativeAdHelper

```kotlin
val nativeHelper = NativeAdProviderHelper(this, this, nativeSpec).apply {
    // Tự động lấy từ preload buffer khi spec.tag trùng với tag đã register
}
```

### 9.5. Theo dõi trạng thái

```kotlin
lifecycleScope.launch {
    NativeAds.stateFlow("home_native").collect { state ->
        // Theo dõi trạng thái buffer
    }
}

// Kiểm tra số lượng quảng cáo sẵn sàng
val count = NativeAds.available("home_native")

// Xoá buffer
NativeAds.clear("home_native")
```

---

## 10. In-App Purchase (Thanh toán)

### 10.1. Khởi tạo (trong Application)

```kotlin
val purchaseItems = listOf(
    PurchaseItem("remove_ads", AppPurchase.TYPE_IAP.PURCHASE),       // Mua 1 lần
    PurchaseItem("premium_monthly", AppPurchase.TYPE_IAP.SUBSCRIPTION) // Đăng ký
)

AppPurchase.getInstance().initBilling(this, purchaseItems)

// Lắng nghe kết quả khởi tạo
AppPurchase.getInstance().setBillingListener(BillingListener { resultCode ->
    if (resultCode == 0) {
        // Khởi tạo thành công
    }
})
```

### 10.2. Mua sản phẩm

```kotlin
// Mua sản phẩm 1 lần
AppPurchase.getInstance().purchase(activity, "remove_ads")

// Đăng ký gói
AppPurchase.getInstance().subscribe(activity, "premium_monthly")
```

### 10.3. Lắng nghe kết quả mua

```kotlin
AppPurchase.getInstance().setPurchaseListener(object : PurchaseListener {
    override fun onProductPurchased(productId: String?, transactionDetails: String) {
        // Mua thành công
    }

    override fun displayErrorMessage(errorMsg: String) {
        // Lỗi
    }

    override fun onUserCancelBilling() {
        // Người dùng huỷ
    }
})
```

### 10.4. Kiểm tra trạng thái mua

```kotlin
// Kiểm tra đã mua chưa
val isPurchased = AppPurchase.getInstance().isPurchased()

// Lấy thông tin giá
val price = AppPurchase.getInstance().getPrice("remove_ads")
val priceSub = AppPurchase.getInstance().getPriceSub("premium_monthly")

// Lấy giá kèm tiền tệ
val priceWithCurrency = AppPurchase.getInstance().getPriceWithCurrency(
    "remove_ads",
    AppPurchase.TYPE_IAP.PURCHASE
)

// Lấy chu kỳ đăng ký
val period = AppPurchase.getInstance().getPeriod("premium_monthly")

// Lấy thời gian dùng thử
val trialPeriod = AppPurchase.getInstance().getTrialPeriod("premium_monthly")
```

### 10.5. Ẩn quảng cáo khi đã mua

```kotlin
// Kiểm tra trong cấu hình ad helper
val config = InterstitialAdConfig(
    idAds = "ad_id",
    canShowAds = !AppPurchase.getInstance().isPurchased()
)
```

---

## 11. Cấu hình Analytics

### 11.1. Adjust

```kotlin
val adjustConfig = AdjustConfig(
    enableAdjust = true,
    adjustToken = "YOUR_ADJUST_TOKEN"
).apply {
    eventAdImpressionValue = "EVENT_TOKEN"  // Sự kiện impression có giá trị
    eventNamePurchase = "EVENT_TOKEN"       // Sự kiện mua hàng
    eventAdImpression = "EVENT_TOKEN"       // Sự kiện impression
    fbAppId = "FACEBOOK_APP_ID"            // Facebook App ID (tuỳ chọn)
}

adConfig.adjustConfig = adjustConfig
```

### 11.2. AppsFlyer

```kotlin
val appsflyerConfig = AppsflyerConfig(
    enableAppsflyer = true,
    appsflyerToken = "YOUR_APPSFLYER_TOKEN"
).apply {
    eventAdImpression = "EVENT_NAME"
}

adConfig.appsflyerConfig = appsflyerConfig
```

### 11.3. Firebase Analytics

Firebase Analytics được tích hợp sẵn. Chỉ cần thêm file `google-services.json` vào module app và doanh thu quảng cáo sẽ tự động được theo dõi thông qua `logPaidEvent`.

---

## 12. UMP Consent (GDPR)

Quản lý đồng thuận người dùng theo quy định GDPR:

```kotlin
val adsConsentConfig = AdsConsentConfig(enableUMP = true).apply {
    isEnableDebug = true                // Bật chế độ debug
    testDevice = "YOUR_DEVICE_HASH"     // Hash thiết bị test
    isResetData = true                  // Reset dữ liệu đồng thuận khi khởi động
}

adConfig.adsConsentConfig = adsConsentConfig
```

SDK sẽ tự động hiển thị form đồng thuận khi cần và chỉ tải quảng cáo sau khi có đồng thuận.

---

## 13. Callback và State

### 13.1. AdsCallback

Callback chung cho tất cả loại quảng cáo. Tất cả các phương thức đều là tuỳ chọn (override khi cần):

| Phương thức | Mô tả |
|-------------|-------|
| `onNextAction()` | Chuyển sang hành động tiếp theo |
| `onAdClosed()` | Quảng cáo đã đóng |
| `onAdFailedToLoad(adError)` | Tải quảng cáo thất bại |
| `onAdFailedToShow(adError)` | Hiển thị quảng cáo thất bại |
| `onAdLoaded()` | Quảng cáo đã tải xong |
| `onAdClicked()` | Người dùng nhấn vào quảng cáo |
| `onAdImpression()` | Quảng cáo được hiển thị (impression) |
| `onRewardAdLoaded(apRewardAd)` | Quảng cáo reward đã tải |
| `onUserEarnedReward(rewardItem)` | Người dùng nhận thưởng |
| `onRewardedAdClosed(earnedReward)` | Reward/reward-inter đã đóng, kèm trạng thái đã nhận thưởng hay chưa |
| `onInterstitialLoad(interstitialAd)` | Interstitial đã tải |
| `onInterstitialShow()` | Interstitial đang hiển thị |
| `onNativeAdLoaded(nativeAd)` | Native ad đã tải |
| `onBannerLoaded(adView)` | Banner đã tải |
| `onAdSplashReady()` | Quảng cáo splash sẵn sàng |

### 13.2. StatusAd

Trạng thái của wrapper quảng cáo:

| Giá trị | Mô tả |
|---------|-------|
| `AD_INIT` | Khởi tạo, chưa tải |
| `AD_LOADING` | Đang tải |
| `AD_LOADED` | Đã tải xong |
| `AD_LOAD_FAIL` | Tải thất bại |
| `AD_RENDER_SUCCESS` | Đã render thành công (native) |

### 13.3. StateFlow

Mỗi ad helper cung cấp `StateFlow` để theo dõi trạng thái theo reactive:

```kotlin
// Interstitial
interstitialHelper.adState: StateFlow<AdInterstitialState>

// Banner
bannerHelper.getBannerState(): StateFlow<AdBannerState>

// Native
nativeHelper.getAdNativeState(): StateFlow<NativeAdState>

// Splash
splashHelper.adState: StateFlow<SplashAdState>
```

---

## 14. Lưu ý quan trọng

### Kiểm tra trước khi hiển thị quảng cáo

```kotlin
// Luôn kiểm tra canShowAds dựa trên trạng thái mua hàng
val canShow = !AppPurchase.getInstance().isPurchased()
```

### Waterfall (Danh sách ID ưu tiên)

Tất cả ad helper hỗ trợ waterfall - thử tải từ ID đầu tiên, nếu thất bại hoặc timeout
thì chuyển sang ID tiếp theo. ID đầu tiên load thành công sẽ thắng và các ID phía sau
không được request tiếp.

```kotlin
config.setListId(listOf(
    "ca-app-pub-xxx/yyy",   // ID ưu tiên cao (eCPM cao)
    "ca-app-pub-xxx/zzz"    // ID dự phòng (eCPM thấp)
))
```

Native và banner có guard callback muộn: nếu một ID đã timeout nhưng SDK trả callback sau đó,
callback đó sẽ bị bỏ qua để không ghi đè ad unit đã thắng. Banner waterfall mặc định timeout
10 giây cho mỗi ID.

### Lifecycle

Các ad helper tự động gắn với lifecycle của Activity/Fragment. Không cần tự huỷ, nhưng nên gọi `cancel()` khi không cần nữa.

### Chế độ test

Trong môi trường `ENVIRONMENT_DEVELOP`, SDK sẽ sử dụng quảng cáo test. Đảm bảo chuyển sang `ENVIRONMENT_PRODUCTION` trước khi phát hành.

### Thiết bị test

Thêm hash thiết bị vào `listDeviceTest` để không bị tính impression thật trong khi phát triển:

```kotlin
adConfig.listDeviceTest = listOf("33BE2250B43518CCDA7DE426D04EE231")
```

### Interval giữa các quảng cáo

Sử dụng `intervalInterstitialAd` để tránh hiển thị quảng cáo quá thường xuyên, ảnh hưởng trải nghiệm người dùng:

```kotlin
adConfig.intervalInterstitialAd = 30  // Tối thiểu 30 giây giữa 2 lần hiển thị
```
