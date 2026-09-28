# adLib — Ads Integration Guide

Unified guide for integrating ads from the `adlib` module. Each ad type has its own
page with copy-paste examples.

For the GMA Next-Gen module (`gma-lib`) — initialization, every ad format (XML and Compose),
lists, picture-in-picture and Billing — see the single guide [gma-lib.md](gma-lib.md).

| Ad type | Doc | Entry point |
|---|---|---|
| Native | [native-ads.md](native-ads.md) | `NativeAdController`, `NativeAdCard` |
| Interstitial | [interstitial-ads.md](interstitial-ads.md) | `interstitialAd { }`, `InterstitialAdManager` |
| Banner | [banner-ads.md](banner-ads.md) | `bannerAd { }`, `BannerAd(adUnitId)` |
| App Open | [app-open-ads.md](app-open-ads.md) | `AppOpenManager.init` / `loadAndShowSplash` |
| Rewarded | [rewarded-ads.md](rewarded-ads.md) | `rewardedInterstitialAd { }`, `RewardAdManager` |
| Native Banner | [native-banner-ads.md](native-banner-ads.md) | `NativeBannerAdManager`, `AdmobNativeBannerManager` |

## Common concepts

### Waterfall
Waterfall lists are tried **sequentially** in priority order. The library requests
`id[0]`; if that id fails or times out it moves to `id[1]`, and so on. The first id
that returns an ad wins and the waterfall stops.

Current timeout behavior:
- Native uses per-id timeouts from `NativeAdConfig.listTimeout` or the default native timeout.
- Banner waterfall has a per-id timeout of 10 seconds by default.
- Interstitial, rewarded, rewarded-interstitial, splash, and app-open helper waterfalls
load each id in order and stop on the first loaded ad.

Late callbacks from timed-out native/banner requests are ignored so an older ad unit
cannot overwrite the winning ad unit.

### Gating
Every load path is automatically guarded by `AdsManger`:
- skips when the user has purchased (`AppPurchase.isPurchased()`),
- skips when UMP consent is not granted (`AdsConsentManager`),
- raises a notification / throws in production when a **test ad id** is used.

You never need to repeat these checks in your screen code.

### Load counting & logging — `AdLoadStats`
Every load path reports to a single counter so you can see, at a glance, how many ads
were requested / loaded / failed / served / impressed — per ad type and per tag.

Enable logging once (e.g. in `MyApplication`):

```kotlin
AppLogger.isEnabled = true   // turns on all adlib logs, including AdLoadStats
```

Then watch logcat:

```
D/AdLoadStats: [NATIVE/home] loaded id=ca-app-pub-… -> requested=4 loaded=3 failed=1 served=2 impression=2
D/AdLoadStats: [INTERSTITIAL] loaded -> requested=2 loaded=2 failed=0 served=0 impression=1
```

Read counters programmatically (e.g. for a debug overlay):

```kotlin
val native = AdLoadStats.get(AdType.NATIVE)            // type-level totals
val home   = AdLoadStats.get(AdType.NATIVE, "home")    // one placement
val all    = AdLoadStats.snapshot()                    // everything
```

Counters:
- `requested` — a load was started
- `loaded` — AdMob returned an ad
- `failed` — a load attempt failed (waterfall timeouts count too)
- `served` — a preloaded ad was popped from the buffer and handed to the UI
- `impression` — the ad recorded an on-screen impression

### Architecture (native)
All native loading funnels through one core, `NativeAdLoader`, used by:
- `NativeAdController` (request/state/lifecycle),
- `NativeAdManager` (GMA load + view binding),
- Compose UI (`NativeAdCard` / `NativeAdView`).

This guarantees identical behaviour (paid-event logging, impression/click tracking,
counting) whether an ad is preloaded or loaded on demand. See
[native-ads.md](native-ads.md).

Native preload fills the buffer silently: UI `onNativeAdLoaded` callbacks are fired
when an ad is handed to a display holder/helper, not merely when a warm buffer slot is
filled.
```
NativeAdController ─► NativeAdManager ─► NativeAdLoader ─► AdMob SDK
Compose holder ─┘            │
                            └─► AdsManger (gating, paid, impression) + AdLoadStats
```
