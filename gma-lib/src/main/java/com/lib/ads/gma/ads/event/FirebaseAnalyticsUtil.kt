package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

object FirebaseAnalyticsUtil {
    private const val TAG = "FirebaseAnalyticsUtil"
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    @JvmStatic
    fun init(context: Context) {
        firebaseAnalytics = FirebaseAnalytics.getInstance(context)
    }

    @JvmStatic
    fun logEventWithAds(context: Context, params: Bundle) =
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression", params)

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

    @JvmStatic
    fun logConfirmPurchaseGoogle(orderId: String, purchaseId: String, purchaseToken: String) {
        val (tokenPart1, tokenPart2) = if (purchaseToken.length > 100) {
            purchaseToken.take(100) to purchaseToken.substring(100)
        } else {
            purchaseToken to "EMPTY"
        }
        firebaseAnalytics.logEvent("confirm_purchased_with_google", Bundle().apply {
            putString("purchase_order_id", orderId)
            putString("purchase_package_id", purchaseId)
            putString("purchase_token_part_1", tokenPart1)
            putString("purchase_token_part_2", tokenPart2)
        })
        Log.d(TAG, "logConfirmPurchaseGoogle: tracked")
    }

    @JvmStatic
    fun logRevenuePurchase(value: Double) {
        firebaseAnalytics.logEvent("user_purchased_value", Bundle().apply {
            putDouble("value", value)
            putString("currency", "USD")
        })
    }
}
