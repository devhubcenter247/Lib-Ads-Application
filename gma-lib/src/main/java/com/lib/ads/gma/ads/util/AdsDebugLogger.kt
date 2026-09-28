package com.lib.ads.gma.ads.util

/** Centralized lifecycle logger; enabled through Ads.isEnableAdsLog. */
object AdsDebugLogger {
    fun state(format: String, state: Any?) {
        AppLogger.d("Ads/$format", state?.toString() ?: "null")
    }
}
