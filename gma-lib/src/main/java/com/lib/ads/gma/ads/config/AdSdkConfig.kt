package com.lib.ads.gma.ads.config

import android.app.Application
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.AdapterInitializationConfig

/** Configuration matching the important adlib fields without classic GMA types. */
@OptIn(ExperimentalApi::class)
class AdSdkConfig(
    val application: Application,
    environment: String = ENVIRONMENT_PRODUCTION,
) {
    companion object {
        const val ENVIRONMENT_DEVELOP = "develop"
        const val ENVIRONMENT_PRODUCTION = "production"
    }

    var appAdId: String? = null
    var listDeviceTest: List<String> = emptyList()

    /** Full GMA request configuration. Takes precedence over [listDeviceTest]. */
    var requestConfiguration: RequestConfiguration? = null
    var idAdResume: String? = null
    var listIdAdResume: List<String>? = null

    /** Ad Placements id applied to every resume app-open request built from [idAdResume]/[listIdAdResume]. */
    var placementIdAdResume: Long? = null

    var isDisableValidator: Boolean = false

    /**
     * Splash app-open ad unit id(s) to start preloading as soon as SDK init finishes — before
     * the app's `SplashActivity` even reaches `onCreate`. Purely a warm-up: the splash screen
     * still calls `AdsManager.loadSplashAppOpenAd(s)`/`AppOpenAdManager.loadSplashAppOpenAd(s)`
     * itself to get the actual show/timeout/callback flow; starting the preload here just means
     * that call is far more likely to find an already-warm ad on its very first poll, shaving
     * real time off a new user's time-to-first-ad. Safe to leave unset — nothing else changes.
     */
    var splashAdId: String? = null
    var splashAdIdList: List<String>? = null

    /** Ad Placements id applied to the early splash preload started from [splashAdId]/[splashAdIdList]. */
    var placementIdSplash: Long? = null

    /**
     * When `true`, the early splash preload started from [splashAdId]/[splashAdIdList] requests
     * `skipUninitializedAdapters()` (see [adapterInitializationConfig]) — the splash ad request
     * goes out as soon as the adapter(s) it actually needs are ready, instead of waiting for
     * every mediation adapter to finish initializing. Only meaningful together with a narrowed
     * [adapterInitializationConfig] (e.g. `setAllowedAdFormats(setOf(AdFormat.APP_OPEN_AD))`);
     * left `false` by default since it changes mediation init ordering and should be A/B'd.
     */
    var skipUninitializedAdaptersForSplash: Boolean = false
    /**
     * Logs the GA4 reserved `ad_impression` (value, currency, ad_platform, ad_source, ad_format,
     * ad_unit_name) from every paid callback. Turn off only when the AdMob app is linked to Firebase
     * with automatic ad_impression collection — check DebugView first, otherwise revenue doubles.
     */
    var logFirebaseAdImpression: Boolean = true

    /**
     * Forwards the UMP/TCF decision to Firebase Consent Mode v2 and Adjust (Google DMA) whenever
     * consent is resolved or changes. Does nothing outside the EEA flow (no TCF data stored).
     */
    var propagateConsent: Boolean = true

    /**
     * Development builds only (ignored unless [isVariantDev]): UMP debug geography, e.g.
     * `ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA`, to test the consent flow without a VPN.
     */
    var consentDebugGeography: Int? = null

    /**
     * Development builds only: hashed device ids UMP treats as test devices. UMP prints the id
     * in logcat ("Use new ConsentDebugSettings.Builder().addTestDeviceHashedId(...)") on first run.
     */
    var consentTestDeviceHashedIds: List<String> = emptyList()

    /**
     * Logs `event_current_total_revenue_ad` (lifetime ad revenue) after every paid impression —
     * the legacy behaviour, kept on by default so existing dashboards do not break. It is the
     * noisiest event the SDK sends; turn it off once no report reads it.
     */
    var logTotalRevenueEachImpression: Boolean = true

    /**
     * Pause between putting up the loading cover and calling `show()` on an interstitial. Lower it
     * to show faster; the cover hides the screen transition behind the ad.
     */
    var interstitialShowDelayMs: Long = 800L

    /**
     * Opt-in ad health events to Firebase (and AppsFlyer when listed in its events):
     * `ad_load_time` per request, `ad_load_failed`, `ad_show_failed`. Useful to tune waterfall
     * timeouts and watch fill rate; adds roughly one event per ad request.
     */
    var logAdHealthEvents: Boolean = false

    /**
     * Opt-in Firebase `purchase` for IAP revenue. Leave off when Firebase is linked to Google Play:
     * GA4 already records `in_app_purchase` automatically and both would be summed as revenue.
     */
    var logFirebasePurchase: Boolean = false

    /** Opt-in `Daily_Ads_Revenue` milestone (Azura "Taichi" tROAS event). Null = off. */
    var dailyAdsRevenue: DailyAdsRevenueConfig? = null
    var adjustConfig: AdjustConfig? = null
    var appsflyerConfig: AppsflyerConfig? = null
    var adapterInitializationConfig: AdapterInitializationConfig? = null

    /** Shows debug notices for test ad units in development builds. */
    var showMessageForTester: Boolean = false

    /** Enables verbose SDK logs (ads + billing). Applied by AdsMultiDexApplication on create. */
    var enableLog: Boolean = false

    /** Runtime fullscreen state shared by app-open and fullscreen ad flows. */
    @Volatile
    var fullScreenAdShowing: Boolean = false

    var isVariantDev: Boolean = environment == ENVIRONMENT_DEVELOP

    fun setEnvironment(value: String) {
        isVariantDev = value == ENVIRONMENT_DEVELOP
    }

    fun isEnableAdResume(): Boolean =
        !idAdResume.isNullOrBlank() || !listIdAdResume.isNullOrEmpty()
}
