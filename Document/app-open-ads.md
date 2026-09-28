# App Open Ads

`AppOpenManager` is a singleton handling two cases:

- **App-resume** — shown automatically when the app returns to foreground.
- **Splash** — shown once during the splash flow.

Loads are gated by `AdsManger` and counted in
[`AdLoadStats`](README.md#load-counting--logging--adloadstats) under `AdType.APP_OPEN`.

---

## App-resume (auto)

Initialize once in `Application.onCreate`:

```kotlin
AppOpenManager.getInstance().init(application, BuildConfig.ad_appopen_resume)
// or a waterfall:
AppOpenManager.getInstance().init(application, listOf(idHigh, idLow))
```

The resume waterfall tries ids sequentially and stops at the first loaded app-open ad.

The manager observes the process lifecycle and shows the resume ad automatically.
Control it:

```kotlin
val aom = AppOpenManager.getInstance()
aom.disableAppResume(); aom.enableAppResume()
aom.disableAppResumeWithActivity(PaymentActivity::class.java)   // never on this screen
aom.disableAdResumeByClickAction()                              // skip the next resume after an ad click
aom.isShowingAd()
```

> When showing your own full-screen ad (interstitial/rewarded), set
> `AdsManger.setFullScreenAdShowing(true)` so a resume ad doesn't appear on top.

---

## Splash app-open

### One call (recommended)

Sets the id(s), loads, shows as soon as it's ready, and calls `onNext` **exactly once**
(on close, fail, timeout, or purchase):

```kotlin
AppOpenManager.getInstance().loadAndShowSplash(
    activity = this,
    adUnitId = BuildConfig.ads_open_app,      // or adUnitIds = listOf(idHigh, idLow)
    timeDelayMs = 3_000,
    timeOutMs = 30_000,
    onNext = { startMain() },
)
```
Optional `adCallback` forwards impression/click/closed/fail events.

### Manual (low-level)

```kotlin
AppOpenManager.getInstance().setSplashAdId(BuildConfig.ads_open_app)   // or setSplashAdIdList(...)

AppOpenManager.getInstance().loadOpenAppAdSplash(
    activity, timeDelay = 3_000, timeOut = 30_000,
    object : AdsCallback() {
        override fun onAdSplashReady() {
            AppOpenManager.getInstance().showAppOpenSplash(activity, object : AdsCallback() {
                override fun onNextAction() { startMain() }
            })
        }
        override fun onNextAction() { startMain() }   // timeout / fail
    }
)
// waterfall: loadOpenAppAdSplashList(activity, timeDelay, timeOut, callback)  (after setSplashAdIdList)
```

Splash waterfall follows the same rule: first loaded id wins, fail/timeout moves to
the next id until the global splash timeout fires.

`onCheckShowAppOpenSplashWhenFail(activity, callback, timeDelay)` shows a late-arriving
splash ad on resume if it wasn't ready in time.
