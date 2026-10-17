# gma-lib — tracking, IAP and consent integration

What an app has to configure to get correct revenue data from `gma-lib`, and what changed for
apps upgrading from earlier versions.

## 1. Upgrade checklist

| # | Action | Why |
|---|--------|-----|
| 1 | Set `AdjustConfig.eventNamePurchase` to the **event token** (6 chars) from the Adjust dashboard | Default is now `""` (the old default `"purchase_item"` was not a valid token, so purchases never reached Adjust) |
| 2 | Decide `AdjustConfig.usePurchaseVerification` | `true` only if Purchase Verification is enabled on the Adjust account; IAP is sent once either way (it used to be sent twice) |
| 3 | Check Firebase DebugView for an automatic `ad_impression` with `ad_platform=AdMob` | If present, set `AdSdkConfig.logFirebaseAdImpression = false`, otherwise GA4 counts revenue twice |
| 4 | Consumable products: call `AppPurchase.getInstance().setRecoveredConsumableListener { grant(it.productId) }` before `initBilling` | Purchases completed while the app was dead (e.g. pending cash payments) are delivered there, then consumed |
| 5 | Consuming the published artifact (`com.lib:gmasdk`): add the exclude below to the app module | Mediation adapters depend on `play-services-ads`, which must not sit next to the Next-Gen SDK |
| 6 | Code with an exhaustive `when (adType)` on `AdType` | New value `REWARDED_INTERSTITIAL` |
| 7 | Tell the data team: `ad_impression` now carries revenue; mediated fills now have their real ad unit | Dashboards built on the old values will shift |

```kotlin
// app/build.gradle.kts
configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
```

## 2. Minimal configuration

```kotlin
class App : AdsMultiDexApplication() {

    // Consent-first: GMA starts after UMP on the first launch, at startup on later launches
    // (stored consent). Attribution SDKs start in onCreate regardless.
    override fun initializeAdsOnCreate() = false

    override fun createAdSdkConfig() = AdSdkConfig(
        this,
        if (BuildConfig.DEBUG) AdSdkConfig.ENVIRONMENT_DEVELOP else AdSdkConfig.ENVIRONMENT_PRODUCTION,
    ).apply {
        appAdId = getString(R.string.admob_app_id)
        adjustConfig = AdjustConfig(enableAdjust = true, adjustToken = "APP_TOKEN").apply {
            eventNamePurchase = "abc123"          // event token, not a name
            usePurchaseVerification = false
        }
        appsflyerConfig = AppsflyerConfig(enableAppsflyer = true, devKey = "DEV_KEY")
    }
}
```

Splash, after the UMP form:

```kotlin
consentManager.requestUMP()
if (consentManager.getCanRequestAd()) {
    AdsProvider.getInstance().initializeIfNeeded()     // no-op if already started
    AdsProvider.getInstance().awaitReady { ready -> if (ready) loadSplashAd() else goNext() }
} else goNext()
```

`AppPurchase.getBillingAndAwaitInitAds(...)` calls `initializeIfNeeded()` itself.

## 3. What is sent where

| Source | Firebase | Adjust | AppsFlyer | Meta |
|--------|----------|--------|-----------|------|
| Paid impression | `ad_impression` (GA4 params), `paid_ad_impression`, `paid_ad_impression_value`, revenue totals | `trackAdRevenue("admob_sdk")` with network, unit, placement; optional value event | `logAdRevenue` (AD_REVENUE in `events`) | `paid_ad_impression*` |
| Impression / click | `event_user_click_ads` | optional event tokens | `ad_impression` / `ad_click` when listed | `ad_impression`, `event_user_click_ads` |
| One-time IAP | optional `purchase` | purchase event token | `af_purchase` (`af_content_type=inapp`) | — |
| Subscription start | optional `purchase` | `trackPlayStoreSubscription` | `af_purchase` (`af_content_type=subs`) | — |
| Consent (UMP/TCF) | Consent Mode v2 | `google_dma` third-party sharing | TCF read by the SDK | — |

IAP rules:
- Revenue is reported once per purchase token, only when the purchase is `PURCHASED`, at the price
  of the offer actually bought. A free trial start reports nothing; renewals are server-side.
- `Purchase.quantity` is honoured.

## 4. Optional analytics (`AdSdkConfig`)

| Flag | Default | Events |
|------|---------|--------|
| `logTotalRevenueEachImpression` | `true` | `event_current_total_revenue_ad` after every impression (legacy, noisy) |
| `logAdHealthEvents` | `false` | `ad_load_time` (`load_time_ms`, `success`), `ad_load_failed` (`error_code`, `error_name`, `error_message`), `ad_show_failed` — per ad unit |
| `logFirebasePurchase` | `false` | Firebase `purchase` — keep off when GA4 is linked to Google Play (`in_app_purchase` is automatic) |
| `dailyAdsRevenue` | `null` | `Daily_Ads_Revenue` (and optionally `azura_paid_ad_impression`), same names/params as Azura's AzTaichi |
| `propagateConsent` | `true` | consent forwarding above |

Health events reach AppsFlyer only when `AdsTrackEvent.AD_LOAD_TIME` / `AD_LOAD_FAILED` /
`AD_SHOW_FAILED` are in `AppsflyerConfig.events`.

## 5. Behaviour worth knowing

- **Callbacks.** Load and ad-event listeners are called on the main thread. The Next-Gen SDK itself
  calls back on a background thread.
- **Interstitial interval.** `intervalBetweenAds` is a global cap: it counts from the last
  interstitial shown by any helper or by `InterstitialAdManager`, not just by the same screen.
- **Premium state.** `isPurchased()` survives restarts and offline launches. It only changes after a
  successful Play query, so a failed query never revokes a paying user.
- **Splash timeouts.** Each load gets exactly one of `onReady` / `onNextAction` from the timeout
  path; a fill arriving after the timeout stays cached for `onCheckShowSplashWhenFail` but is not
  reported again.
