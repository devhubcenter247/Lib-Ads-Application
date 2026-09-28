package com.lib.ads.gma.ads.event

import android.content.Context
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.lib.ads.gma.ads.manager.AdSdkInitializer
import com.google.android.gms.ads.AdValue

/**
 * Utility object for Adjust SDK event tracking.
 */
object AdAdjust {

    
    fun trackAdRevenue(id: String) {
        val adjustAdRevenue = AdjustAdRevenue(id)
        Adjust.trackAdRevenue(adjustAdRevenue)
    }

    
    fun pushTrackTokenFcm(token: String, context: Context) {
        Adjust.setPushToken(token, context)
    }

    
    fun onTrackEvent(eventName: String) {
        val event = AdjustEvent(eventName)
        Adjust.trackEvent(event)
    }

    
    fun onTrackEvent(eventName: String, id: String) {
        val event = AdjustEvent(eventName).apply {
            setCallbackId(id)
        }
        Adjust.trackEvent(event)
    }

    
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) {
        val event = AdjustEvent(eventName).apply {
            setRevenue(revenue.toDouble(), currency)
        }
        Adjust.trackEvent(event)
    }

    
    fun onTrackRevenuePurchase(revenue: Float, currency: String) {
        val eventName = AdSdkInitializer.adConfig?.adjustConfig?.eventNamePurchase ?: return
        onTrackRevenue(eventName, revenue, currency)
    }

    
    fun onTrackImpression() {
        val eventName = AdSdkInitializer.adConfig?.adjustConfig?.eventAdImpression
        if (eventName.isNullOrEmpty()) return
        val event = AdjustEvent(eventName)
        Adjust.trackEvent(event)
    }

    
    fun pushTrackEventAdmob(adValue: AdValue, adSourceName: String) {
        val adRevenue = AdjustAdRevenue("admob_sdk").apply {
            setRevenue(adValue.valueMicros / 1_000_000.0, adValue.currencyCode)
            setAdRevenueNetwork(adSourceName)
        }
        Adjust.trackAdRevenue(adRevenue)
    }

    
    fun logPaidAdImpressionValue(revenue: Double, currency: String) {
        val eventToken = AdSdkInitializer.adConfig?.adjustConfig?.eventAdImpressionValue ?: return
        val event = AdjustEvent(eventToken).apply {
            setRevenue(revenue, currency)
        }
        Adjust.trackEvent(event)
    }
}
