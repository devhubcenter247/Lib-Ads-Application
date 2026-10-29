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
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.util.AppLogger

object AdsAppsflyer {
    private const val TAG = "AdsAppsflyer"
    const val CONTENT_TYPE_INAPP = "inapp"
    const val CONTENT_TYPE_SUBS = "subs"

    @Volatile
    private var connected = false

    @JvmField
    var enableAppsflyer: Boolean = false

    @JvmField
    var events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT

    @JvmStatic
    @JvmOverloads
    fun connect(
        application: Application,
        devKey: String,
        debug: Boolean = false,
        events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT,
        alreadyInitialized: Boolean = false,
        enableTCFDataCollection: Boolean = true,
    ) {
        if (connected) return
        if (devKey.isBlank() && !alreadyInitialized) return
        connected = true
        enableAppsflyer = true
        this.events = events
        val appsflyer = AppsFlyerLib.getInstance()
        appsflyer.setDebugLog(debug)
        if (!alreadyInitialized) {
            // Must precede start(): AppsFlyer reads the TCF string stored by UMP on launch.
            if (enableTCFDataCollection) appsflyer.enableTCFDataCollection(true)
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
    ) = logPaidAdImpression(
        PaidImpression.from(adValue, responseInfo, adType, responseInfo.extractAdUnitIdOrNull().orEmpty(), 0L)
    )

    internal fun logPaidAdImpression(impression: PaidImpression) {
        if (!enableAppsflyer || AdsTrackEvent.AD_REVENUE !in events) return
        runCatching {
            AppsFlyerLib.getInstance().logAdRevenue(
                AFAdRevenueData(
                    impression.adSourceName,
                    MediationNetwork.GOOGLE_ADMOB,
                    impression.currency,
                    impression.value,
                ),
                mapOf(
                    AdRevenueScheme.AD_UNIT to impression.adUnitId,
                    AdRevenueScheme.AD_TYPE to impression.adFormat,
                    AdRevenueScheme.PLACEMENT to impression.placement,
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
    ) = logPurchase(context, revenue, currency, productId, CONTENT_TYPE_INAPP, orderId = null)

    /** In-app products and subscriptions both land on `af_purchase`, told apart by `af_content_type`. */
    @JvmStatic
    fun logPurchase(
        context: Context,
        revenue: Double,
        currency: String,
        productId: String,
        contentType: String,
        orderId: String?,
    ) {
        if (!enableAppsflyer || AdsTrackEvent.PURCHASE !in events) return
        val values = buildMap<String, Any> {
            put("af_revenue", revenue)
            put("af_currency", currency)
            put("af_content_id", productId)
            put("af_content_type", contentType)
            orderId?.takeIf { it.isNotBlank() }?.let { put("af_order_id", it) }
        }
        AppsFlyerLib.getInstance().logEvent(context, "af_purchase", values)
    }

    @JvmStatic
    fun logPurchase(
        revenue: Double,
        currency: String,
        productId: String,
    ) {
        val context = AdsProvider.getInstance().applicationContextOrNull() ?: return
        logPurchase(context, revenue, currency, productId)
    }

    internal fun logPurchase(revenue: Double, currency: String, productId: String, contentType: String, orderId: String?) {
        val context = AdsProvider.getInstance().applicationContextOrNull() ?: return
        logPurchase(context, revenue, currency, productId, contentType, orderId)
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.toAfParams(): Map<String, Any> =
        this?.keySet()?.mapNotNull { key -> get(key)?.let { key to it } }?.toMap().orEmpty()
}
