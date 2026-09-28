package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Utility object for Firebase Analytics event logging.
 */
object FirebaseAnalytics {
    private const val TAG = "FirebaseAnalyticsUtil"

    private var firebaseAnalytics: FirebaseAnalytics? = null

    
    fun init(context: Context) {
        firebaseAnalytics = FirebaseAnalytics.getInstance(context)
    }

    
    fun logEventWithAds(context: Context, params: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression", params)
    }
    fun logPaidAdImpressionValue(context: Context, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression_value", bundle)
    }

    
    fun logClickAdsEvent(context: Context, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("event_user_click_ads", bundle)
    }

    
    fun logCurrentTotalRevenueAd(context: Context, eventName: String, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)
    }

    
    fun logEventTracking(context: Context, eventName: String, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent(eventName, bundle)
    }

    
    fun logTotalRevenue001Ad(context: Context, bundle: Bundle) {
        FirebaseAnalytics.getInstance(context).logEvent("paid_ad_impression_value_001", bundle)
    }

    
    fun logConfirmPurchaseGoogle(
        orderId: String?,
        purchaseId: String,
        purchaseToken: String
    ) {
        val (tokenPart1, tokenPart2) = if (purchaseToken.length > 100) {
            purchaseToken.substring(0, 100) to purchaseToken.substring(100)
        } else {
            purchaseToken to "EMPTY"
        }

        val bundle = Bundle().apply {
            putString("purchase_order_id", orderId)
            putString("purchase_package_id", purchaseId)
            putString("purchase_token_part_1", tokenPart1)
            putString("purchase_token_part_2", tokenPart2)
        }

        firebaseAnalytics?.logEvent("confirm_purchased_with_google", bundle)
        Log.d(TAG, "logConfirmPurchaseGoogle: tracked")
    }

    
    fun logRevenuePurchase(value: Double) {
        val bundle = Bundle().apply {
            putDouble(FirebaseAnalytics.Param.VALUE, value)
            putString(FirebaseAnalytics.Param.CURRENCY, "USD")
        }
        firebaseAnalytics?.logEvent("user_purchased_value", bundle)
    }
}
