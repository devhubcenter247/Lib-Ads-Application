package com.lib.ads.gma.ads.event

import android.content.Context
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.AdjustPlayStoreSubscription
import com.lib.ads.gma.ads.config.AdjustConfig
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.util.AppLogger
import com.google.android.libraries.ads.mobile.sdk.common.AdValue

object AdsAdjust {
    private const val TAG = "AdsAdjust"
    private const val AD_REVENUE_SOURCE_ADMOB = "admob_sdk"

    @JvmField
    var enableAdjust: Boolean = false

    private val adjustConfig: AdjustConfig?
        get() = AdsProvider.getInstance().adConfigOrNull?.adjustConfig

    /** Adjust rejects an event without a token; a blank one means "not configured", not an error. */
    private fun String?.tokenOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

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
        val token = eventName.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token))
    }

    @JvmStatic
    fun onTrackEvent(eventName: String, id: String) {
        if (!enableAdjust) return
        val token = eventName.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token).apply { setCallbackId(id) })
    }

    @JvmStatic
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) {
        if (!enableAdjust) return
        val token = eventName.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token).apply { setRevenue(revenue.toDouble(), currency) })
    }

    @Deprecated(
        message = "Missing productId and purchaseToken — no server-side verification. Use the 4-param overload.",
        replaceWith = ReplaceWith("onTrackRevenuePurchase(revenue, currency, productId, purchaseToken)")
    )
    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String) {
        if (!enableAdjust) return
        val token = adjustConfig?.eventNamePurchase.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token).apply { setRevenue(revenue.toDouble(), currency) })
    }

    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String, productId: String, purchaseToken: String) =
        trackPurchase(revenue.toDouble(), currency, productId, purchaseToken, orderId = null)

    /**
     * One-time IAP revenue. Sent exactly once: either verified-and-tracked by Adjust, or tracked
     * directly — see [AdjustConfig.usePurchaseVerification]. The deduplication id lets Adjust drop
     * a repeat of the same order even if the client sends it again.
     */
    internal fun trackPurchase(revenue: Double, currency: String, productId: String, purchaseToken: String, orderId: String?) {
        if (!enableAdjust) return
        val config = adjustConfig
        val token = config?.eventNamePurchase.tokenOrNull() ?: run {
            AppLogger.w(TAG, "trackPurchase skipped: AdjustConfig.eventNamePurchase token is not set")
            return
        }
        val event = AdjustEvent(token).apply {
            setRevenue(revenue, currency)
            setProductId(productId)
            setPurchaseToken(purchaseToken)
            (orderId?.takeIf { it.isNotBlank() } ?: purchaseToken).let(::setDeduplicationId)
            addCallbackParameter("product_id", productId)
        }
        if (config?.usePurchaseVerification == true) {
            Adjust.verifyAndTrackPlayStorePurchase(event) { result ->
                AppLogger.d(TAG, "purchase verify: status=${result.verificationStatus} code=${result.code} message=${result.message}")
            }
        } else {
            Adjust.trackEvent(event)
        }
    }

    @Deprecated(
        message = "Uses the current time as purchase time. Pass the Purchase's purchaseTime.",
        replaceWith = ReplaceWith("onTrackRevenueSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken, purchaseTime)")
    )
    @JvmStatic
    fun onTrackRevenueSubscription(priceMicros: Long, currency: String, productId: String, orderId: String, signature: String, purchaseToken: String) =
        onTrackRevenueSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken, System.currentTimeMillis())

    @JvmStatic
    fun onTrackRevenueSubscription(
        priceMicros: Long,
        currency: String,
        productId: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long,
    ) {
        if (!enableAdjust) return
        val subscription = AdjustPlayStoreSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken).apply {
            setPurchaseTime(purchaseTime)
        }
        Adjust.trackPlayStoreSubscription(subscription)
        AppLogger.d(TAG, "trackPlayStoreSubscription: productId=$productId orderId=$orderId")
    }

    @JvmStatic
    fun onTrackImpression() {
        if (!enableAdjust) return
        val token = adjustConfig?.eventAdImpression.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token))
    }

    @JvmStatic
    fun onTrackClickAds() {
        if (!enableAdjust) return
        val token = adjustConfig?.eventAdClick.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token))
    }

    @JvmStatic
    fun pushTrackEvent(adValue: AdValue, adSourceName: String) {
        if (!enableAdjust) return
        Adjust.trackAdRevenue(AdjustAdRevenue(AD_REVENUE_SOURCE_ADMOB).apply {
            setRevenue(adValue.valueMicros / 1_000_000.0, adValue.currencyCode)
            setAdRevenueNetwork(adSourceName)
        })
    }

    internal fun trackAdRevenue(impression: PaidImpression) {
        if (!enableAdjust) return
        Adjust.trackAdRevenue(AdjustAdRevenue(AD_REVENUE_SOURCE_ADMOB).apply {
            setRevenue(impression.value, impression.currency)
            setAdRevenueNetwork(impression.adSourceName)
            impression.adUnitId.takeIf { it.isNotEmpty() }?.let(::setAdRevenueUnit)
            impression.placement.takeIf { it.isNotEmpty() }?.let(::setAdRevenuePlacement)
        })
    }

    @JvmStatic
    fun logPaidAdImpressionValue(revenue: Double, currency: String) {
        if (!enableAdjust) return
        val token = adjustConfig?.eventAdImpressionValue.tokenOrNull() ?: return
        Adjust.trackEvent(AdjustEvent(token).apply { setRevenue(revenue, currency) })
    }
}
