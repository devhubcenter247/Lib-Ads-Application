package com.lib.ads.gma.ads.config

/**
 * Configuration class for AppsFlyer SDK integration.
 *
 * @param enableAppsflyer Whether AppsFlyer tracking is enabled.
 * @param appsflyerToken The AppsFlyer dev key token.
 */
class AppsflyerConfig @JvmOverloads constructor(
    var enableAppsflyer: Boolean = false,
    var appsflyerToken: String = ""
) {
    /**
     * Event name for ad impression tracking in AppsFlyer.
     */
    var eventAdImpression: String = ""

    // Java compatibility
    fun isEnableAppsflyer(): Boolean = enableAppsflyer
}
