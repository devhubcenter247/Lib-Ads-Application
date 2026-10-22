# Test plan — gma-lib changes (commits 94fc756 → latest on the branch)

Manual checks on a real device for everything changed on branch `vult_update_ads_iap_check`.
Unit tests (69) and builds already pass; this list covers what only a device can show.

## 0. Setup

| Item | How |
|------|-----|
| Build | `appDev` flavor for ads/consent; a release-signed build uploaded to an internal track for billing |
| Firebase | Add `google-services.json` + the google-services plugin to the test app (the sample has neither), then `adb shell setprop debug.firebase.analytics.app <applicationId>` and open DebugView |
| Adjust | Fill `adjust_token` / `adjust_event_purchase` in `app/build.gradle.kts`; dev flavor uses the sandbox environment; watch the Adjust Testing Console |
| AppsFlyer | Fill `af_dev_key`; register the device as an AppsFlyer test device |
| Billing | License tester account; test card "always approves" and "slow, approves after a few minutes" (pending) |
| Consent | Dev flavor: `consentDebugGeography = ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA` and `consentTestDeviceHashedIds = listOf("<hash from logcat>")` in `AdSdkConfig`; `ConsentManager.resetConsentForDebug()` to see the form again. Ignored in production builds |
| Logcat filter | `AdsLogEventManager\|GMA_Purchase\|AdsAdjust\|ConsentSignals\|AdHealthEvents\|DailyAdsRevenue\|InterstitialAdManager\|AppOpenAdManager` |

Pass = every "Expected" holds, no crash or ANR, no `CalledFromWrongThreadException` or
`WindowLeaked` in logcat.

## 1. Ad revenue tracking (P0)

| # | Steps | Expected |
|---|-------|----------|
| 1.1 | Show one ad of each format: banner, native, interstitial, rewarded, rewarded interstitial, app-open | DebugView: one `ad_impression` per ad with `value`, `currency`, `ad_platform=admob`, `ad_source`, `ad_format` (`banner`/`native`/`interstitial`/`rewarded`/`rewarded_interstitial`/`app_open`), `ad_unit_name` |
| 1.2 | Same as 1.1 | No `ad_impression` **without** `value` (the old value-less one is gone). If a second `ad_impression` with `ad_platform=AdMob` appears, AdMob auto-collection is on → set `logFirebaseAdImpression = false` and retest |
| 1.3 | Get a fill from a mediated network (Meta/Pangle/Mintegral/Unity test mode) | `ad_unit_name` and logcat `unit=` are the real ad unit, not empty; `ad_source` names the network |
| 1.4 | Same | Adjust console: ad revenue `admob_sdk` with network, unit, placement. AppsFlyer: ad revenue with `ad_unit`, `ad_type`, `placement` |
| 1.5 | Unity test placement in the AdMob mediation group | Unity fills (adapter + SDK 4.19.0 now present). Before the fix this could not fill |
| 1.6 | Show ~5 impressions quickly (banner + native on one screen) | `paid_ad_impression_value_001` fires each time the accumulated revenue passes 0.01, no crash, totals keep growing across restarts |

## 2. IAP (P0)

| # | Steps | Expected |
|---|-------|----------|
| 2.1 | Buy a non-consumable | Success callback, ads disappear, Adjust shows **one** purchase event (not two), AppsFlyer `af_purchase` with `af_content_type=inapp` |
| 2.2 | Buy with the "slow" test card (pending) | `Pending` callback, **no** revenue event yet; when it completes (app open) → one revenue event + entitlement |
| 2.3 | Pending purchase, kill the app before it completes, reopen after it completes | Entitlement granted on launch, purchase acknowledged (not refunded after 3 days), revenue reported once |
| 2.4 | Buy a consumable, kill the app before consume finishes, reopen | With `setRecoveredConsumableListener` set: the listener fires **once**, the item is consumed; no second grant on the next launch |
| 2.5 | Subscribe to a plan with a free trial | Success, no revenue event (trial = 0) |
| 2.6 | Subscribe to a plan with an intro price | Adjust `trackPlayStoreSubscription` and AppsFlyer `af_purchase` (`subs`) with the **intro** price |
| 2.7 | Buy a consumable with quantity > 1 (if enabled in Play Console) | Revenue = price × quantity |
| 2.8 | Restart the app several times after any purchase | No repeated revenue events |
| 2.9 | Firebase DebugView after a purchase | `confirm_purchased_with_google` has no `purchase_token_part_*` params |

## 3. Premium state (P1)

| # | Steps | Expected |
|---|-------|----------|
| 3.1 | Premium user, cold start | No splash/app-open ad flashes before billing connects |
| 3.2 | Premium user, airplane mode, cold start | Still premium, no ads (used to lose premium on a failed query) |
| 3.3 | Refund / cancel the purchase in Play Console, reopen with network | Premium removed, ads come back |
| 3.4 | Premium user taps a rewarded entry (helper `waitLoadAndShow`/`forceShow`/`load`, `RewardAdManager.showReward(tag)` / `forceShowRewardAd` / raw load) with a listener whose `onPremiumUser()` grants the reward and returns `true` | Reward granted once, **no** `onFailed`/`onFailedToShow`/`onNotReady`, no ad request; a flow with `onNextAction` continues |
| 3.5 | Same with a listener that does not override `onPremiumUser` | Old behaviour: `onFailed` / `onFailedToShow("App is purchased")` or `onNotReady` |

## 4. Splash flows (P1)

Run each with fast network, slow network (throttle to 2G), and no network.

| # | Steps | Expected |
|---|-------|----------|
| 4.1 | Splash app-open, ad loads before timeout | Ad shows once, next screen opens **once** |
| 4.2 | Splash app-open, load slower than the timeout | Next screen opens once at the timeout; no ad pops up later, no second navigation |
| 4.3 | Splash app-open, press Home during loading, return | No crash, no double navigation, no stray dialog |
| 4.4 | Splash interstitial (`loadSplashInterstitialAds` / list) with `timeDelay > timeOut` | Exactly one of ad / next screen; nothing fires after the timeout |
| 4.5 | Splash interstitial, all ids fail before the timeout | Same as before the change: `onFailed`, then `onNextAction` at the timeout — confirm the app does not navigate twice |
| 4.6 | Rotate / finish the activity while the loading dialog is shown | No `WindowLeaked`; LeakCanary (if added) shows no leaked Activity from `InterstitialAdManager` / `AppOpenAdManager` |
| 4.7 | Splash app-open / `InterstitialAdHelper` / rewarded helpers with a timeout shorter than the load (slow network) | The late fill is destroyed (no leak in LeakCanary / memory profiler), nothing shows late |

## 5. Threading and UI (P1)

| # | Steps | Expected |
|---|-------|----------|
| 5.1 | Banner via `BannerAdManager.loadBanner` with a listener that touches views in `onLoaded` | No `CalledFromWrongThreadException` |
| 5.2 | `NativeAdManager.loadNativeAd(activity, …)` populating a view | Same |
| 5.3 | Native / banner in a RecyclerView or LazyColumn, scroll fast, click ads | No crash, clicks logged, ads refresh on resume only after an impression |
| 5.4 | Native waterfall with short per-id timeouts on a slow network | No `ConcurrentModificationException`; one ad shown |
| 5.5 | PiP sample (`PictureInPictureActivity`) | Loads and shows, no thread exception |

## 6. Interstitial interval (P1)

| # | Steps | Expected |
|---|-------|----------|
| 6.1 | Two screens with their own `InterstitialAdHelper`, `intervalBetweenAds = 30`; show on screen A, go to B within 30 s, trigger | B **skips** (global cap) and continues the flow |
| 6.2 | Same after 30 s | B shows |
| 6.3 | Change device time backwards by a day, trigger | Cap still behaves by elapsed time (not blocked for a day) |

## 7. Consent and attribution startup

| # | Steps | Expected |
|---|-------|----------|
| 7.1 | EEA (debug geography), fresh install, accept all | Logcat `ConsentSignals` no warning; DebugView shows consent granted; Adjust third-party sharing with `google_dma eea=1` |
| 7.2 | EEA, fresh install, reject / manage → deny ads | `ad_storage`/`ad_user_data`/`ad_personalization` denied; Adjust `google_dma` 0s; ads still request (non-personalised) only if UMP allows |
| 7.3 | Change choice via privacy options form | New values pushed once (Adjust gets one new sharing event, not one per launch) |
| 7.4 | Non-EEA install | Everything granted, `eea=0` |
| 7.5 | Fresh install (sample is consent-first) | GMA starts only after the UMP form; splash ads still work |
| 7.6 | Second launch, then kill the process on a deep screen and restore it | Ads load on the restored screen without passing through splash |
| 7.7 | Adjust console after first launch | Session/install tracked on first launch (SDK starts in `Application.onCreate`) |
| 7.8 | Production build with `consentDebugGeography` set | Setting ignored: real geography, `resetConsentForDebug()` does nothing |

## 8. Opt-in analytics (only if enabled)

| # | Flag | Expected |
|---|------|----------|
| 8.1 | `logAdHealthEvents = true` (dev flavor default) | `ad_load_time` per ad unit request with `load_time_ms`, `success`; `ad_load_failed` with `error_code`/`error_name`; `ad_show_failed` when a show fails |
| 8.2 | `logTotalRevenueEachImpression = false` | No `event_current_total_revenue_ad` per impression; 3-day/7-day events still fire |
| 8.3 | `dailyAdsRevenue = DailyAdsRevenueConfig()` | Day 1: `Daily_Ads_Revenue` once revenue > 0.01; otherwise on day 2 after 10 impressions; never twice |
| 8.4 | `logFirebasePurchase = true` | Firebase `purchase` with value, currency, item_id, transaction_id |

## 9. Gaps — status

Resolved after the first version of this plan:

| Gap | Resolution |
|-----|------------|
| No UMP debug geography / test devices | `AdSdkConfig.consentDebugGeography` / `consentTestDeviceHashedIds` + `ConsentManager.resetConsentForDebug()` (dev builds only) |
| Late fills after a timeout leaked | Destroyed in splash app-open, `InterstitialAdHelper`, `AppOpenAdHelper`, rewarded / rewarded-interstitial helpers (also when the fill races the cancellation) |
| Premium user and rewarded ads | `RewardAdListener.onPremiumUser(): Boolean` on every reward entry point (tests 3.4 / 3.5) |
| Unused `com.appsflyer:adrevenue` in gma-lib | Removed. An app calling `AppsFlyerAdRevenue` directly must add the dependency itself |
| Tests ignored by `.gitignore` | Directory rules removed; the explicit `NativeAdPreloadConfigTest.kt` rule kept |

Decided, no change:

| Topic | Decision |
|-------|----------|
| AppsFlyer / Adjust start before the first UMP answer | Keep starting in `Application.onCreate` (same as the other libs); TCF is read by AppsFlyer once stored, Adjust/Firebase get consent via `onConsentUpdated` |

Still open (low risk or outside gma-lib):

| # | Gap | Impact | Suggested fix |
|---|-----|--------|---------------|
| 1 | Health events do not cover Google `*AdPreloader` APIs (resume app-open, `preloadRewardAd`) | No load-time data for those paths | The SDK exposes no per-request load callback there |
| 2 | Recovered (app-dead) **subscription** start revenue is not reported | Rare revenue gap | Server-side / Adjust S2S subscription tracking |
| 3 | Consumable bought with the **old** lib whose consume failed may be granted again once after updating | Rare double grant | Accept |
| 4 | `adlib` keeps its own older tracking code | Only apps still on adlib | Migrate them to gma-lib |
| 5 | Sample app has no `google-services.json` and no IAP screen | Firebase / IAP need a private test app | Add to a private test app |

## 10. 1.3.0 — stability changes

Unit tests: 62, incl. Robolectric `ConsumableDeliveryTest`. Device-only checks:

| # | Steps | Expected |
|---|-------|----------|
| 10.1 | Interstitial via `InterstitialAdHelper.waitLoadAndShow` with `finish()` in `onNextAction`; close the ad | Navigates **once**; DebugView shows the interstitial `ad_impression`; background → foreground shows the app-open resume ad again |
| 10.2 | Splash interstitial with no network / all ids failing | Splash continues **once**, no second navigation when the timeout would have fired |
| 10.3 | Rotate a screen holding a loaded but unshown interstitial | The next screen shows that ad without a new request (logcat: no second load) |
| 10.4 | Open a screen, wait > 55 min, show (or fake the clock) | Interstitial / rewarded / native / banner older than 55 min are reloaded, not shown |
| 10.5 | `waitLoadAndShow` on a slow network | Loading cover gone after ≤ 8 s; a late fill is shown on the next attempt |
| 10.6 | Consent-first app (`initializeAdsOnCreate() = false`), EEA debug geography, open a banner/interstitial screen before answering | No crash; ads load once consent is given, without the app calling `initializeIfNeeded()` |
| 10.7 | Buy a **consumable** with the "slow" test card, keep the app open until it completes | `setRecoveredConsumableListener` fires **once**; nothing again on the next launch |
| 10.8 | Cancel a subscription in Play, background the app > 1 min, return | Premium removed and ads back without killing the app |
| 10.9 | Buy remove-ads | Logcat: no further app-open / rewarded preload requests |
| 10.10 | Rewarded ad with "Don't keep activities" on; finish the ad | `onRewarded` + `onRewardedAdClosed` still reach listeners registered with `registerAdListener` |
| 10.11 | `adb shell am send-trim-memory <applicationId> BACKGROUND`, reopen a native/banner screen | No crash; ads reload |
| 10.12 | App with a secondary process (`android:process=":x"` service) | Adjust / AppsFlyer / Firebase / ads start only in the main process (one Adjust session) |
| 10.13 | Release build, logcat | No `paid:` revenue lines from `AdsLogEventManager`, no `AppOpenManager` lifecycle lines |
