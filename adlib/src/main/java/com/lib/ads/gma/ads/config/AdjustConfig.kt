package com.lib.ads.gma.ads.config

/**
 * Configuration class for Adjust SDK integration.
 *
 * @param enableAdjust Whether Adjust tracking is enabled.
 * @param adjustToken The Adjust SDK token.
 */
class AdjustConfig(
    var enableAdjust: Boolean = false,
    var adjustToken: String = ""
) {
    /**
     * Event name for purchase tracking in Adjust.
     */
    var eventNamePurchase: String = ""

    /**
     * Event token for ad impression value tracking.
     */
    var eventAdImpressionValue: String = ""

    /**
     * Event token for ad impression tracking.
     */
    var eventAdImpression: String = ""

    /**
     * Facebook App ID for cross-platform attribution.
     */
    var fbAppId: String = ""

}
