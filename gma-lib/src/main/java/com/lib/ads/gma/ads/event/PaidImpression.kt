package com.lib.ads.gma.ads.event

import android.os.Bundle
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.lib.ads.gma.ads.model.AdType
import java.util.Locale

/**
 * One paid impression, normalized once so every destination (Firebase, Adjust, AppsFlyer, Meta)
 * reports the same unit, source, format and currency.
 */
internal data class PaidImpression(
    val valueMicros: Long,
    val currency: String,
    val precision: Int,
    val adUnitId: String,
    /** Human-readable network that filled, e.g. "AdMob Network", "Meta Audience Network". */
    val adSourceName: String,
    val adapterClassName: String?,
    val adType: AdType,
    /** Ad Placements id, 0 when unset. */
    val placementId: Long,
) {
    val value: Double get() = valueMicros / 1_000_000.0
    val adFormat: String get() = adType.name.lowercase(Locale.US)
    val placement: String get() = if (placementId > 0L) placementId.toString() else adUnitId

    /** GA4 reserved `ad_impression` — these params feed the Ad revenue card, LTV and ROAS. */
    fun firebaseAdImpressionParams(): Map<String, Any> = mapOf(
        "ad_platform" to AD_PLATFORM,
        "ad_source" to adSourceName,
        "ad_format" to adFormat,
        "ad_unit_name" to adUnitId,
        "value" to value,
        "currency" to currency,
    )

    /** Legacy `paid_ad_impression` params; names kept so existing dashboards keep working. */
    fun paidAdImpressionParams(): Map<String, Any?> = mapOf(
        "valuemicros" to valueMicros.toDouble(),
        "currency" to currency,
        "precision" to precision,
        "adunitid" to adUnitId,
        "network" to adapterClassName,
    )

    /** Legacy `paid_ad_impression_value` params. */
    fun paidAdImpressionValueParams(): Map<String, Any?> = mapOf(
        "value" to value,
        "currency" to currency,
        "precision" to precision,
        "adunitid" to adUnitId,
        "network" to adapterClassName,
    )

    companion object {
        const val AD_PLATFORM = "admob"

        fun precisionOf(type: PrecisionType?): Int = when (type) {
            PrecisionType.ESTIMATED -> 1
            PrecisionType.PUBLISHER_PROVIDED -> 2
            PrecisionType.PRECISE -> 3
            else -> 0
        }

        fun from(
            adValue: AdValue,
            responseInfo: ResponseInfo,
            adType: AdType,
            adUnitId: String,
            placementId: Long,
        ): PaidImpression {
            val loadedSource = responseInfo.loadedAdSourceResponseInfo
            return PaidImpression(
                valueMicros = adValue.valueMicros,
                currency = adValue.currencyCode.ifBlank { DEFAULT_CURRENCY },
                precision = precisionOf(adValue.precisionType),
                adUnitId = adUnitId,
                adSourceName = loadedSource?.name?.takeIf { it.isNotBlank() } ?: "AdMob",
                adapterClassName = loadedSource?.adapterClassName ?: responseInfo.adapterClassName,
                adType = adType,
                placementId = placementId,
            )
        }
    }
}

internal const val DEFAULT_CURRENCY = "USD"

internal fun Map<String, Any?>.toBundle(): Bundle = Bundle().also { bundle ->
    forEach { (key, value) ->
        when (value) {
            null -> bundle.putString(key, null)
            is String -> bundle.putString(key, value)
            is Double -> bundle.putDouble(key, value)
            is Float -> bundle.putFloat(key, value)
            is Long -> bundle.putLong(key, value)
            is Int -> bundle.putInt(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
}
