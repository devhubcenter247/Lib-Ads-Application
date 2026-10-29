package com.lib.ads.gma.ads.event

import android.os.SystemClock
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.model.AdType
import java.util.Locale

/**
 * Opt-in ad health events ([com.lib.ads.gma.ads.config.AdSdkConfig.logAdHealthEvents]):
 * `ad_load_time` for every finished request, `ad_load_failed` with the SDK error, and
 * `ad_show_failed`. Per ad unit, not per waterfall, so fill rate and latency are visible per id.
 */
internal object AdHealthEvents {
    const val EVENT_AD_LOAD_TIME = "ad_load_time"
    const val EVENT_AD_LOAD_FAILED = "ad_load_failed"
    const val EVENT_AD_SHOW_FAILED = "ad_show_failed"
    private const val TAG = "AdHealthEvents"

    /** GA4 rejects string params longer than 100 characters. */
    private const val MAX_PARAM_LENGTH = 100

    private val enabled: Boolean
        get() = AdsProvider.getInstance().adConfigOrNull?.logAdHealthEvents == true

    /** Monotonic start mark for [loadFinished]. */
    fun now(): Long = SystemClock.elapsedRealtime()

    /** @param error null on success. */
    fun loadFinished(adType: AdType, adUnitId: String, startedAt: Long, error: LoadAdError?) {
        if (!enabled) return
        val elapsed = (now() - startedAt).coerceAtLeast(0L)
        log(EVENT_AD_LOAD_TIME, loadTimeParams(adType, adUnitId, elapsed, success = error == null))
        if (error != null) {
            log(EVENT_AD_LOAD_FAILED, loadFailedParams(adType, adUnitId, error.code.value, error.code.name, error.message))
        }
    }

    fun showFailed(adType: AdType, adUnitId: String, message: String?) {
        if (!enabled) return
        log(EVENT_AD_SHOW_FAILED, showFailedParams(adType, adUnitId, message))
    }

    fun loadTimeParams(adType: AdType, adUnitId: String, elapsedMs: Long, success: Boolean): Map<String, Any> = mapOf(
        "ad_format" to adType.format(),
        "ad_unit_name" to adUnitId.clip(),
        "load_time_ms" to elapsedMs,
        "success" to if (success) 1L else 0L,
    )

    fun loadFailedParams(adType: AdType, adUnitId: String, code: Int, codeName: String, message: String?): Map<String, Any> = mapOf(
        "ad_format" to adType.format(),
        "ad_unit_name" to adUnitId.clip(),
        "error_code" to code.toLong(),
        "error_name" to codeName.clip(),
        "error_message" to message.orEmpty().clip(),
    )

    fun showFailedParams(adType: AdType, adUnitId: String, message: String?): Map<String, Any> = mapOf(
        "ad_format" to adType.format(),
        "ad_unit_name" to adUnitId.clip(),
        "error_message" to message.orEmpty().clip(),
    )

    private fun AdType.format() = name.lowercase(Locale.US)

    private fun String.clip() = take(MAX_PARAM_LENGTH)

    private fun log(name: String, params: Map<String, Any>) {
        val context = AdsProvider.getInstance().applicationContextOrNull() ?: return
        val bundle = params.toBundle()
        try {
            FirebaseAnalyticsUtil.logEventTracking(context, name, bundle)
            AdsAppsflyer.logEvent(context, name, bundle)
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
        }
    }
}
