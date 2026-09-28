# AdsManager quick start

`AdsManager` combines configuration, request and display into one safe call. Call each placement once,
normally from `Activity.onCreate`. The returned helper is optional.

```kotlin
import com.lib.ads.application.ads.manager.AdsManager
```

## Banner

```kotlin
AdsManager.showBanner(
    activity = this,
    container = binding.flBanner,
    adUnitId = BuildConfig.ad_banner,
)
```

## Native XML

```kotlin
AdsManager.showNative(
    activity = this,
    tag = "home_native",
    container = binding.flNativeAds,
    shimmer = binding.shimmerNative,
    adUnitId = BuildConfig.ad_native,
    layoutId = R.layout.layout_native_common,
)
```

The default native preload buffer is `0`, which avoids loading an extra ad that may never be shown.
Use `preloadBufferSize = 1` only when the placement is displayed repeatedly.

## Interstitial

```kotlin
AdsManager.showInterstitial(
    activity = this,
    adUnitId = BuildConfig.ad_inter_normal,
) {
    startActivity(Intent(this, NextActivity::class.java))
}
```

The completion block runs after the ad is dismissed, or immediately after timeout/load/show failure.
It is never called before `InterstitialAd.show()`.

## App-open exclusions

```kotlin
AdsManager.excludeAppOpen(
    SplashActivity::class.java,
    LanguageActivity::class.java,
)
```

This excludes only those screens and does not globally disable app-open ads for the process.

## Compose native

The existing Compose function now requests the first ad automatically:

```kotlin
NativeAd(
    tag = "language_native",
    adUnitId = BuildConfig.ad_native,
) { ad ->
    NativeMediumCtaBottom(ad)
}
```

Do not call `NativeAds.preload()` or `holder.request()` in addition to this convenience component.
