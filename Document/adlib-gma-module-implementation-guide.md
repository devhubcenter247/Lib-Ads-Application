# `adlib-gma` Review and New GMA Ads Module Implementation Guide

This document is the implementation-oriented reference for the ads code in this
repository. It describes the current `adlib-gma` module, the shared `adlib`
module, the sample application, the public functions and states, and the work
required to build a new Google Mobile Ads (GMA) provider module.

The source code is the authority. This document intentionally records behavior
that is observable in the current implementation, including behavior that
should be improved in a new module.

## 1. Executive summary

The repository currently contains two separate SDK layers:

| Layer | Module | Package root | Role |
|---|---|---|---|
| Shared abstraction and application-facing API | `adlib` | `com.lib.ads.application.ads` | Provider-independent helpers, managers, wrappers, Compose APIs, billing and consent abstractions |
| Existing GMA implementation | `gma-lib` | `com.lib.ads.application.ads` | Public GMA facade plus implementation packages, GMA wrappers, preload engine and legacy APIs |
| Demo application | `app` | `com.lib.ads.application.app` | Screens that exercise the `adlib` API |

Important current-state fact: `app/build.gradle.kts` currently contains
`implementation(project(":adlib"))` and does not contain
`implementation(project(":adlib-gma"))`. The demo app therefore does not prove
that `adlib-gma` works at runtime. Most demo screens use the newer abstraction
API in `adlib`, not the `Ads` API in `adlib-gma`.

The recommended architecture for a new module is:

```text
Application / feature screens
        |
        v
Stable provider-neutral contracts (adlib or a new small contracts module)
        |
        v
GMA provider implementation (new module)
        |
        v
Google Mobile Ads SDK + UMP + selected mediation adapters
```

Keep provider-neutral models and lifecycle contracts above the GMA module.
Keep GMA SDK types, GMA request construction, response metadata and adapter
details inside the provider implementation. If compatibility with existing
`adlib-gma` clients is required, add a compatibility facade instead of making
the new core depend on every legacy class.

## 2. Repository and build topology

### 2.1 Gradle modules

`settings.gradle.kts` includes:

```kotlin
include(":app")
include(":adlib")
include(":adlib-gma")
```

Relevant module settings:

| Module | Namespace | Min SDK | JVM | Published coordinates in source |
|---|---|---:|---:|---|
| `adlib` | `com.lib.adlib` | 26 | 17 | `com.lib:adlib:1.7.3` |
| `adlib-gma` | `com.lib.ads.application` | 26 | 17 | `com.ads.app:gmasdk:1.0.1` |

Both libraries enable Compose and expose many dependencies as `api`. This is
convenient for consumers but increases dependency surface and the probability
of version collisions. A new module should expose only dependencies that are
part of its intended public API.

### 2.2 Current dependency split

`adlib-gma` currently uses:

- Google Mobile Ads Next-Gen SDK: `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.1`.
- UMP: `com.google.android.ump:user-messaging-platform:4.0.0`.
- Billing: `com.android.billingclient:billing-ktx:9.1.0`.
- Firebase Analytics, Adjust, AppsFlyer, Facebook and AppsFlyer ad revenue.
- Facebook, Mintegral, Pangle and Unity mediation adapters.
- AndroidX, Compose, Shimmer, Lottie, SDP, Retrofit/OkHttp, Guava, coroutines and Arrow.

It also applies a global configuration exclusion for
`com.google.android.gms:play-services-ads-api`. This must be reviewed before
copying the module: the Next-Gen SDK and the classic Play Services GMA SDK are
different dependency families, and mixing them can produce duplicate classes,
incompatible callback types or runtime surprises.

The `app` module currently adds `play-services-ads:24.9.0` while depending on
`adlib`, whose own dependency graph contains another GMA version. A new module
must choose one GMA SDK family and verify the resolved dependency graph with
Gradle before release.

### 2.3 Resources and manifest expectations

`adlib-gma` owns reusable resources such as:

- `list_id_test`, used by the legacy banner/native loaders to detect test IDs.
- Loading/shimmer layouts and ad layout resources.
- Warning notification resources used by `Admob.showTestIdAlert`.

The consuming application must provide its own application-level GMA app ID
configuration according to the selected GMA SDK, and must declare any required
network, billing, Facebook or provider-specific metadata. Do not assume that
library resources or manifest entries automatically satisfy an app's product
configuration.

## 3. Current initialization and lifecycle

### 3.1 Existing `adlib-gma` initialization

The entry point is `Ads.getInstance()` from
`ads/engine/Ads.kt`.

Typical sequence:

```kotlin
val config = AdsConfig(application, AdsConfig.ENVIRONMENT_DEVELOP).apply {
    appAdId = "ca-app-pub-..."
    listDeviceTest = listOf("TEST_DEVICE_HASH")
    idAdResume = "APP_OPEN_UNIT_ID"
    adjustConfig = AdjustConfig(enableAdjust = false)
    taichiConfig = TaichiConfig(isEnableTaichi = false)
}

Ads.getInstance().init(application, config)
Ads.getInstance().initAdsNetwork {
    // GMA initialization completed; requests can now be made.
}
```

`Ads.init(application, config)` stores the application/configuration, sets
the global development flag (`AppUtil.VARIANT_DEV`), optionally initializes
Adjust, and configures Taichi. It does not itself call GMA initialization.

`Ads.initAdsNetwork(initFinished)` queues the callback through
`runWhenReady`, prevents duplicate initialization with an `AtomicBoolean`, and
delegates to `Admob.getInstance().init`. When initialization completes it
flushes pending callbacks and initializes `AppOpenManager` if resume app-open
IDs are configured.

`Ads.runWhenReady(callback)` executes immediately when the SDK is ready;
otherwise it queues the callback. `Ads.whenAdsReady { ... }` is an extension
that provides the same gate for provider functions.

### 3.2 Existing GMA singleton

`Admob.getInstance()` is a singleton. Its public responsibilities include:

- `init(application, appAdId, testDeviceList, onInitialized)`.
- `getAdRequest(adUnitId)`.
- `setDisableAdResumeWhenClickAds(Boolean)`.
- `setOpenActivityAfterShowInterAds(Boolean)`.
- `interstitialSplashLoaded()` and `getInterstitialSplash()`.
- `getDeviceId(activity)`.

The implementation uses `MobileAds.initialize` on an IO coroutine and handles
WebView data-directory suffixes for secondary processes on Android 9+.

### 3.3 Consent sequence

The shared `adlib` demo uses:

```text
Application.onCreate
  -> AdSdkInitializer.init
SplashActivity.onCreate
  -> AdsConsentManager.requestUMP
  -> getCanRequestAd
  -> wait for AdSdkInitializer init callback when consent permits
  -> continueStartup(enableAds = true/false)
```

For a new provider, consent must be resolved before requesting ads. Consent
failure, no internet, and unavailable consent state must have a defined product
fallback: continue the app without ads, retry later, or show a controlled error.
Never leave the splash screen waiting indefinitely for a consent or SDK
callback.

### 3.4 App lifecycle and app-open ads

`AppOpenManager` registers activity lifecycle callbacks and observes
`ProcessLifecycleOwner`. It tracks the current activity with a weak reference,
loads resume ads, prevents duplicate display with `isShowingAd`, and skips ads
for purchased users.

The main lifecycle callbacks and controls are:

- `init(application, adUnitId)` or list-based initialization.
- `showAdIfAvailable()`.
- `setAdResumePreShowListener(listener)`.
- `disableAppResumeWithActivity(activityClass)`.
- `disableAdResumeByClickAction()`.
- `isAdAvailable(...)` and loading state checks.
- Splash loading/showing functions described in the app-open section below.

A provider implementation should make these invariants explicit:

1. Never show an app-open ad over another full-screen ad.
2. Never show an ad when the current activity is finishing/destroyed.
3. Never show an ad when the process is not in a started lifecycle state.
4. Clear a shown ad and reload after dismissal or failed show.
5. Enforce freshness/expiration before showing cached app-open ads.
6. Provide a deterministic next action for every timeout and failure path.

## 4. Public API inventory of `adlib-gma`

This section lists the API families. Function names below are the names to
preserve or deliberately replace when implementing a compatible module.

### 4.1 Configuration and enums

#### `AdsConfig`

File: `ads/config/AdsConfig.kt`

Fields and functions:

- `application: Application`.
- `isVariantDev: Boolean`.
- `adjustConfig: AdjustConfig?`.
- `taichiConfig: TaichiConfig`.
- `eventNamePurchase: String`.
- `idAdResume: String?`.
- `listIdAdResume: List<String>?`.
- `listDeviceTest: List<String>`.
- `appAdId: String?`.
- `setEnvironment(environment: String)`.
- `isEnableAdResume(): Boolean`.
- Constants `ENVIRONMENT_DEVELOP` and `ENVIRONMENT_PRODUCTION`.

#### `AdjustConfig`

- `enableAdjust` and `adjustToken`.
- `eventNamePurchase`.
- `eventAdImpressionValue`.
- `eventAdImpression`.
- `eventAdClick`.
- `fbAppId`.

#### `TaichiConfig`

- `isEnableTaichi`.
- `day2ImpressionThreshold`.
- `revenueThresholdUsd`.

#### `AdType`

`BANNER`, `INTERSTITIAL`, `NATIVE`, `REWARDED`, `APP_OPEN`.

#### Callback contracts

`com.lib.ads.application.ads.callback.AdsCallback` is the low-level GMA callback
used by the control layer. The public facade callback is
`com.lib.ads.application.ads.callback.AdsCallback`; it uses wrapper models and is
the callback application code should prefer.

The low-level GMA callback has these optional methods:

- `onNextAction()`.
- `onAdClosed()`.
- `onAdFailedToLoad(LoadAdError)`.
- `onAdFailedToShow(FullScreenContentError)`.
- `onAdLoaded()`.
- `onAdSplashReady()`.
- `onInterstitialLoad(InterstitialAd)`.
- `onAdClicked()`.
- `onAdImpression()`.
- `onRewardAdLoaded(RewardedAd)` and its rewarded-interstitial overload.
- `onUnifiedNativeAdLoaded(NativeAd)`.
- `onInterstitialShow()`.
- `onBannerLoaded(AdView)`.

`RewardCallback` contains reward-specific methods:

- `onUserEarnedReward(RewardItem)`.
- `onRewardedAdClosed()`.
- `onRewardedAdFailedToShow(FullScreenContentError)`.
- `onAdClicked()`.
- `onAdImpression()`.

`AdResumePreShowListener.onPreShowAd()` and
`UMPResultListener.onCheckUMPSuccess(canRequestAds)` are single-method
listeners for lifecycle/consent integration.

### 4.2 Banner APIs

File: `ads/engine/AdsBanner.kt` and GMA implementation files in
`ads/gma/AdmobBanner.kt` and `AdmobBannerFragment.kt`.

Main extension functions on `Ads`:

```kotlin
loadBanner(activity, id)
loadBanner(activity, id, AdsCallback)
loadCollapsibleBanner(activity, id, gravity, AdsCallback)
loadBannerFragment(activity, id, rootView)
loadBannerFragment(activity, id, rootView, AdsCallback)
loadCollapsibleBannerFragment(activity, id, rootView, gravity, AdsCallback)
requestLoadBanner(activity, idBannerAd, collapsibleGravity, AdsCallback)
requestLoadBanner(activity, idBannerAd, collapsibleGravity, useInlineAdaptive, maxHeight, AdsCallback)
loadBannerList(activity, listId, collapsibleGravity, AdsCallback)
loadBannerList(activity, listId, collapsibleGravity, useInlineAdaptive, maxHeight, AdsCallback)
populateUnifiedBannerAdView(adView, adContainer)
```

Behavior:

- `loadBanner` finds `R.id.banner_container` and
  `R.id.shimmer_container_banner` in the activity or root view.
- It creates a GMA `AdView`, chooses adaptive or inline adaptive size, starts a
  shimmer, loads the request, then swaps visibility on success/failure.
- `requestLoadBanner` returns the loaded `AdView` through
  `AdsCallback.onBannerLoaded` and is the better primitive for reusable custom
  containers.
- `loadBannerList` tries IDs sequentially and stops at the first success.
- `collapsibleGravity` is passed into the request as the collapsible direction.
- Purchased users are short-circuited. For request APIs the callback receives a
  cancelled `LoadAdError`; for view APIs the shimmer is hidden and no ad is shown.

New-module requirements:

- Provide explicit ownership and destruction for each `AdView`.
- Remove an existing child before adding another one, or guarantee one helper
  instance per container.
- Make test-ID detection a policy service rather than hard-coding notification
  and exception behavior in the ad loader.
- Make adaptive size calculation density-safe and test it at rotation/window
  resize boundaries.

### 4.3 Interstitial APIs

File: `ads/engine/AdsInterstitial.kt`.

Functions:

- `preloadInterstitialAds(id)`.
- `getInterstitialAdsPreload(id): ApInterstitialAd?`.
- `getInterstitialAds(context, id, AdsCallback)` (deprecated).
- `getInterstitialAdsList(context, listId, AdsCallback)`.
- `forceShowInterstitial(activity, ApInterstitialAd?, AdsCallback)` (deprecated).
- `loadInterstitial(context, id, onResult: (InterstitialAdEvent) -> Unit)`.
- `showInterstitial(activity, ad, onNextAction, onResult)`.

The preferred path is `loadInterstitial` followed by `showInterstitial` using
the provider-neutral `InterstitialAdEvent`. The old callback path exposes the
legacy `ApInterstitialAd` wrapper and is marked deprecated.

The list API is a sequential waterfall: an ID is attempted, and the next ID is
requested only after failure. The final failure is returned to the caller.

`showInterstitial` invokes `onNextAction` after `Dismissed` or `FailedToShow`.
The implementation must also cover null/not-ready ads and activity lifecycle
failure so that the caller is never blocked.

### 4.4 Rewarded and rewarded-interstitial APIs

File: `ads/engine/AdsReward.kt`.

Functions include:

- `preloadRewardAd(id)` / `getRewardAdPreload(id)`.
- `preloadRewardInterstitialAd(id)` / `getRewardInterstitialAdPreload(id)`.
- `loadRewardAd(activity, id, AdsCallback)` (deprecated).
- `loadRewardAdList(activity, listId, AdsCallback)`.
- `loadReward(activity, id, onResult: (RewardAdEvent) -> Unit)`.
- `loadRewardList(activity, listId, onResult)`.
- `showRewardAd(activity, ApRewardAd, rewardCallback)`.
- `showReward(activity, ApRewardAd, onNextAction, onResult)`.
- Rewarded-interstitial equivalents: load/show and list variants.

The critical business invariant is that entitlement is granted only after
`onUserEarnedReward`/the corresponding `RewardAdEvent`, never merely after
ad-loaded or ad-closed. The demo application correctly tracks `earnedReward`
before navigating after a rewarded ad.

The new module should expose an explicit result model containing at least:

- loaded/failed-to-load;
- shown/failed-to-show;
- clicked/impression;
- user-earned-reward;
- dismissed;
- whether the reward was granted.

### 4.5 Native APIs

File: `ads/engine/AdsNative.kt`.

Loading functions:

- `loadNativeList(context, listId, layoutCustomNative, callback)`.
- `loadNativeNextAd(context, listId, pos, layoutCustomNative, finalCallback, done)`.
- `loadNativeListTimeOut(context, listId, timeOutPerId, layoutCustomNative, callback)`.
- `loadNativeNextAdWithTimeout(...)`.
- `resolveTimeoutMs(timeOutPerId, pos)`.
- `loadNativeAd(activity, id, layoutCustomNative, adPlaceHolder, shimmer, callback)`.
- `loadNativeAdResultCallback(context, id, layoutCustomNative, callback)`.
- The overload accepting `maxNumberOfAds`.
- `populateNativeAdView(activity, apNativeAd, adPlaceHolder, shimmer)`.

Native list loading is sequential ID fallback. The timeout variant abandons the
current attempt after its timeout and moves to the next ID. When the timeout
list is shorter than the ID list, the implementation falls back to the first
timeout value; invalid/non-positive values become 5 seconds.

`ApNativeAd` stores the custom layout ID and the provider native ad. The custom
layout must inflate to a GMA `NativeAdView` and must bind the GMA asset views
correctly. A new provider must not allow application code to cast a provider
native object to a different SDK's native object.

### 4.6 Native preload engine

Files: `ads/helper/adnative/preload/*`.

`NativeAdPreload` is a singleton keyed by either:

- `KeyPreload(adId, layoutId)`;
- `KeyPreloadList(listId, layoutId)`.

Public operations include:

- `preload(activity, adId, layoutId, buffer)`.
- `preload(activity, listId, layoutId, buffer)`.
- `preload(activity, listId, listTimeout, layoutId, buffer)`.
- Convenience overloads with a default buffer of 1.
- `canRequestLoad(context)`.
- `getAdPreloadState(...)`.
- `pollAdNative(...)` and `pollOrAwaitAdNative(...)`.
- `getAdNative(...)` and `getOrAwaitAdNative(...)`.
- `isPreloadAvailable(...)`.
- `isPreloadInProcess(...)`.
- `getNativeAdBuffer(...)`.
- `registerAdsCallback(...)` / `unRegisterAdsCallback(...)`.

`NativeAdBufferState` values:

| State | Meaning |
|---|---|
| `None` | No preload operation has started or state was released |
| `Start` | One or more load requests were started |
| `Consume(ad)` | A native ad entered the buffer |
| `Complete` | All requested attempts completed |
| `Error` | At least one attempt failed; completion is also signaled when the counter reaches zero |

`NativeAdPreloadTemplate` maintains a synchronized `ArrayDeque`, an atomic
request counter, an in-progress flag, StateFlow and LiveData. `poll*` removes
an item; `get*` peeks without removing it. List preload queues can be sorted by
the configured priority order using `AdUnitTagger`.

`NativeAdPreloadClientOption` supports:

- `preloadAfterShow`;
- `preloadBuffer`;
- `preloadOnResume`;
- builder methods `setPreloadAfterShow`, `setPreloadBuffer`,
  `setPreloadOnResume`, and `build`.

New-module requirements:

- Release/cancel coroutine scopes when their owning feature is gone.
- Define whether cached native ads are safe to reuse after configuration,
  orientation or layout changes.
- Prevent a preload counter from remaining in progress when a provider callback
  is never delivered.
- Make callback registration lifecycle-aware to avoid retaining activities.

### 4.7 App-open and splash APIs

Files: `ads/gma/AppOpenManager.kt`,
`AppOpenManagerResume.kt`, and `AppOpenManagerSplash.kt`.

Splash functions:

- `loadOpenAppAdSplash(activity, timeDelay, timeOut, callback)`.
- `loadOpenAppAdSplashList(activity, timeDelay, timeOut, callback)`.
- `loadNextOpenSplash(...)`.
- `showAppOpenSplash(activity, callback)`.
- `onCheckShowAppOpenSplashWhenFail(activity, callback, timeDelay)`.

The splash implementation supports a delay (minimum display window) and a
timeout (maximum wait). Every path should call `onNextAction` exactly once.
In a new implementation, replace scattered `Handler`/`Runnable` state with a
single cancellable operation object so late load callbacks cannot trigger a
second navigation or display.

### 4.8 GMA response and event analytics

`ResponseInfoExt.extractAdUnitIdOrNull()` extracts response metadata. The
following event utilities are available:

`AdsLogEventManager`:

- `logPaidAdImpression(context, adValue, responseInfo, adType)`.
- `logClickAdsEvent(context, adUnitId)`.
- `logCurrentTotalRevenueAd(context, eventName)`.
- `logTotalRevenue001Ad(context)`.
- `logTotalRevenueAdIn3DaysIfNeed(context)`.
- `logTotalRevenueAdIn7DaysIfNeed(context)`.
- `trackAdRevenue(id)`.
- `onTrackEvent(eventName)` and the ID overload.
- `onTrackTokenFcm(token, context)`.
- `onTrackRevenue(eventName, revenue, currency)`.
- Purchase/subscription tracking functions.
- `pushTrackEventAdmob(adValue, adSourceName)`.
- `onTrackImpression(context)`.

`AdsTaichi`:

- `configure(enabled, day2ImpressionThreshold, revenueThresholdUsd)`.
- `isEnabled()`.
- `onTrackingPaidImpression(context, adValue, adFormat)`.
- `onTrackingDailyRevenue(context, adValue, adFormat)`.

`FirebaseAnalyticsUtil`, `FacebookEventUtils` and `AdsAdjust` provide the
provider-specific analytics bridges. A new module should centralize impression
revenue normalization: micros to major currency units, currency code, precision,
ad format, ad unit ID, mediation network and response ID. Analytics failures
must never fail an ad callback or user navigation.

### 4.9 Billing and purchased-user gating

The GMA module contains a complete billing area under
`ads/billing`, including:

- `AppPurchase` singleton and billing initialization/purchase entry points.
- `BillingConnectionManager` for BillingClient connection management.
- `PurchaseProcessor`, `PurchaseVerifier`, `ProductDetailsRepository`.
- `PurchaseItem`, `BillingProductInfo`, `PurchaseResult`, `PurchaseEvent` and
  `BillingState` models.
- `BillingListener`, `PurchaseListener`, and `UpdatePurchaseListener`.
- `PurchaseDevBottomSheet` for development purchase UI.

`ProductDetailsRepository` supports product synchronization and lookup methods
such as `syncPurchaseItemsToListProduct`, `queryProducts`,
`getINAPProductDetails`, `getSubsProductDetails`, `getPrice`, `getName`,
`getPriceSub`, `getPeriod`, `getTrialPeriod`, `getProductInfoList`, product ID
lists, currency/price helpers and `getListInAppId`/`getListSubId`.

Ad loaders use `AppPurchase.getInstance().isPurchased()` as a hard gate. This
gate must be shared by banner, native, interstitial, rewarded and app-open
flows. Purchase state must be refreshed and persisted safely; a stale false
value can show ads to a paying user, while a stale true value can suppress
revenue.

For a clean new module, consider moving billing into an independent feature or
injecting an `AdSuppressionPolicy` interface. Ads should not need to know the
details of Play Billing product parsing.

## 5. `adlib` shared API used by the app

The sample application primarily uses these newer abstractions:

### 5.1 Initialization and managers

- `AdSdkInitializer.init(application, AdSdkConfig)`.
- `AdSdkInitializer.setInitCallback(callback)`.
- `AdSdkConfig` with environment, consent, Adjust, AppsFlyer, test devices and
  interstitial interval settings.
- `AdsManager` and legacy `AdsManger` for high-level ad operations.
- `BannerAdManager`, `InterstitialAdManager`, `RewardAdManager`,
  `NativeAdManager`, and `AdmobNativeBannerManager`.
- `AppOpenManager` and `AdsConsentManager`.

### 5.2 Application-facing helpers

Banner:

- `BannerAdHelper`.
- `BannerAdConfig`.
- `BannerAdParam.Request.create()` and resume/request variants.
- `bannerAdWaterfall`.
- Compose `BannerAd`, `BannerAdCard`, `rememberBannerAd`.
- `BannerAdDisplayState`: `Idle`, `Loading`, `Loaded`, `Error`, `Cancelled`.

Interstitial:

- `interstitialAdWaterfall`.
- `InterstitialAdHelper` and `InterstitialAdExt`.
- `waitLoadAndShow`.
- `InterstitialAdEvent` and `FullScreenAdGate`.

Reward:

- `rewardedAdWaterfall`.
- `RewardedAdHelper`, `RewardedAdExt`.
- Rewarded-interstitial helper equivalents.
- `RewardCallback` and `ApRewardItem`.

Native:

- `NativeAdSpec.simple(...)` and `NativeAdSpec.waterfall(...)`.
- `NativeAds.register`, `preload`, `stateFlow`, `available`, and consume/poll
  operations.
- `NativeAdProviderHelper`.
- `NativeAdParam.Request.create()` and `ResumeRequest`.
- `NativeAdContainer`, `NativeAdCard`, `NativeAdView`, `NativeAdMediaView`.
- `rememberNativeAdPreload`.
- `NativeAdState` and `NativeAdBufferState`.

The shared layer is the preferred application API because it already hides
provider SDK objects and gives feature code a stable state/callback model. A
new GMA module should implement these contracts where possible instead of
duplicating application-specific waterfall and Compose code.

## 6. How the sample app uses the APIs

### 6.1 `MyApplication`

File: `app/src/main/java/com/lib/ads/application/app/MyApplication.kt`.

Startup calls:

1. `AdsMultiDexApplication.onCreate`.
2. `initBilling` (currently commented product initialization).
3. `initAds`.
4. Configure `AdSdkConfig` environment from `BuildConfig.env_dev`.
5. Configure UMP debug/test device settings.
6. Configure Adjust and optional AppsFlyer.
7. Add test devices.
8. Call `AdSdkInitializer.init`.
9. Set `AdsManager.disableAdResumeWhenClickAds`.
10. Disable resume ads on `SplashActivity`.
11. Initialize app network monitoring.

### 6.2 `SplashActivity`

The screen demonstrates:

- UMP before ad initialization.
- App-open resume initialization.
- Native preload for the Language screen using a shared `NativeAdSpec`.
- App-open `waitLoadAndShow`.
- Interstitial `waitLoadAndShow`.
- Rewarded `waitLoadAndShow` with reward-earned gating.
- Interstitial splash waterfall APIs (currently helper methods are present but
  commented/optional in the startup path).
- Banner helper setup.
- Native preload StateFlow collection.

### 6.3 Test screens

The app contains dedicated examples:

- `TestBannerActivity`: View banner helper, reload and state collection.
- `TestBannerComposeActivity`: simple, waterfall, manual reload, inline
  adaptive, collapsible and `canShowAds = false` cases.
- `TestNativeBasicActivity`: one native unit without preload.
- `TestNativePreloadActivity`: waterfall native with buffer preload.
- `TestNativeProviderActivity`: tag/spec-based native provider helper.
- `TestAdmobNativeBannerActivity`: native-banner helper.
- `TestNativeComposeActivity`, `Compose1AdActivity`, `Compose2AdActivity`:
  Compose native preload and custom native layouts.
- `ReloadAdsActivity`: reload and disabling resume after click behavior.
- `NativeSplashActivity`: native splash-related flow.

These screens are useful contract examples for a new implementation, but they
must be run against the intended module. With the current Gradle dependencies,
they exercise `adlib`, not `adlib-gma`.

## 7. Recommended design for a new GMA module

### 7.1 Module boundaries

Use one of these two migration strategies:

#### Strategy A: provider implementation behind existing `adlib`

Use when application code should remain unchanged.

- Keep `adlib` contracts and wrappers.
- Create `adlib-gma-new` or replace the provider implementation behind the
  contracts.
- Move GMA SDK imports to the new module.
- Keep app code dependent on contracts/helper packages only.
- Add integration tests that prove `adlib` behavior is backed by GMA.

#### Strategy B: compatibility module for `adlib-gma`

Use when existing consumers already import `Ads` and the legacy GMA APIs.

- Preserve `Ads`, `AdsCallback`, `Ap*` wrappers and extension names.
- Implement each facade using the new internal provider.
- Mark old APIs deprecated only after a replacement exists.
- Do not expose classic GMA and Next-Gen GMA types from the same public method
  family.

### 7.2 Stable provider-neutral contracts

Define these contracts before implementing GMA:

```kotlin
interface AdsProvider {
    suspend fun initialize(context: Context, config: AdsProviderConfig): InitResult
    suspend fun loadBanner(request: BannerRequest): BannerResult
    suspend fun loadInterstitial(request: FullScreenRequest): InterstitialResult
    suspend fun loadRewarded(request: FullScreenRequest): RewardedResult
    suspend fun loadNative(request: NativeRequest): NativeResult
}
```

The exact types may follow the existing `adlib` models, but the contract should
define:

- initialization idempotency;
- consent readiness;
- ad suppression/purchase state;
- request cancellation;
- timeout behavior;
- waterfall order;
- display lifecycle;
- reward entitlement;
- impression/revenue events;
- release/destroy behavior.

### 7.3 Suggested internal components

1. `GmaSdkInitializer`: one idempotent initialization state machine.
2. `GmaConsentGateway`: UMP request and `canRequestAds` result.
3. `GmaAdRequestFactory`: test flags, extras, targeting and request IDs.
4. `GmaBannerLoader`: adaptive/inline/collapsible banner handling.
5. `GmaFullScreenLoader`: interstitial/rewarded/app-open loading and display.
6. `GmaNativeLoader`: native binding and layout validation.
7. `GmaWaterfallExecutor`: sequential IDs and per-attempt timeout.
8. `GmaPreloadStore`: bounded, lifecycle-aware queues.
9. `GmaEventReporter`: analytics and paid-impression normalization.
10. `GmaAdSuppressionPolicy`: purchased/consent/offline checks.
11. `GmaDebugTools`: test-ID validation and diagnostics, disabled or safe in release.

## 8. Review findings in the current `adlib-gma`

These are implementation findings to address or explicitly accept when building
the new module.

### High priority

1. **Two GMA SDK families exist in the repository.** `adlib-gma` uses the
   Next-Gen package while `adlib`, and the app, use classic Play Services GMA
   dependencies. Decide the supported family and validate the resolved graph.
2. **The sample app does not consume `adlib-gma`.** Add a dedicated integration
   sample or switch a build variant intentionally; otherwise regressions in the
   GMA module are invisible.
3. **Callback exactly-once guarantees are not centralized.** Splash timeout,
   late load callbacks, list fallback and display callbacks can converge on
   navigation. New code needs an exactly-once result gate.
4. **Preload scopes and queues need lifecycle policy.** The singleton preload
   engine uses a `CoroutineScope(Dispatchers.Main)` and stores provider objects.
   Define cancellation, memory limits and activity/layout ownership.
5. **Public APIs expose provider SDK classes.** `AdsCallback` contains GMA
   `LoadAdError`, `InterstitialAd`, `NativeAd`, `AdView` and similar types.
   This makes provider replacement and binary compatibility difficult. Keep
   these types internal to a new module or isolate them in a compatibility API.

### Medium priority

1. **Billing is coupled to every ad loader.** Inject suppression state or move
   billing behind a small interface.
2. **Analytics is coupled to load/show callbacks.** Analytics failures should
   be isolated and event payload creation should be tested independently.
3. **Legacy and new APIs coexist.** `getInterstitialAds`,
   `forceShowInterstitial` and `loadRewardAd` are deprecated while newer event
   APIs exist. Document one canonical path and add migration examples.
4. **Test ID handling can throw in production.** `showTestIdAlert` intentionally
   throws when a known test ID is found in production. Keep this only as an
   explicitly enabled validation mode; do not let a debug convenience become a
   production crash policy unintentionally.
5. **View helpers assume required IDs and layout types.** `findViewById(...)!!`
   and native layout casts fail at runtime. Validate and return structured
   configuration errors in the new module.
6. **Error models are inconsistent.** Some paths use GMA `LoadAdError`, some
   use `FullScreenContentError`, and some convert to `ApAdError` or string
   messages. Define one provider-neutral error taxonomy.
7. **Global singletons complicate tests.** Initialization, purchase state,
   app-open state and preload queues should be resettable or injectable in test
   builds.

### Low priority / maintainability

1. Package name `funtion` is misspelled; do not reproduce it in a new API.
2. Names such as `Admob` remain even though the implementation uses the
   Next-Gen GMA SDK. Prefer `Gma`/`GoogleMobileAds` naming in new code.
3. Configuration contains unused or cross-cutting fields such as event names
   and app-open IDs. Split configuration by concern.
4. `api` is used for many implementation dependencies. Reduce exposure in the
   new module to improve consumer dependency stability.

## 9. Implementation checklist

### Build and packaging

- [ ] Decide classic GMA versus Next-Gen GMA and pin one compatible family.
- [ ] Align compile/min SDK, Java/Kotlin target and Compose versions.
- [ ] Verify no duplicate GMA classes with `./gradlew :app:dependencies`.
- [ ] Keep mediation adapter versions compatible with the selected GMA SDK.
- [ ] Add consumer ProGuard/R8 rules and test minified release output.
- [ ] Publish a versioned artifact with a changelog and migration notes.

### Initialization and consent

- [ ] Make initialization idempotent and thread-safe.
- [ ] Make callbacks queued before initialization and flushed once.
- [ ] Resolve UMP before ad requests.
- [ ] Continue the product flow on consent/network failure.
- [ ] Configure app ID and test devices without hard-coded production secrets.

### Ad formats

- [ ] Banner: adaptive, inline, collapsible, list fallback, resize and destroy.
- [ ] Interstitial: load, cache, freshness, show, dismissal, failure and next action.
- [ ] Rewarded: reward entitlement only after earned callback.
- [ ] Rewarded interstitial: same reward guarantee and lifecycle rules.
- [ ] Native: asset binding, layout validation, impression/click and destroy.
- [ ] App-open: activity tracking, process lifecycle, freshness and exclusions.
- [ ] Splash: delay/timeout race handling and exactly-once navigation.

### Preload and state

- [ ] Bound buffer size and memory.
- [ ] Define `peek` versus `consume` semantics.
- [ ] Cancel in-flight work when no owner remains.
- [ ] Ensure each load attempt decrements counters on success, failure and timeout.
- [ ] Test list priority ordering and duplicate ad units.
- [ ] Expose StateFlow/LiveData only where lifecycle ownership is clear.

### Observability and QA

- [ ] Log request ID, ad format, ad unit, provider/network and result.
- [ ] Record paid impressions with micros, currency and precision unchanged until normalization.
- [ ] Ensure analytics exceptions cannot break ad UX.
- [ ] Test purchased users, no consent, offline mode, no-fill, invalid ID,
      activity rotation, background/foreground, duplicate show and process death.
- [ ] Test every waterfall fallback and every timeout boundary.
- [ ] Run the demo app against the new module, not only against `adlib`.

## 10. Suggested verification commands

From the repository root:

```bash
./gradlew :adlib-gma:compileDebugKotlin
./gradlew :adlib:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:dependencies
./gradlew :adlib-gma:lint
```

For dependency debugging, inspect the runtime classpath and confirm that only
the selected GMA family is present. For production readiness, also run the
release/minified build and install it on a test device with test ad units.

## 11. Source map

Use these files as the primary implementation references:

- Initialization/configuration: `gma-lib/src/main/java/com/lib/ads/application/ads/GmaSdk.kt`, `ads/config/*`.
- GMA singleton and requests: `ads/gma/Admob.kt`.
- Banner: `ads/engine/AdsBanner.kt`, `ads/gma/AdmobBanner.kt`, `AdmobBannerFragment.kt`.
- Interstitial: `ads/engine/AdsInterstitial.kt`, `ads/gma/AdmobInterstitial.kt`.
- Rewarded: `ads/engine/AdsReward.kt`, `ads/gma/AdmobReward.kt`.
- Native: `ads/engine/AdsNative.kt`, `ads/gma/AdmobNative.kt`, `AdmobNativePopulate.kt`.
- App-open/splash: `ads/gma/AppOpenManager*.kt` and `ads/helper/appopen/*`.
- Consent: `ads/admob/AdsConsentManager.kt`.
- Wrappers/events: `ads/model/wrapper/*`, `ads/callback/*`, `ads/model/*`, `ads/event/*`.
- Native preload: `ads/helper/adnative/preload/*`.
- Billing: `ads/billing/*` (public billing facade and implementation).
- Public application API: `ads/callback/*`, `ads/helper/*`, `ads/manager/*`, `compose/*`.
- App integration examples: `app/src/main/java/com/lib/ads/application/app/MyApplication.kt` and `app/src/main/java/com/lib/ads/application/app/activity/*`.

## 12. Final recommendation

For a production-grade new GMA module, use the `adlib` contracts as the
application boundary, implement GMA behind those contracts, and retain
`Ads` only as a thin compatibility facade if existing consumers require it.
First add an integration sample that explicitly depends on the new module.
Then validate initialization, consent, all six ad flows, preload behavior,
purchase suppression and analytics under lifecycle/error tests before publishing.

## 13. `gma-lib` module

The Next-Gen GMA module `gma-lib` (used by the sample app) is documented in a single guide:
[gma-lib.md](gma-lib.md) — initialization, consent, banner/native holders and tag buffers,
lists, interstitial, rewarded, app-open, picture-in-picture, Billing and the parity notes with
`adlib`. Sections 1–12 above describe `adlib` and the old `adlib-gma` provider module.
