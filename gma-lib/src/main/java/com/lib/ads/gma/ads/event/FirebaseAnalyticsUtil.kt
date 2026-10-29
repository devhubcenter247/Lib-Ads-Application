package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

object FirebaseAnalyticsUtil {
    private const val TAG = "FirebaseAnalyticsUtil"

    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null

    @JvmStatic
    fun init(context: Context) {
        firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)
    }

    @JvmStatic
    fun logEventWithAds(context: Context, params: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression", params)

    /** GA4 reserved `ad_impression` with value/currency — the event the Ad revenue card reads. */
    internal fun logAdImpression(context: Context, impression: PaidImpression) =
        FirebaseAnalytics.getInstance(context)
            .logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, impression.firebaseAdImpressionParams().toBundle())

    @Deprecated(
        message = "Logs ad_impression without value/currency, which GA4 counts as a zero-revenue impression. " +
            "The SDK now logs a complete ad_impression from every paid callback.",
    )
    @JvmStatic
    fun logAdImpression(context: Context) =
        FirebaseAnalytics.getInstance(context).logEvent("ad_impression", null)

    @JvmStatic
    fun logPaidAdImpressionValue(context: Context, bundle: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression_value", bundle)

    @JvmStatic
    fun logClickAdsEvent(context: Context, bundle: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent("event_user_click_ads", bundle)

    @JvmStatic
    fun logCurrentTotalRevenueAd(context: Context, eventName: String, bundle: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)

    @JvmStatic
    fun logEventTracking(context: Context, eventName: String, bundle: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)

    @JvmStatic
    fun logTotalRevenue001Ad(context: Context, bundle: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression_value_001", bundle)

    /**
     * The purchase token is a credential for Google Play Developer API calls; it no longer goes to
     * analytics. [purchaseToken] is kept in the signature for source compatibility only.
     */
    @JvmStatic
    fun logConfirmPurchaseGoogle(orderId: String, purchaseId: String, @Suppress("UNUSED_PARAMETER") purchaseToken: String) {
        val analytics = firebaseAnalytics ?: run {
            Log.w(TAG, "logConfirmPurchaseGoogle skipped: init(context) was not called")
            return
        }
        analytics.logEvent("confirm_purchased_with_google", Bundle().apply {
            putString("purchase_order_id", orderId)
            putString("purchase_package_id", purchaseId)
        })
        Log.d(TAG, "logConfirmPurchaseGoogle: tracked")
    }

    @JvmStatic
    fun logRevenuePurchase(value: Double) {
        val analytics = firebaseAnalytics ?: run {
            Log.w(TAG, "logRevenuePurchase skipped: init(context) was not called")
            return
        }
        analytics.logEvent("user_purchased_value", Bundle().apply {
            putDouble("value", value)
            putString("currency", DEFAULT_CURRENCY)
        })
    }
}
