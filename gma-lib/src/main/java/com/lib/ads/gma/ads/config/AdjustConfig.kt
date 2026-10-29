package com.lib.ads.gma.ads.config

/**
 * Adjust setup. Every `event*` field is an Adjust **event token** (6 characters, created in the
 * Adjust dashboard), not an event name — a blank token disables that event.
 */
class AdjustConfig(
    var enableAdjust: Boolean = false,
    var adjustToken: String = ""
) {
    /** One-time IAP revenue event. */
    var eventNamePurchase: String = ""

    /**
     * Opt-in per-impression revenue event. Ad revenue already reaches Adjust through
     * `trackAdRevenue`; only set this when the dashboard does not also sum ad revenue, or the
     * revenue is counted twice.
     */
    var eventAdImpressionValue: String = ""
    var eventAdImpression: String = ""
    var eventAdClick: String = ""
    var fbAppId: String = ""

    /**
     * `true`: IAP goes through `Adjust.verifyAndTrackPlayStorePurchase` (Adjust tracks the event
     * itself once Google verifies it — requires Purchase Verification enabled on the Adjust account).
     * `false`: plain `Adjust.trackEvent`. Never both — that counted every purchase twice.
     */
    var usePurchaseVerification: Boolean = false
}
