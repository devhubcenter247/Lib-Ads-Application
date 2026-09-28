package com.lib.ads.gma.ads.config

import android.app.Application
import com.google.android.gms.ads.RequestConfiguration

/**
 * Main configuration class for the ad SDK.
 *
 * @param application The application instance.
 * @param environment Optional environment setting (ENVIRONMENT_DEVELOP or ENVIRONMENT_PRODUCTION).
 */
class AdSdkConfig @JvmOverloads constructor(
    val application: Application,
    environment: String? = null
) {
    companion object {
        const val ENVIRONMENT_DEVELOP = "develop"
        const val ENVIRONMENT_PRODUCTION = "production"
        const val DEFAULT_TOKEN_FACEBOOK_SDK = "client_token"
    }

    /**
     * Whether the current build is a development variant.
     */
    private var _isVariantDev: Boolean = environment == ENVIRONMENT_DEVELOP

    /**
     * Number of times ads can be reloaded.
     */
    var numberOfTimesReloadAds: Int = 0

    /**
     * Adjust SDK configuration.
     */
    var adjustConfig: AdjustConfig? = null

    /**
     * UMP consent configuration.
     */
    var adsConsentConfig: AdsConsentConfig? = null

    /**
     * AppsFlyer SDK configuration.
     */
    var appsflyerConfig: AppsflyerConfig? = null

    /**
     * Event name for purchase tracking.
     */
    var eventNamePurchase: String = ""

    /**
     * Single ad unit ID for app open/resume ads.
     */
    var idAdResume: String? = null
        set(value) {
            field = value
            if (value != null) _enableAdResume = true
        }

    /**
     * List of ad unit IDs for app open/resume ads (fallback support).
     */
    var listIdAdResume: List<String>? = null
        set(value) {
            field = value
            if (value != null) _enableAdResume = true
        }

    /**
     * List of test device IDs for ad testing.
     */
    var listDeviceTest: List<String> = emptyList()

    /**
     * Child-directed treatment tag. Use [RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE],
     * [RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE], or leave null (unspecified).
     */
    var tagForChildDirectedTreatment: Int? = null

    /**
     * Under-age-of-consent tag. Use [RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE],
     * [RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE], or leave null (unspecified).
     */
    var tagForUnderAgeOfConsent: Int? = null

    /**
     * Maximum ad content rating. Use [RequestConfiguration.MAX_AD_CONTENT_RATING_G],
     * [RequestConfiguration.MAX_AD_CONTENT_RATING_PG], [RequestConfiguration.MAX_AD_CONTENT_RATING_T],
     * [RequestConfiguration.MAX_AD_CONTENT_RATING_MA], or leave null (unspecified).
     */
    var maxAdContentRating: String? = null

    /**
     * Publisher privacy personalization state. Use
     * [RequestConfiguration.PublisherPrivacyPersonalizationState.ENABLED],
     * [RequestConfiguration.PublisherPrivacyPersonalizationState.DISABLED], or leave null (default).
     */
    var publisherPrivacyPersonalizationState: RequestConfiguration.PublisherPrivacyPersonalizationState? = null

    /**
     * Whether app open/resume ads are enabled.
     */
    private var _enableAdResume: Boolean = false

    /**
     * Facebook client token for SDK initialization.
     */
    var facebookClientToken: String = DEFAULT_TOKEN_FACEBOOK_SDK

    /**
     * Minimum interval (in seconds) between interstitial ad impressions.
     */
    var intervalInterstitialAd: Int = 0

    /**
     * Sets the environment (develop or production).
     */
    fun setEnvironment(environment: String) {
        _isVariantDev = environment == ENVIRONMENT_DEVELOP
    }

    /**
     * Checks if AppsFlyer is enabled.
     */
    fun isEnableAppsflyer(): Boolean {
        return appsflyerConfig?.enableAppsflyer ?: false
    }

    // Java compatibility methods - matching original Java signatures
    fun isVariantDev(): Boolean = _isVariantDev
    fun isEnableAdResume(): Boolean = _enableAdResume
}
