package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import com.facebook.appevents.AppEventsLogger

/**
 * Utility object for Facebook Analytics event logging.
 */
object FacebookEvent {

    
    fun logEventWithAds(context: Context, params: Bundle) {
        AppEventsLogger.newLogger(context).logEvent("paid_ad_impression", params)
    }

    
    fun logPaidAdImpressionValue(context: Context, bundle: Bundle) {
        AppEventsLogger.newLogger(context).logEvent("paid_ad_impression_value", bundle)
    }

    
    fun logClickAdsEvent(context: Context, bundle: Bundle) {
        AppEventsLogger.newLogger(context).logEvent("event_user_click_ads", bundle)
    }

    
    fun logCurrentTotalRevenueAd(context: Context, eventName: String, bundle: Bundle) {
        AppEventsLogger.newLogger(context).logEvent(eventName, bundle)
    }

    
    fun logTotalRevenue001Ad(context: Context, bundle: Bundle) {
        AppEventsLogger.newLogger(context).logEvent("paid_ad_impression_value_001", bundle)
    }
}
