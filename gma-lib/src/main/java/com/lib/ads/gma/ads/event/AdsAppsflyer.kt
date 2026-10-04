package com.lib.ads.gma.ads.event

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.appsflyer.AFAdRevenueData
import com.appsflyer.AdRevenueScheme
import com.appsflyer.AppsFlyerLib
import com.appsflyer.MediationNetwork
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.util.AppLogger
import java.util.Locale

object AdsAppsflyer {
    private const val TAG = "AdsAppsflyer"

    @Volatile
    private var connected = false

    @JvmField
    var enableAppsflyer: Boolean = false

    @JvmField
    var events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT

    @JvmStatic
    fun connect(
        application: Application,
        devKey: String,
        debug: Boolean = false,
        events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT,
        alreadyInitialized: Boolean = false,
    ) {
        if (connected) return
        if (devKey.isBlank() && !alreadyInitialized) return
        connected = true
        enableAppsflyer = true
        this.events = events
        val appsflyer = AppsFlyerLib.getInstance()
        appsflyer.setDebugLog(debug)
        if (!alreadyInitialized) {
            appsflyer.init(devKey, null, application)
            appsflyer.start(application)
        }
    }

    @JvmStatic
    fun connectOnly(events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT) {
        connected = true
        enableAppsflyer = true
        this.events = events
    }

    @JvmStatic
    fun logPaidAdImpression(
        context: Context,
        adValue: AdValue,
        responseInfo: ResponseInfo,
        adType: AdType,
    ) {
        if (!enableAppsflyer || AdsTrackEvent.AD_REVENUE !in events) return
        val loadedSource = responseInfo.loadedAdSourceResponseInfo
        val revenue = adValue.valueMicros / 1_000_000.0
        runCatching {
            AppsFlyerLib.getInstance().logAdRevenue(
                AFAdRevenueData(
                    loadedSource?.name?.takeIf { it.isNotBlank() } ?: "admob",
                    MediationNetwork.GOOGLE_ADMOB,
                    adValue.currencyCode,
                    revenue,
                ),
                mapOf(
                    AdRevenueScheme.AD_UNIT to responseInfo.extractAdUnitIdOrNull().orEmpty(),
                    AdRevenueScheme.AD_TYPE to adType.name.lowercase(Locale.US),
                    AdRevenueScheme.PLACEMENT to (loadedSource?.adapterClassName ?: ""),
                ),
            )
        }.onFailure {
            AppLogger.w(TAG, "logPaidAdImpression failed: ${it.message}")
        }
    }

    @JvmStatic
    fun logEvent(context: Context, eventName: String, params: Bundle? = null) {
        if (!enableAppsflyer || eventName.isBlank()) return
        val event = AdsTrackEvent.of(eventName) ?: return
        if (event !in events) return
        AppsFlyerLib.getInstance().logEvent(context, eventName, params.toAfParams())
    }

    @JvmStatic
    fun logPurchase(
        context: Context,
        revenue: Double,
        currency: String,
        productId: String,
    ) {
        if (!enableAppsflyer || AdsTrackEvent.PURCHASE !in events) return
        AppsFlyerLib.getInstance().logEvent(
            context,
            "af_purchase",
            mapOf(
                "af_revenue" to revenue,
                "af_currency" to currency,
                "af_content_id" to productId,
            ),
        )
    }

    @JvmStatic
    fun logPurchase(
        revenue: Double,
        currency: String,
        productId: String,
    ) {
        val context = Ads.getInstance().applicationContextOrNull() ?: return
        logPurchase(context, revenue, currency, productId)
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.toAfParams(): Map<String, Any> =
        this?.keySet()?.mapNotNull { key -> get(key)?.let { key to it } }?.toMap().orEmpty()
}
