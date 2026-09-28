# Banner Ads

Banners now have a **one-liner** for the common case (mirrors the native `nativeAd { }`),
plus the full `BannerAdHelper` when you need control, plus a Compose API.

Loads are gated by `AdsManger` and counted in
[`AdLoadStats`](README.md#load-counting--logging--adloadstats) under `AdType.BANNER`.

---

## 1. XML one-liner (recommended)

Builds the config, wires the container (+ shimmer), and auto-requests — nothing else needed:

```kotlin
class HomeActivity : AppCompatActivity() {

    private val bannerHelper by bannerAd(
        adUnitId = BuildConfig.ad_banner,
        container = { binding.frAds },
    )

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(binding.root)
        bannerHelper        // touch once to initialize + auto-request
    }
}
```

Variants:

```kotlin
// collapsible
private val bannerHelper by bannerAd(
    adUnitId = BuildConfig.ad_banner_collapse,
    container = { binding.frAds },
    collapsibleGravity = "bottom",          // or "top"
)

// adaptive inline (taller)
private val bannerHelper by bannerAd(
    adUnitId = BuildConfig.ad_banner,
    container = { binding.frAds },
    useInline = true,
    maxHeightDp = 100,
)

// waterfall (multiple ad units, tried in order)
private val bannerHelper by bannerAdWaterfall(
    adUnitIds = listOf(BuildConfig.ad_banner_high, BuildConfig.ad_banner_low),
    container = { binding.frAds },
)

// from a Fragment
private val bannerHelper by bannerAd(
    activity = requireActivity(),
    adUnitId = BuildConfig.ad_banner,
    container = { binding.frAds },
)
```

Waterfall tries ids sequentially and stops at the first loaded banner. Each banner id
has a 10-second timeout by default; if an id times out, its view is destroyed and any
late callback from that id is ignored.

Optional params on every variant: `canShowAds`, `canReloadAds`, `autoRequest`, `adCallback`.

Manual control afterwards:

```kotlin
bannerHelper.requestAds(BannerAdParam.Request)   // reload
bannerHelper.flagUserEnableReload = false        // pause auto-reload on resume
bannerHelper.getBannerState()                    // StateFlow<AdBannerState>
bannerHelper.registerAdListener(object : AdsCallback() { /* … */ })
```

---

## 2. Config factories (when you build the helper yourself)

```kotlin
BannerAdConfig.simple(BuildConfig.ad_banner)
BannerAdConfig.simple(BuildConfig.ad_banner, useInline = true, maxHeightDp = 100)
BannerAdConfig.collapsible(BuildConfig.ad_banner_collapse, gravity = "bottom")
BannerAdConfig.waterfall(listOf(idHigh, idLow))

val helper = BannerAdHelper(this, this, BannerAdConfig.simple(BuildConfig.ad_banner))
    .setBannerContentView(binding.frAds)
helper.requestAds(BannerAdParam.Request)
```

(The old fluent form — `BannerAdConfig(id, true, true).setUsingInlineBanner(…).setMaxHeight(…)
.setCollapsibleGravity(…)` — still works.)

---

## 3. Compose

```kotlin
BannerAd(adUnitId = BuildConfig.ad_banner)

BannerAd(
    adUnitId = BuildConfig.ad_banner_collapse,
    collapsibleGravity = "bottom",
)

BannerAd(adUnitIds = listOf(idHigh, idLow), useInline = true, maxHeightDp = 100)

// full control
val holder = rememberBannerAd(BannerAdConfig.simple(BuildConfig.ad_banner))
BannerAdCard(holder, loading = { DefaultBannerAdLoading() })
holder.reload(); holder.cancel()
```

---

## 4. Manager (low-level)

```kotlin
BannerAdManager.loadBanner(
    activity, id = BuildConfig.ad_banner,
    adContainer = binding.frAds,
    containerShimmer = binding.shimmer,
    useInlineAdaptive = true,
    callback = object : AdsCallback() {
        override fun onAdLoaded() {}
        override fun onAdFailedToLoad(adError: ApAdError?) {}
    }
)
// Also: loadBannerList(...), loadCollapsibleBanner(activity, id, gravity, container, shimmer, callback)
```

`loadBannerList(...)` accepts an optional `timeoutPerIdMs` parameter. Public
`requestLoadBanner(...)` remains a fire-and-forget API; the internal waterfall path
owns the ad view so it can destroy timed-out attempts safely.

---

## 5. Tag-based preload holder (`createBannerAdHolder`)

Preload on one screen, consume on the next by tag. The holder needs no Activity/Context:
loading uses the application context and the holder hands out a ready `AdView`.

```kotlin
// Previous screen
BannerAds.preload(BannerAdSpec(tag = TAG_HOME_BANNER, adUnitIds = listOf(BuildConfig.ad_banner)))

// Displaying screen
private val bannerHolder by createBannerAdHolder(
    tag = TAG_HOME_BANNER,
    options = BannerAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(BuildConfig.ad_banner),   // cold load when the tag is not preloaded
        lifecycleOwner = this,
        autoRequestOnStart = true,
    ),
)
```

Collect `bannerHolder.state` and attach `BannerAdDisplayState.Loaded.adView` to your container,
or pass the holder to `BannerAdCard(holder = bannerHolder)` in Compose. Full example, behavior
table and internal structure:
[gma-lib.md, mục 4](gma-lib.md#4-banner).
