# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AdsApplication ships an Android ads library built on the **Google Mobile Ads Next-Gen SDK**
(`com.google.android.libraries.ads.mobile.sdk`), with mediation (Meta, Mintegral, Pangle, Unity),
Google Play Billing, UMP consent, and attribution/analytics (Adjust, AppsFlyer, Firebase, Meta
App Events). Kotlin only.

| Module | Role |
|--------|------|
| `gma-lib` | **The library.** Next-Gen SDK. Published as `com.lib:gmasdk`. The sample app uses it. |
| `adlib` | Legacy library on `play-services-ads`. Not used by the app; kept for existing consumers. Do not add features here. |
| `app` | Sample app (flavors `appDev` / `appProd`), entry point `GmaApplication`. |

`gma-lib` and `adlib` expose the same package names with incompatible GMA types — never put both
on one classpath.

## Build Commands

```bash
./gradlew :gma-lib:testDebugUnitTest          # JVM unit tests
./gradlew :app:assembleAppDevDebug            # sample, test ad units
./gradlew :app:assembleAppProdRelease         # sample, R8 enabled
./gradlew :gma-lib:compileDebugAndroidTestKotlin
./gradlew :gma-lib:publishReleasePublicationToGitHubPackagesRepository
```

Unit tests live in `gma-lib/src/test` (JVM, pure logic: revenue mapping, billing pricing, consent mapping, caps).

## Build Configuration

- AGP 9.1.1, Kotlin 2.3.21, Gradle wrapper per `gradle/wrapper`
- compileSdk 37 (gma-lib 37.1), minSdk 26, targetSdk 37, JVM 17
- Ads / attribution / billing / mediation versions live in `gradle/libs.versions.toml` and are shared
  by `gma-lib` and `adlib`. Keep each mediation adapter on the release family of its network SDK.
- `gma-lib` excludes `play-services-ads(-lite)` for itself; an app consuming the **published**
  artifact must add the same `configurations.configureEach { exclude(...) }` (POMs do not carry
  configuration-level excludes).

## Architecture (gma-lib, package `com.lib.ads.gma.ads`)

| Package | Responsibility |
|---------|----------------|
| `application/AdsMultiDexApplication` | Bootstrap: config, attribution SDKs, GMA init (consent-aware) |
| `engine/AdsProvider` | SDK lifecycle: `initialize`, `initAttribution`, readiness (`awaitReady`, `whenAdsReady`), `onConsentUpdated` |
| `config/` | `AdSdkConfig` (all flags), `AdjustConfig`, `AppsflyerConfig`, `DailyAdsRevenueConfig` |
| `manager/` | Raw per-format load/show (`InterstitialAdManager`, `RewardAdManager`, `BannerAdManager`, `NativeAdManager`, `AppOpenAdManager` for splash) |
| `helper/` | Lifecycle-aware helpers (`AdsHelper` subclasses), preload stores, Compose UIs, `ConsentManager`, resume `AppOpenManager` |
| `event/` | All tracking. `AdsLogEventManager` is the single entry point every format calls |
| `billing/` | `AppPurchase` facade → `BillingConnectionManager`, `ProductDetailsRepository`, `PurchaseProcessor`, `PurchaseVerifier`, `PurchaseEntitlementStore` |

### Rules that keep the library correct

1. **Threading.** The Next-Gen SDK calls *all* load and event callbacks on a background thread.
   Raw loads and banner/native event listeners deliver to main (`runOnMain`); analytics may stay on
   the SDK thread. Any new SDK callback must follow the same rule; shared mutable flags read across
   threads are `@Volatile` or main-only.
2. **Ad unit ids.** Use `Ad.adUnitIdOrEmpty()`. `ResponseInfo.extractAdUnitIdOrNull()` is empty for
   mediated fills and Ad Manager units.
3. **Revenue.** Every paid callback calls `AdsLogEventManager.logPaidAdImpression(context, adValue, ad, adType)`.
   It builds one `PaidImpression` and fans out: Firebase `ad_impression` (GA4 params), legacy
   `paid_ad_impression*`, Adjust `trackAdRevenue`, AppsFlyer `logAdRevenue`, revenue totals. Do not
   log `ad_impression` anywhere else.
4. **Rewards** are granted only from the SDK reward callback (`onRewarded`); `onRewardedAdClosed(earnedReward)`
   reports whether it fired. Premium users never load rewarded ads; every reward entry point asks
   `RewardAdListener.onPremiumUser()` first so the app can grant the reward itself.
5. **Waterfalls**: the first id that loads wins; late fills are kept or destroyed, never shown twice.
   Splash flows reach the listener with exactly one terminal callback after a timeout.
6. **Billing**:
   - Revenue is reported only for `PURCHASED`, once per purchase token, from the bought offer's price.
   - Entitlements are rebuilt only from a successful Play query, then persisted.
   - Unacknowledged purchases are acknowledged on refresh.
   - Consumables completed while the app was dead go to `AppPurchase.setRecoveredConsumableListener`.
7. **Consent**: UMP/TCF is forwarded to Firebase Consent Mode v2 and Adjust (Google DMA) via
   `AdsProvider.onConsentUpdated`; AppsFlyer reads TCF itself.

### Analytics flags (`AdSdkConfig`) — defaults preserve existing behaviour

| Flag | Default | Effect |
|------|---------|--------|
| `logFirebaseAdImpression` | true | GA4 `ad_impression` with revenue; turn off only if AdMob auto-collection already logs it |
| `propagateConsent` | true | UMP → Firebase / Adjust consent |
| `consentDebugGeography`, `consentTestDeviceHashedIds` | null / empty | UMP debug settings, dev builds only |
| `logTotalRevenueEachImpression` | true | per-impression `event_current_total_revenue_ad` |
| `logAdHealthEvents` | false | `ad_load_time`, `ad_load_failed`, `ad_show_failed` |
| `logFirebasePurchase` | false | Firebase `purchase` (leave off when GA4 is linked to Play) |
| `dailyAdsRevenue` | null | Azura `Daily_Ads_Revenue` milestone |

Adjust event fields in `AdjustConfig` are **event tokens** from the dashboard; blank disables the event.

## Key Entry Points

- `app/.../GmaApplication.kt` — sample configuration
- `gma-lib/.../application/AdsMultiDexApplication.kt` — bootstrap order
- `gma-lib/.../engine/AdsProvider.kt` — init and readiness
- `gma-lib/.../event/AdsLogEventManager.kt` — tracking fan-out
- `gma-lib/.../billing/AppPurchase.kt` — IAP facade
- `gma-lib/.../helper/ConsentManager.kt` — UMP
