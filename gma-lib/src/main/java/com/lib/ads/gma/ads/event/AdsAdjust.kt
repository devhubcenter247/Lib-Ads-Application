package com.lib.ads.gma.ads.event

import android.content.Context
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.AdjustPlayStoreSubscription
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.util.AppLogger
import com.google.android.libraries.ads.mobile.sdk.common.AdValue

object AdsAdjust {
    private const val TAG = "AdsAdjust"

    @JvmField
    var enableAdjust: Boolean = false

    @JvmStatic
    fun trackAdRevenue(id: String) {
        if (!enableAdjust) return
        Adjust.trackAdRevenue(AdjustAdRevenue(id))
    }

    @JvmStatic
    fun pushTrackTokenFcm(token: String, context: Context) {
        if (!enableAdjust) return
        Adjust.setPushToken(token, context)
    }

    @JvmStatic
    fun onTrackEvent(eventName: String) {
        if (!enableAdjust) return
        Adjust.trackEvent(AdjustEvent(eventName))
    }

    @JvmStatic
    fun onTrackEvent(eventName: String, id: String) {
        if (!enableAdjust) return
        Adjust.trackEvent(AdjustEvent(eventName).apply { setCallbackId(id) })
    }

    @JvmStatic
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) {
        if (!enableAdjust) return
        Adjust.trackEvent(AdjustEvent(eventName).apply { setRevenue(revenue.toDouble(), currency) })
    }

    @Deprecated(
        message = "Missing productId and purchaseToken — no server-side verification. Use the 4-param overload.",
        replaceWith = ReplaceWith("onTrackRevenuePurchase(revenue, currency, productId, purchaseToken)")
    )
    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String) {
        if (!enableAdjust) return
        val eventName = Ads.getInstance().adConfig.adjustConfig?.eventNamePurchase ?: return
        onTrackRevenue(eventName, revenue, currency)
    }

    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String, productId: String, purchaseToken: String) {
        if (!enableAdjust) return
        val eventName = Ads.getInstance().adConfig.adjustConfig?.eventNamePurchase ?: return
        val event = AdjustEvent(eventName).apply {
            setRevenue(revenue.toDouble(), currency)
            setProductId(productId)
            setPurchaseToken(purchaseToken)
        }
        Adjust.trackEvent(event)
        Adjust.verifyAndTrackPlayStorePurchase(event) { result ->
            AppLogger.d(TAG, "purchase verify: status=${result.verificationStatus} code=${result.code} message=${result.message}")
        }
    }

    @JvmStatic
    fun onTrackRevenueSubscription(priceMicros: Long, currency: String, productId: String, orderId: String, signature: String, purchaseToken: String) {
        if (!enableAdjust) return
        val subscription = AdjustPlayStoreSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken).apply {
            setPurchaseTime(System.currentTimeMillis())
        }
        Adjust.trackPlayStoreSubscription(subscription)
        AppLogger.d(TAG, "trackPlayStoreSubscription: productId=$productId orderId=$orderId")
    }

    @JvmStatic
    fun onTrackImpression() {
        if (!enableAdjust) return
        val eventName = Ads.getInstance().adConfig.adjustConfig?.eventAdImpression?.takeIf { it.isNotEmpty() } ?: return
        Adjust.trackEvent(AdjustEvent(eventName))
    }

    @JvmStatic
    fun onTrackClickAds() {
        if (!enableAdjust) return
        val eventName = Ads.getInstance().adConfig.adjustConfig?.eventAdClick?.takeIf { it.isNotEmpty() } ?: return
        Adjust.trackEvent(AdjustEvent(eventName))
    }

    @JvmStatic
    fun pushTrackEvent(adValue: AdValue, adSourceName: String) {
        if (!enableAdjust) return
        Adjust.trackAdRevenue(AdjustAdRevenue("admob_sdk").apply {
            setRevenue(adValue.valueMicros / 1_000_000.0, adValue.currencyCode)
            setAdRevenueNetwork(adSourceName)
        })
    }

    @JvmStatic
    fun logPaidAdImpressionValue(revenue: Double, currency: String) {
        if (!enableAdjust) return
        val eventName = Ads.getInstance().adConfig.adjustConfig?.eventAdImpressionValue ?: return
        Adjust.trackEvent(AdjustEvent(eventName).apply { setRevenue(revenue, currency) })
    }
}
