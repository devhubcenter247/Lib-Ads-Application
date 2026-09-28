package com.lib.ads.gma.ads.config

/**
 * Configuration class for UMP (User Messaging Platform) consent management.
 *
 * @param enableUMP Whether UMP consent is enabled.
 */
class AdsConsentConfig(
    val enableUMP: Boolean
) {
    /**
     * Whether to enable debug mode for consent testing.
     */
    var isEnableDebug: Boolean = false

    /**
     * Test device ID for consent debugging.
     */
    var testDevice: String = ""

    /**
     * Whether to reset consent data on each app launch (for testing).
     */
    var isResetData: Boolean = false
}
