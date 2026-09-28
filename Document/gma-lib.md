# `gma-lib` — Hướng dẫn tích hợp quảng cáo & Billing

Tài liệu duy nhất cho module `gma-lib` (Google Mobile Ads **Next-Gen** SDK). Mọi API, tên hàm và
tham số dưới đây đã được đối chiếu với source hiện tại trong `gma-lib/src/main/java/com/lib/ads/gma/`.
Khi code đổi, sửa tài liệu này theo đúng cách đó: đọc file `.kt` trước rồi mới viết.

> Module `adlib` có API khác (callback `AdsCallback`, `BannerAdHelper`, `NativeAdProviderHelper`...)
> và có tài liệu riêng: [README.md](README.md), [DOCUMENTATION.md](DOCUMENTATION.md). Không dùng lẫn
> ví dụ giữa hai module.

## Mục lục

1. [Cài đặt & khởi tạo](#1-cài-đặt--khởi-tạo)
2. [UMP Consent](#2-ump-consent)
3. [Khái niệm chung: tag, waterfall, holder, placement](#3-khái-niệm-chung-tag-waterfall-holder-placement)
4. [Banner](#4-banner)
5. [Native](#5-native)
6. [Quảng cáo trong LazyColumn/LazyRow](#6-quảng-cáo-trong-lazycolumnlazyrow)
7. [Interstitial](#7-interstitial)
8. [Rewarded & Rewarded-Interstitial](#8-rewarded--rewarded-interstitial)
9. [App Open (resume & splash)](#9-app-open-resume--splash)
10. [Picture-in-picture](#10-picture-in-picture)
11. [Billing (Google Play)](#11-billing-google-play)
12. [Configurable Init — giảm TTFA cho splash](#12-configurable-init--giảm-ttfa-cho-splash)
13. [Debug, log & test ID](#13-debug-log--test-id)
14. [Checklist tích hợp](#14-checklist-tích-hợp)

---

## 1. Cài đặt & khởi tạo

### 1.1 Dependency

```kotlin
// app/build.gradle.kts — cùng project
implementation(project(":gma-lib"))

// hoặc bản publish (GitHub Packages)
implementation("com.lib:gmasdk:<version>")
```

`gma-lib` đã khai báo GMA Next-Gen SDK (`ads-mobile-sdk`), UMP, Play Billing và adapter mediation.
**Không** thêm `com.google.android.gms:play-services-ads` và **không** đóng gói chung với `adlib`:
Next-Gen SDK trùng class với SDK cũ và gây lỗi duplicate class.

### 1.2 Application

```xml
<application android:name=".MyApplication" ... />
```

```kotlin
class MyApplication : AdsMultiDexApplication() {

    override fun createAdSdkConfig(): AdSdkConfig = AdSdkConfig(
        this,
        if (BuildConfig.DEBUG) AdSdkConfig.ENVIRONMENT_DEVELOP else AdSdkConfig.ENVIRONMENT_PRODUCTION,
    ).apply {
        appAdId = "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"      // bắt buộc (App ID, dấu ~)
        listIdAdResume = listOf(BuildConfig.ad_app_open)         // app-open resume, optional
        splashAdId = BuildConfig.ad_app_open_splash              // preload splash sớm, optional
        // listDeviceTest = listOf("HASHED_TEST_DEVICE_ID")
    }
}
```

`AdsMultiDexApplication.onCreate()` tự làm, đúng một lần:

1. Gọi `createAdSdkConfig()`; thiếu `appAdId` thì ném exception.
2. `Ads.getInstance().initialize(...)`: request configuration/test device, init GMA SDK, app-open
   resume (nếu có `idAdResume`/`listIdAdResume`), preload splash (nếu có `splashAdId`/`splashAdIdList`).
3. Khởi tạo Firebase Analytics và lưu thời điểm cài đặt.

Không tự gọi `MobileAds.initialize()` hay `Ads.getInstance().initialize()` thêm lần nữa.

**Consent trước rồi mới init SDK:** override `initializeAdsOnCreate() = false`, rồi gọi
`(application as AdsMultiDexApplication).initializeAds { /* SDK ready */ }` sau khi UMP xong.

### 1.3 `AdSdkConfig`

| Field | Kiểu | Mặc định | Ghi chú |
|---|---|---|---|
| `appAdId` | `String?` | `null` | **Bắt buộc**, App ID (dấu `~`) |
| constructor `environment` / `isVariantDev` | `String` / `Boolean` | production | Develop: bật log, dev purchase flow (mục 11.7) |
| `listDeviceTest` | `List<String>` | rỗng | Hash test device |
| `requestConfiguration` | `RequestConfiguration?` | `null` | Ghi đè `listDeviceTest` nếu set |
| `idAdResume` / `listIdAdResume` | `String?` / `List<String>?` | `null` | Ad unit app-open **resume** (list = waterfall) |
| `placementIdAdResume` | `Long?` | `null` | Placement id cho resume app-open |
| `splashAdId` / `splashAdIdList` | `String?` / `List<String>?` | `null` | Preload splash app-open ngay khi SDK init xong |
| `placementIdSplash` | `Long?` | `null` | Placement id cho preload splash |
| `skipUninitializedAdaptersForSplash` | `Boolean` | `false` | Mục 12 |
| `adapterInitializationConfig` | `AdapterInitializationConfig?` | `null` | Mục 12 |
| `adjustConfig` | `AdjustConfig?` | `null` | Adjust SDK |
| `showMessageForTester` | `Boolean` | `false` | Overlay debug trên banner/native |

### 1.4 App ID vs Ad Unit ID

```text
App ID:      ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy   (dấu ~, AdSdkConfig.appAdId)
Ad Unit ID:  ca-app-pub-xxxxxxxxxxxxxxxx/yyyyyyyyyy   (dấu /, từng quảng cáo)
```

Đặt Ad Unit ID qua `BuildConfig`/flavor, không hard-code trong UI.

---

## 2. UMP Consent

```kotlin
class SplashActivity : AppCompatActivity() {
    private val consentManager by lazy { ConsentManager(this) }

    private suspend fun requestConsent() {
        consentManager.requestUMP()              // suspend: cập nhật consent + hiện form nếu cần
        if (consentManager.canRequestAds) {
            // tiếp tục splash / màn chính
        }
    }
}
```

`requestUMP()` (bí danh `loadAndShowConsentForm()`) tự retry khi lỗi mạng và luôn hoàn tất, kể cả
khi UMP thất bại, nên không treo splash.

Privacy options (màn Settings):

```kotlin
if (ConsentManager(this).isPrivacyOptionsRequired) {
    lifecycleScope.launch { ConsentManager(this@SettingsActivity).showPrivacyOption() }
}
```

---

## 3. Khái niệm chung: tag, waterfall, holder, placement

**Waterfall.** Mọi định dạng nhận danh sách ad unit id và thử **tuần tự**; id đầu tiên load thành
công thắng, dừng waterfall. Timeout áp dụng cho **từng id**. Callback đến muộn của id đã quá
timeout không thay được ad đã thắng.

**Tag.** Một chuỗi ổn định theo vị trí hiển thị (`home_banner`, `feed_native`,
`after_onboarding`...). Tag sở hữu một buffer:

| Định dạng | Buffer theo tag |
|---|---|
| Banner | `BannerAds` + `BannerAdSpec` |
| Native | `NativeAds` + `NativeAdSpec` |
| Interstitial / Rewarded / Splash app-open | `FullScreenAdLruCache` (qua manager/helper) |

Preload ở màn trước theo tag, màn sau tiêu thụ đúng tag đó. Ad lấy khỏi buffer chỉ dùng một lần.

**Holder (banner & native).** Holder là object gắn với một vị trí hiển thị, giữ state
`Idle/Loading/Loaded(Success)/Error/Cancelled`. Mọi holder theo cùng quy tắc:

| Tình huống | Kết quả |
|---|---|
| Tag có sẵn ad trong buffer | Lấy ngay, không hiện Loading |
| Tag đang preload | Chờ preload đó rồi lấy |
| Buffer trống | Holder tự load waterfall (ids của spec đã register, hoặc `fallbackAdUnitIds`) |
| `request()`/`reload()`/resume khi đang có request chạy | Dùng lại request đang chạy |
| `request()`/`reload()`/resume khi ad đang hiện **đã có impression** | Load ad mới; ad cũ hiện tới khi có ad mới |
| `request()`/`reload()`/resume khi ad đang hiện **chưa có impression** | Bỏ qua (tránh tụt show rate) |
| `reload(force = true)` | Thay ngay, không cần impression |
| Lỗi khi đang hiện ad | Giữ ad cũ, không gọi `onFailed` |
| Lỗi khi chưa có ad | `Error` + `onFailed` |
| Ad về sau khi holder đã huỷ/đã có request mới | Giữ vào buffer của tag cho lần sau (buffer đầy/hết hạn thì destroy) |
| `cancel()` | Dừng load, **destroy** ad đang hiện, state `Cancelled` |

Holder không cần Activity: load dùng application context. Holder XML gắn với `lifecycleOwner`
truyền vào options; holder Compose gắn với `LocalLifecycleOwner` và dispose khi rời composition.

**Ad Placements (`placementId`).** Số nguyên tự quy ước, gắn nhãn "vị trí hiển thị" vào request để
so sánh show rate/eCPM giữa các vị trí dùng chung một ad unit. Có ở mọi entry point:

```kotlin
AdsManager.showBanner(..., placementId = AdPlacement.HOME_BANNER)
AdsManager.loadAndShowInterstitial(..., placementId = AdPlacement.AFTER_LEVEL) { }
AdsManager.loadAndShowRewarded(..., placementId = AdPlacement.DOUBLE_COINS)
AdsManager.loadNative(..., placementId = AdPlacement.FEED)
AdsManager.loadSplashAppOpenAd(..., placementId = AdPlacement.SPLASH)

BannerAdPreloadHolderOptions(placementId = ...) / BannerAdSpec(placementId = ...)
NativeAdRequestOptions().setPlacementId(...)          // native holder, NativeAdSpec
InterstitialAdConfig(listOf(id), canShowAds = true, placementId = ...)
RewardedAdConfig(listOf(id), placementId = ...)
AppOpenAdConfig(tagConfig = "splash", listId = listOf(id), placementId = ...)
```

Đặt hằng số tập trung, ví dụ `object AdPlacement { const val HOME_BANNER = 1001L; ... }`.

---

## 4. Banner

Package `com.lib.ads.gma.ads.helper.banner` (+ `.preload`, `.params`).

### 4.1 XML — một dòng

```kotlin
val banner: BannerAdHolder = AdsManager.showBanner(
    activity = this,
    container = binding.flBanner,          // FrameLayout
    adUnitId = BuildConfig.ad_banner,
    enabled = !AppPurchase.getInstance().isPurchased(),
    placementId = AdPlacement.HOME_BANNER, // optional
    callback = object : BannerAdListener {
        override fun onLoaded(adView: AdView?) {}
        override fun onFailed(error: ApAdError) {}
    },
)
```

### 4.2 XML — holder + `bindToContainer`

Dùng khi cần kích thước khác, collapsible, waterfall hoặc preload theo tag:

```kotlin
private val bannerHolder by createBannerAdHolder(
    tag = "home_banner",
    options = BannerAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(BuildConfig.ad_banner_high, BuildConfig.ad_banner_low),
        lifecycleOwner = this,            // reload khi resume, dispose ở ON_DESTROY
        autoRequestOnStart = true,        // false = tự gọi bannerHolder.request()
        autoReloadOnResume = true,
        size = BannerSize.LargePortraitAdaptive,
        collapsibleGravity = BannerCollapseGravity.BOTTOM,
        placementId = AdPlacement.HOME_BANNER,
    ),
)

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bannerHolder.registerAdCallback(myBannerListener)
    bannerHolder.bindToContainer(this, binding.flBanner, BannerCollapseGravity.BOTTOM)
}
```

`bindToContainer` hiện child `shimmer_container_banner` của container khi đang load, gắn banner khi
có, ẩn container khi lỗi/huỷ. Muốn tự render, collect `bannerHolder.state`
(`BannerAdDisplayState.Loaded(adView, adUnitId)`) và tự `addView`.

Gọn hơn cho Activity:

```kotlin
private val banner by bannerAdWaterfall(
    adUnitIds = listOf(BuildConfig.ad_banner_high, BuildConfig.ad_banner_low),
    container = { binding.flBanner },
    collapsibleGravity = BannerCollapseGravity.BOTTOM,
    useInline = false,        // true = BannerSize.InlineAdaptive(maxHeightDp)
)
```

**`BannerSize`:** `LargePortraitAdaptive` (mặc định), `InlineAdaptive(maxHeightDp)`, `Width(widthDp)`,
`Height(heightDp)`, `Fixed(widthDp, heightDp)`.

### 4.3 Compose

```kotlin
@Composable
fun HomeBanner() {
    val config = remember {
        BannerAdConfig(listOf(BuildConfig.ad_banner), canShowAds = true, canReloadAds = true)
            .setSize(BannerSize.InlineAdaptive(60))
    }
    BannerAd(config = config)     // shimmer mặc định theo kích thước, tự request + reload khi resume
}
```

Tự điều khiển holder:

```kotlin
val holder = rememberBannerAd(
    tag = "home_banner",
    options = BannerAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(BuildConfig.ad_banner),
        autoRequestOnStart = true,
    ),
    callback = myBannerListener,
)
BannerAdCard(
    holder = holder,
    loading = { DefaultBannerAdLoading(holder.loadingHeightDp) },   // optional
    error = { },
)
```

`rememberBannerAd(config)` dùng tag `config.tag` (`setTag(...)`) hoặc tự sinh từ id/size/gravity/
placement. Shimmer: `DefaultBannerAdLoading`, `DefaultLargeBannerAdLoading`, màu qua
`BannerLoadingColors`.

### 4.4 Preload banner ở màn trước

```kotlin
BannerAds.preload(
    BannerAdSpec(
        tag = "home_banner",
        adUnitIds = listOf(BuildConfig.ad_banner),
        size = BannerSize.LargePortraitAdaptive,
        collapsibleGravity = BannerCollapseGravity.BOTTOM,
        bufferSize = 1,
    ),
)
```

Holder cùng tag ở màn sau lấy banner này trước. Theo dõi buffer: `BannerAds.stateFlow(tag)`
(`BannerPreloadState`), `available(tag)`, `readyAdUnitId(tag)`. Preload không tự retry khi lỗi.

---

## 5. Native

Package `com.lib.ads.gma.ads.helper.adnative` (+ `.preload`, `.api`, `.params`).

### 5.1 XML — một dòng

```kotlin
AdsManager.loadNative(
    activity = this,
    container = binding.flNative,
    adUnitId = BuildConfig.ad_native,
    layoutId = R.layout.layout_native_common,   // root là NativeAdView với các id chuẩn
    placementId = AdPlacement.FEED,
    callback = object : NativeAdListener {
        override fun onLoaded(ad: ApNativeAd) {}
        override fun onFailed(error: ApAdError) {}
    },
)
```

Layout XML: root `NativeAdView`, các view `ad_headline`, `ad_body`, `ad_call_to_action`,
`ad_app_icon`, `ad_advertiser`, `ad_price`, `ad_stars`, `ad_media` (id nào không có thì bỏ qua).

### 5.2 Preload theo tag

```kotlin
NativeAds.preload(
    NativeAdSpec(
        tag = "feed_native",
        adUnitIds = listOf(BuildConfig.ad_native_high, BuildConfig.ad_native_low),
        bufferSize = 2,
        requestOptions = NativeAdRequestOptions().setPlacementId(AdPlacement.FEED),
    ),
)
val ad: ApNativeAd? = NativeAds.get("feed_native")   // tự lấy nếu không dùng holder
```

`NativeAds.stateFlow(tag)` (`PreloadBufferState`), `available(tag)`, `readyAdUnitId(tag)`,
`stopPreload(tag)`, `forcePreload(spec)`. Buffer có TTL (`NativeAdSpec.ttlMs`, mặc định 4 giờ).
Lấy ad khỏi buffer **không** tự nạp lại; gọi `preload` lại khi cần.

### 5.3 XML — holder

```kotlin
private val nativeHolder: NativeAdHolderConfig by createNativeAdHolder(
    tag = "home_native",
    options = NativeAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(BuildConfig.ad_native),
        timeoutPerIdMs = 10_000L,
        lifecycleOwner = this,
        autoRequestOnStart = true,
        requestOptions = NativeAdRequestOptions().setPlacementId(AdPlacement.HOME_NATIVE),
    ),
)

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        nativeHolder.state.collect { state ->
            when (state) {
                is NativeAdDisplayState.Success -> {
                    val adView = layoutInflater.inflate(R.layout.layout_native_common, binding.flNative, false) as NativeAdView
                    state.ad.nativeAd?.let { NativeAdManager.bindNativeAdView(it, adView) }
                    binding.flNative.removeAllViews()
                    binding.flNative.addView(adView)
                }
                NativeAdDisplayState.Idle, NativeAdDisplayState.Loading -> showShimmer()
                is NativeAdDisplayState.Error, is NativeAdDisplayState.Cancelled -> binding.flNative.isVisible = false
            }
        }
    }
}
```

Click/impression/paid đã được holder bind và chuyển tới `nativeHolder.registerAdCallback(...)`.

### 5.4 Compose

```kotlin
@Composable
fun HomeNative() {
    NativeAdWithPreload(
        tag = "home_native",
        options = NativeAdPreloadHolderOptions(
            fallbackAdUnitIds = listOf(BuildConfig.ad_native),
            autoRequestOnStart = true,
        ),
        loading = { ShimmerNativeCard() },
        nativeView = { ad -> MyNativeLayout(ad) },
    )
}
```

Hoặc tách holder: `val holder = rememberNativeAdPreload(tag, options, adCallback)` rồi
`NativeAdCard(holder, loading = ..., error = ..., nativeView = ...)`.

Khối UI dựng layout (package `adnative.api`): bọc nội dung trong `NativeAdView(nativeAd = ad)`, các
view con đọc ad từ `LocalApNativeAd`/`LocalNativeAdView` nên không cần truyền ad xuống:

```kotlin
NativeAdView(nativeAd = ad) {
    NativeAdHeadlineView { Text(it) }
    NativeAdBodyView { Text(it) }
    NativeAdIconView { drawable -> AndroidView(factory = { ImageView(it).apply { setImageDrawable(drawable) } }) }
    NativeAdMediaView(Modifier.fillMaxWidth().height(180.dp))
    NativeAdCallToActionView { cta -> NativeAdButton(text = cta) }
}
```

Ngoài ra có `NativeAdAdvertiserView`, `NativeAdPriceView`, `NativeAdStarRatingView`, `AdBadge`,
`NativeAdAttribution` và các placeholder `Shimmer*` (màu qua `LocalNativeLoadingColors`).

### 5.5 Native banner (XML)

`NativeBannerHelper` hiển thị native vào một `FrameLayout` cỡ banner, chạy trên cùng holder/buffer:

```kotlin
val nativeBanner = NativeBannerHelper(
    context = this,
    lifecycleOwner = this,
    config = NativeBannerConfig(
        idAds = BuildConfig.ad_native_banner,
        canShowAds = true,
        canReloadAds = true,
        layoutId = R.layout.layout_native_banner,
        timeReloadMs = 30_000L,              // 0 = không tự reload theo thời gian
    ).configureRequest { setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.LANDSCAPE) },
)
    .setContainer(binding.flNativeBanner)
    .setShimmerLayoutView(binding.shimmerNativeBanner)

nativeBanner.requestAds()
```

`getAdState()` trả `StateFlow<NativeAdDisplayState>`, `getPreloadState()` trả
`StateFlow<PreloadBufferState>`, `preload(buffer)` nạp buffer cho tag của helper
(`NativeBannerConfig.tag`, mặc định sinh từ ids). Reload theo thời gian chỉ thay ad đã có impression.

---

## 6. Quảng cáo trong LazyColumn/LazyRow

Đừng tạo holder trong từng item (`NativeAdWithPreload`/`BannerAd` bên trong `items {}`): item cuộn
ra là holder chết, mỗi lần xuất hiện lại load từ đầu. Dùng holder thuộc về list:

```kotlin
@Composable
fun Feed(rows: List<Row>) {
    val nativeHolders = rememberNativeAdListHolders(
        tag = "feed_native",
        options = NativeAdPreloadHolderOptions(fallbackAdUnitIds = listOf(BuildConfig.ad_native)),
        prefetchCount = 1,
    )
    val bannerHolders = rememberBannerAdListHolders(
        tag = "feed_banner",
        options = BannerAdPreloadHolderOptions(fallbackAdUnitIds = listOf(BuildConfig.ad_banner)),
    )
    LazyColumn {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is Row.Native -> NativeAdListItem(nativeHolders, key = row.key, nativeView = { MyNativeLayout(it) })
                is Row.Banner -> BannerAdListItem(bannerHolders, key = row.key)
                is Row.Content -> ContentRow(row)
            }
        }
    }
}
```

Hành vi (giống nhau cho native và banner):

| Tình huống | Kết quả |
|---|---|
| Ô cuộn ra khỏi màn hình | Không load gì; holder và ad được giữ |
| Ô cuộn lại, ad đã có impression, buffer có sẵn | Đổi ngay sang ad trong buffer → impression mới |
| Ô cuộn lại, ad đã có impression, buffer trống | Giữ ad cũ, sau `requestDelayMs` (300 ms) trên màn hình thì load ad mới |
| Ô cuộn lại, ad chưa có impression | Giữ nguyên ad, lần hiển thị này được tính |
| Vuốt nhanh qua ô (< `requestDelayMs`) | Không gửi request |
| Load bắt đầu rồi ô cuộn đi | Load chạy tiếp, ad vào đúng ô đó |
| Vượt `maxHolders` (mặc định 10) | Bỏ holder cũ nhất không còn trên màn hình (native trả ad chưa impression về buffer; banner destroy) |

`prefetchCount` giữ sẵn N ad trong buffer của tag khi list đang được xem.

---

## 7. Interstitial

Package `com.lib.ads.gma.ads.helper.interstitial`, listener `InterstitialAdListener`.

### 7.1 Một dòng

```kotlin
AdsManager.loadAndShowInterstitial(
    activity = this,
    adUnitId = BuildConfig.ad_interstitial,
    enabled = true,                          // false = gọi onComplete() ngay
    placementId = AdPlacement.AFTER_LEVEL,
) {
    // Gọi đúng 1 lần: khi disable, khi load/timeout thất bại, hoặc NGAY TRƯỚC khi ad hiện
    // (điều hướng "phía dưới" lớp phủ, user đóng ad là thấy màn mới).
    startActivity(Intent(this, NextActivity::class.java))
}
```

### 7.2 Helper

```kotlin
private val inter by interstitialAd(
    adUnitId = BuildConfig.ad_interstitial,
    lifecycleOwner = this,                   // bắt buộc; helper tự huỷ theo lifecycle
    autoLoad = true,
    loadTimeoutMs = 30_000L,                 // timeout mỗi id
    preloadTag = "after_onboarding",         // optional, mục 7.3
)

var continued = false
inter.waitLoadAndShow(
    activity = this,
    timeoutMs = 15_000L,
    callback = object : InterstitialAdListener {
        override fun onNextAction() {
            if (continued) return
            continued = true
            goNext()
        }
    },
)
```

`interstitialAdWaterfall(adUnitIds = listOf(...), lifecycleOwner = this)` cho nhiều id.

**Thời điểm `onNextAction()`:** helper gọi nó ngay trước `show(activity)`, và gọi lại khi ad đóng hoặc
lỗi show — có thể nhiều lần, nên chống lặp như trên. Cần xử lý **sau khi** user đóng ad thì dùng
`onDismissed(ad)` (và `onFailedToShow` cho nhánh lỗi).

Config thủ công (interval, reload, placement):

```kotlin
private val inter by lazy {
    val config = InterstitialAdConfig.waterfall(
        adUnitIds = listOf(idHigh, idLow),
        intervalBetweenAds = 15L,            // giây, theo từng helper
        autoReloadAfterShow = true,
        loadTimeoutMs = 30_000L,
        canShowAds = true,
        canReloadAds = true,
    ).setPreloadTag("after_onboarding")
    InterstitialAdHelper(this, this, config)
}
inter.requestAds(InterstitialAdParam.Request)
```

Có `isAdLoaded()`, `getLoadedAd()`, `cancel()`, `register/unregister(All)AdListener(s)`.
`forceShow(activity, interstitialAd, callback)` nhận raw `InterstitialAd` của SDK — ưu tiên
`waitLoadAndShow`.

### 7.3 Preload giữa các màn hình

```kotlin
// Màn trước
private val preloadInter by interstitialAd(BuildConfig.ad_interstitial, this, autoLoad = false, preloadTag = "after_onboarding")
preloadInter.preload()                       // vào cache app-wide, không gắn lifecycle màn này

// Màn sau
private val inter by interstitialAd(BuildConfig.ad_interstitial, this, autoLoad = false, preloadTag = "after_onboarding")
inter.waitLoadAndShow(activity = this, callback = listener)
```

Tag trống thì dùng ad unit id cuối trong list. Cache trống lúc show thì helper tự load, không join
preload đang chạy.

### 7.4 Manager theo tag & splash

```kotlin
AdsManager.loadInterstitialAd(tag = "home_inter", ids = listOf(idHigh, idLow), callback = listener)
AdsManager.showInterstitialAd(activity = this, tag = "home_inter", callback = object : InterstitialAdListener {
    override fun onDismissed(ad: ApInterstitialAd) = goNext()
    override fun onFailedToShow(error: ApAdError) = goNext()
    override fun onNextAction() = goNext()   // cache rỗng
})

// Splash interstitial
AdsManager.loadSplashInterstitialAds(
    id = BuildConfig.ad_interstitial_splash, timeOut = 30_000L, timeDelay = 3_000L,
    adListener = object : InterstitialAdListener {
        override fun onReady() = AdsManager.onShowSplash(this@SplashActivity, showListener)
        override fun onFailed(error: ApAdError) = goNext()
        override fun onNextAction() = goNext()   // timeout
    },
)
```

Waterfall splash: `loadSplashListAds(listId, timeOut, timeDelay, adCallback)`. Ad về sau timeout vẫn
có thể gọi `onReady` — kiểm tra đã điều hướng chưa trước khi show.

---

## 8. Rewarded & Rewarded-Interstitial

Package `com.lib.ads.gma.ads.helper.reward`, listener `RewardAdListener`.

**Chỉ cấp thưởng trong `onRewarded(ad, item)`.** `onDismissed`/`onNotReady`/`onFailedToShow` dùng để
điều hướng, không chứng minh user đã xem đủ.

```kotlin
AdsManager.loadAndShowRewarded(
    activity = this,
    adUnitId = BuildConfig.ad_rewarded,
    placementId = AdPlacement.DOUBLE_COINS,
    callback = object : RewardAdListener {
        override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) {
            grantReward(item.rewardItem.amount, item.rewardItem.type)
        }
        override fun onDismissed(ad: ApRewardAd) = goNext()
        override fun onFailedToShow(error: ApAdError) = goNext()
        override fun onNotReady() = goNext()
    },
)
```

Helper:

```kotlin
private val reward by rewardedAd(
    adUnitId = BuildConfig.ad_rewarded,
    lifecycleOwner = this,                   // optional
    autoLoad = false,
)

fun onWatchAdClicked() {
    reward.waitLoadAndShow(activity = this, callback = object : RewardAdListener {
        override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) = grantReward()
        override fun onNotReady() = toast("Chưa có quảng cáo")
    })
}
```

Preload rồi show riêng: `reward.load(activity)` … `reward.forceShow(activity, callback)`.
Waterfall: `rewardedAdWaterfall(...)`. Rewarded-interstitial: `rewardedInterstitialAd(...)` /
`rewardedInterstitialAdWaterfall(...)` → `RewardedInterstitialAdHelper`, cùng listener.

Theo tag qua manager:

```kotlin
AdsManager.loadRewardAd(tag = "daily_reward", ids = listOf(idHigh, idLow), callback = listener)
AdsManager.showRewardAd(activity = this, tag = "daily_reward", callback = rewardListener)
```

---

## 9. App Open (resume & splash)

| | Resume | Splash |
|---|---|---|
| Khi nào | Tự hiện mỗi lần app quay lại từ background | Chỉ khi bạn gọi, thường ở `SplashActivity` |
| Cấu hình | `AdSdkConfig.idAdResume`/`listIdAdResume` | `AdsManager.loadSplashAppOpenAd(s)` hoặc `appOpenAd(...)` |
| Class | `helper.appopen.AppOpenManager` | `manager.AppOpenAdManager` / `AppOpenAdHelper` |

### 9.1 Resume

Chỉ cần `AdSdkConfig` (mục 1.2). Loại trừ màn không muốn hiện:

```kotlin
AdsManager.excludeAppOpen(SplashActivity::class.java, VideoActivity::class.java)
AdsManager.includeAppOpen(VideoActivity::class.java)

// Không hiện cho user mới (preload vẫn chạy nền):
AdsManager.setAppOpenEligibilityGate { prefs.getInt("session_count", 0) >= 3 }
```

### 9.2 Splash qua `AdsManager`

```kotlin
AdsManager.loadSplashAppOpenAds(
    ids = listOf(BuildConfig.ad_open_splash_high, BuildConfig.ad_open_splash_low),
    timeOut = 8_000L,              // quá thời gian thì bỏ qua
    timeDelay = 1_500L,            // thời gian loading tối thiểu, tránh nháy màn hình
    placementId = AdPlacement.SPLASH,
    listener = object : AppOpenAdListener {
        override fun onReady() = AdsManager.showSplashAppOpen(this@SplashActivity, showListener)
        override fun onFailed(error: ApAdError) = openMain()
        override fun onNextAction() = openMain()   // timeout / hết id
    },
)

private val showListener = object : AppOpenAdListener {
    override fun onDismissed() = openMain()
    override fun onFailedToShow(error: ApAdError) = openMain()
    override fun onNextAction() = openMain()       // không có ad sẵn để show
}
```

Một id: `loadSplashAppOpenAd(id, ...)`. Tham số `tag` mặc định dùng chung giữa load và show. Nếu đã
khai báo `AdSdkConfig.splashAdId`, ad thường đã được preload từ `Application.onCreate()`.

### 9.3 `appOpenAd` + `AppOpenAdHelper`

```kotlin
private val appOpen by appOpenAd(adUnitId = BuildConfig.ad_app_open, autoPreload = true)
// waterfall: appOpenAdWaterfall(adUnitIds = listOf(...))

appOpen.waitLoadAndShow(
    activity = this,
    callback = object : AppOpenAdListener {
        override fun onDismissed() = openMain()
        override fun onFailed(error: ApAdError) = openMain()
        override fun onFailedToShow(error: ApAdError) = openMain()
    },
)
```

⚠️ Trong `AppOpenAdHelper.waitLoadAndShow`, `onNextAction()` được gọi **ngay đầu hàm**, trước khi
load/show. Không điều hướng trong `onNextAction()` ở flow này; dùng `onDismissed`, `onFailed`,
`onFailedToShow`.

---

## 10. Picture-in-picture

Cần GMA Next-Gen SDK 1.4.0+. `PictureInPictureAdManager` giữ ad ở cấp app để ad với scope
`APPLICATION` còn hiện khi user chuyển Activity.

```kotlin
PictureInPictureAdManager.load(
    adUnitId = BuildConfig.ad_pip,
    onLoaded = { /* bật nút show */ },
    onFailedToLoad = { error -> },
)

PictureInPictureAdManager.show(
    activity = this,
    position = PictureInPictureAdPosition.BOTTOM_RIGHT,
    presentationScope = PictureInPictureAdPresentationScope.APPLICATION,
)   // trả false nếu chưa có ad
```

`hide()` ẩn tạm; `destroy()` khi không dùng nữa; `isLoaded`, `presentationScope()`.
Callback đăng ký từ Activity qua `setAdEventCallback(...)` phải gỡ (`null`) trong `onDestroy()` vì ad
có thể sống lâu hơn Activity. Ad scope `SCREEN` phải destroy khi màn host bị gỡ.

---

## 11. Billing (Google Play)

`AppPurchase.getInstance()` (package `com.lib.ads.gma.ads.billing`) bọc Play Billing: kết nối, truy
vấn sản phẩm, mua/đăng ký, xác nhận và lưu quyền sở hữu. Khi user có quyền `REMOVE_ADS`, toàn bộ
request/show quảng cáo trong `gma-lib` tự bị chặn.

### 11.1 Chuẩn bị trên Play Console

1. Tạo In-app product hoặc Subscription; Product ID phải trùng tuyệt đối với code.
2. Subscription cần base plan/offer đang active.
3. Thêm license tester; test qua internal/closed testing track.

### 11.2 Khởi tạo trong `Application`

```kotlin
class MyApplication : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        AppPurchase.getInstance()
            .setLogProductDetail(BuildConfig.DEBUG)
            .initBilling(
                this,
                mutableListOf(
                    PurchaseItem.removeAdsOnly("remove_ads", AppPurchase.TYPE_IAP.PURCHASE),
                    PurchaseItem.premiumFeaturesOnly("pro_features", AppPurchase.TYPE_IAP.PURCHASE),
                    PurchaseItem.bundle("premium_monthly", AppPurchase.TYPE_IAP.SUBSCRIPTION, trialId = "free-trial"),
                    PurchaseItem("coins_100", AppPurchase.TYPE_IAP.PURCHASE, consume = true),
                ),
            )
    }
}
```

`initBilling` chỉ chạy lần đầu (gọi lại bị bỏ qua).

| `PurchaseItem` | Quyền (`Entitlement`) |
|---|---|
| `PurchaseItem(id, type)` / `bundle(...)` | `REMOVE_ADS` + `PREMIUM_FEATURES` |
| `removeAdsOnly(...)` | `REMOVE_ADS` |
| `premiumFeaturesOnly(...)` | `PREMIUM_FEATURES` (quảng cáo vẫn hiện) |

`consume = true` cho hàng tiêu hao (coin, lượt chơi): được consume sau khi mua để mua lại được.
`type`: `AppPurchase.TYPE_IAP.PURCHASE` (1) hoặc `SUBSCRIPTION` (2).

### 11.3 Chờ billing sẵn sàng ở splash

```kotlin
AppPurchase.getInstance().getBillingAndAwaitInitAds(
    billingTimeout = 5_000,
    initAdsTimeout = 10_000L,
) { resultCode ->
    // Main thread. resultCode == BillingResponseCode.OK: đã biết trạng thái mua VÀ SDK ads sẵn sàng.
    if (AppPurchase.getInstance().isPurchased()) openMain() else loadSplashAds()
}
```

Chỉ cần billing: `setBillingListener(timeoutMs) { code -> }` (mặc định 5 s). Listener luôn được gọi
đúng một lần — thành công, lỗi hoặc hết thời gian.

### 11.4 Mua & đăng ký

```kotlin
AppPurchase.getInstance().purchase(activity, "remove_ads") { event ->
    when (event) {
        is PurchaseEvent.Success -> showThanks(event.productId)
        is PurchaseEvent.Pending -> showPending()          // thanh toán chờ xử lý, chưa cấp quyền
        PurchaseEvent.Cancelled -> Unit
        is PurchaseEvent.Error -> showError(event.message)  // code 6 = billing chưa init
    }
}

AppPurchase.getInstance().subscribe(activity, "premium_monthly") { event -> /* như trên */ }

// Đổi gói subscription
val token = AppPurchase.getInstance().getSubscriptionPurchaseToken("premium_monthly")
if (token != null) {
    AppPurchase.getInstance().upgradeSubscription(activity, "premium_yearly", token, replacementMode = 0) { event -> }
}
```

`replacementMode` là hằng số `ReplacementMode` của Play Billing (0 = mặc định của Play).
Hàng tiêu hao có thể consume thủ công: `consumePurchase(productId)`.

API cũ vẫn dùng được: `setPurchaseListener(PurchaseListener)` + `purchase(activity, productId)`
(`onProductPurchased`, `displayErrorMessage`, `onUserCancelBilling`).

### 11.5 Kiểm tra quyền sở hữu

```kotlin
val billing = AppPurchase.getInstance()
billing.isPurchased()                                   // có REMOVE_ADS → ads bị chặn
billing.hasEntitlement(Entitlement.PREMIUM_FEATURES)
billing.getOwnedEntitlements()

// Reactive (null nếu chưa initBilling)
billing.isPurchasedFlow()?.collect { removed -> updateAdsUi(removed) }
billing.ownedEntitlementsFlow()?.collect { owned -> updatePremiumUi(owned) }
billing.billingState?.collect { state -> }              // Disconnected/Connecting/Connected/Error

billing.updatePurchaseStatus()                           // truy vấn lại (ví dụ onResume)
billing.setUpdatePurchaseListener { /* trạng thái mua vừa được cập nhật */ }
billing.getOwnerIdInApp() / billing.getOwnerIdSubs()     // List<PurchaseResult>
```

Luôn kiểm tra lại quyền sau khi app khởi động, không chỉ dựa vào callback mua thành công.
Tắt quảng cáo theo trạng thái mua: truyền `enabled = !isPurchased()` / `canShowAds` vào các API
quảng cáo, và ẩn UI quảng cáo khi `isPurchasedFlow()` đổi.

### 11.6 Giá & thông tin sản phẩm

```kotlin
billing.getProductInfoList()     // List<BillingProductInfo>: price, currency, billingPeriod, trial, intro, promo...
billing.getPrice("remove_ads")                        // in-app, đã format
billing.getPriceSub("premium_monthly")                 // subscription
billing.getPeriod("premium_monthly"); billing.getTrialPeriod("premium_monthly")
billing.getIntroductorySubPrice("premium_monthly", offerId = null)
billing.getPriceWithCurrency(productId, typeIAP, sale = 0.5)
billing.getCurrency(productId, typeIAP); billing.getPriceWithoutCurrency(productId, typeIAP)
billing.getPricePricingPhaseList(productId)            // PricingPhase gốc của Play Billing
```

`setDiscount`/`getDiscount` chỉ lưu một hệ số cho app tự dùng khi hiển thị giá.

### 11.7 Build develop

Khi `AdSdkConfig` ở môi trường develop (`isVariantDev = true`):

- `purchase`/`subscribe` mở bottom sheet giả lập thay vì flow Play thật.
- SKU test `android.test.purchased` (`AppPurchase.PRODUCT_ID_TEST`) tự được thêm vào danh sách truy vấn.

Build production luôn dùng flow Play thật; test mua thật bằng license tester.

---

## 12. Configurable Init — giảm TTFA cho splash

Mặc định mọi adapter mediation phải init xong trước khi bất kỳ ad nào được load. Có thể chỉ init
adapter cần cho app-open trước:

```kotlin
override fun createAdSdkConfig(): AdSdkConfig = AdSdkConfig(this).apply {
    appAdId = "..."
    adapterInitializationConfig = AdapterInitializationConfig.Builder()
        .setAllowedAdFormats(setOf(AdFormat.APP_OPEN_AD))
        .build()
    skipUninitializedAdaptersForSplash = true
    splashAdId = BuildConfig.ad_app_open_splash
}
```

⚠️ Adapter của định dạng khác có thể chưa init khi splash chạy. Đo TTFA bằng A/B trước khi bật cho
production.

---

## 13. Debug, log & test ID

```kotlin
AdsManager.openDebugMenu(activity, adUnitId)   // debug menu của GMA SDK
```

- `AdSdkConfig.showMessageForTester = true`: overlay (loại ad, state, tag, buffer, số request, thời
  gian load) trên banner/native. Chỉ bật ở debug. Khi load lỗi, overlay có dòng đỏ `Err:` với
  nguyên nhân của **từng** ad unit trong waterfall, ví dụ
  `/6300978111: NO_FILL(3): No fill.` / `/1033173712: timeout 10000ms`; refresh lỗi (ad cũ vẫn
  hiện) có tiền tố `refresh failed:`. Ở chế độ này container XML không bị ẩn khi lỗi, để còn thấy
  overlay. Cùng nội dung đọc được qua `holder.lastError` (`StateFlow<String?>`) và
  `ApAdError.causes` / `ApAdError.shortDescription()` trong `onFailed`.
- Logcat theo định dạng: tag `GMA_BANNER`, `GMA_NATIVE`, `GMA_INTERSTITIAL`... Mỗi dòng có
  `adKey=<tag> event=<EVENT>`, ví dụ `PRELOAD_START`, `FILL`, `POLL`, `LATE_AD`, `REFRESH_SKIPPED`,
  `LIST_EVICT`. Billing log với tag `GMA_Purchase`.

Test ID chính thức của Google (chỉ dùng cho develop):

```text
App ID:        ca-app-pub-3940256099942544~3347511713
Banner:        ca-app-pub-3940256099942544/6300978111
Interstitial:  ca-app-pub-3940256099942544/1033173712
Rewarded:      ca-app-pub-3940256099942544/5224354917
App Open:      ca-app-pub-3940256099942544/9257395921
Native:        ca-app-pub-3940256099942544/2247696110
PiP:           ca-app-pub-3940256099942544/9657123429
```

---

## 14. Checklist tích hợp

**Quảng cáo**

- [ ] `appAdId` là App ID (dấu `~`); dùng test ID ở develop, ID thật ở release.
- [ ] Đã chạy UMP (`requestUMP()`) và kiểm tra `canRequestAds` trước khi hiện quảng cáo.
- [ ] Mỗi vị trí hiển thị có tag riêng, ổn định; show đúng tag đã preload.
- [ ] Interstitial/rewarded/app-open luôn điều hướng được khi không có mạng, timeout, không có ad
      (`onNextAction`, `onNotReady`, `onFailed`, `onFailedToShow`).
- [ ] Không điều hướng trong `AppOpenAdHelper` → `onNextAction()`; chống lặp `onNextAction()` của interstitial.
- [ ] Rewarded chỉ cấp thưởng trong `onRewarded`.
- [ ] Đã `excludeAppOpen(...)` cho splash và các màn full-screen.
- [ ] Quảng cáo trong list dùng `NativeAdListItem`/`BannerAdListItem`, không tạo holder trong item.
- [ ] `placementId` là hằng số tập trung.

**Billing**

- [ ] Product ID trùng Play Console; subscription có base plan/offer active.
- [ ] `initBilling` gọi một lần trong `Application.onCreate()`.
- [ ] Splash chờ `getBillingAndAwaitInitAds` (hoặc `setBillingListener`) trước khi quyết định load ads.
- [ ] Xử lý đủ `PurchaseEvent`: `Success`, `Pending`, `Cancelled`, `Error`.
- [ ] Kiểm tra quyền sau khi khởi động lại app; test cancel, pending, product sai, mất mạng, restore,
      subscription hết hạn.
