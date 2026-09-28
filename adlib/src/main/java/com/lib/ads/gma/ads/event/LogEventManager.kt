package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.ResponseInfo

/**
 * Centralized manager for logging ad-related events to various analytics platforms.
 */
object LogEventManager {
    private const val TAG = "AzLogEventManager"

    
    fun logPaidAdImpression(
        context: Context,
        adValue: AdValue,
        adUnitId: String,
        responseInfo: ResponseInfo?,
        adType: AdType
    ) {
        if (responseInfo == null) return
        val mediationAdapterClassName = responseInfo.mediationAdapterClassName
        val loadedInfo = responseInfo.loadedAdapterResponseInfo
        val adSourceName = loadedInfo?.adSourceName ?: ""
        val adSourceId = loadedInfo?.adSourceId ?: ""
        val adSourceInstanceName = loadedInfo?.adSourceInstanceName ?: ""
        val adSourceInstanceId = loadedInfo?.adSourceInstanceId ?: ""
        val extras = responseInfo.responseExtras
        val mediationGroupName = extras.getString("mediation_group_name") ?: ""
        val mediationABTestName = extras.getString("mediation_ab_test_name") ?: ""
        val mediationABTestVariant = extras.getString("mediation_ab_test_variant") ?: ""

        Log.d(
            TAG,
            "Paid impression — unit=$adUnitId source=$adSourceName($adSourceId) " +
                "instance=$adSourceInstanceName($adSourceInstanceId) " +
                "group=$mediationGroupName abTest=$mediationABTestName/$mediationABTestVariant"
        )

        logEventWithAds(
            context,
            adValue.valueMicros.toFloat(),
            adValue.precisionType,
            adUnitId,
            mediationAdapterClassName
        )
        AdAdjust.pushTrackEventAdmob(adValue, adSourceName)

        mediationAdapterClassName?.let {
            AdAppsflyer.getInstance().pushTrackEventAdmobNew(adValue, adUnitId, adType, it)
        }
    }

    private fun logEventWithAds(
        context: Context,
        revenue: Float,
        precision: Int,
        adUnitId: String,
        network: String?
    ) {
        Log.d(
            TAG,
            "Paid event of value %.0f microcents in currency USD of precision %s occurred for ad unit %s from ad network %s".format(
                revenue,
                precision,
                adUnitId,
                network
            )
        )

        val params = Bundle().apply {
            putDouble("valuemicros", revenue.toDouble())
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network)
        }

        // Log revenue for this ad
        logPaidAdImpressionValue(context, revenue / 1_000_000.0, precision, adUnitId, network)
        FirebaseAnalytics.logEventWithAds(context, params)
        FacebookEvent.logEventWithAds(context, params)

        // Update current total revenue ads
        SharePreferenceUtils.updateCurrentTotalRevenueAd(context, revenue)
        logCurrentTotalRevenueAd(context, "event_current_total_revenue_ad")

        // Update current total revenue ads for event paid_ad_impression_value_0.01
        AppUtil.currentTotalRevenue001Ad += revenue
        SharePreferenceUtils.updateCurrentTotalRevenue001Ad(context, AppUtil.currentTotalRevenue001Ad)
        logTotalRevenue001Ad(context)

        logTotalRevenueAdIn3DaysIfNeed(context)
        logTotalRevenueAdIn7DaysIfNeed(context)
    }

    private fun logPaidAdImpressionValue(
        context: Context,
        value: Double,
        precision: Int,
        adUnitId: String,
        network: String?
    ) {
        val params = Bundle().apply {
            putDouble("value", value)
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network)
        }

        AdAdjust.logPaidAdImpressionValue(value, "USD")
        FirebaseAnalytics.logPaidAdImpressionValue(context, params)
        FacebookEvent.logPaidAdImpressionValue(context, params)
    }

    
    fun logClickAdsEvent(context: Context, adUnitId: String) {
        Log.d(TAG, "User click ad for ad unit $adUnitId.")
        val bundle = Bundle().apply {
            putString("ad_unit_id", adUnitId)
        }

        FirebaseAnalytics.logClickAdsEvent(context, bundle)
        FacebookEvent.logClickAdsEvent(context, bundle)
    }

    
    fun logCurrentTotalRevenueAd(context: Context, eventName: String) {
        val currentTotalRevenue = SharePreferenceUtils.getCurrentTotalRevenueAd(context)
        val bundle = Bundle().apply {
            putFloat("value", currentTotalRevenue)
        }

        FirebaseAnalytics.logCurrentTotalRevenueAd(context, eventName, bundle)
        FacebookEvent.logCurrentTotalRevenueAd(context, eventName, bundle)
    }

    
    fun logTotalRevenue001Ad(context: Context) {
        val revenue = AppUtil.currentTotalRevenue001Ad
        if (revenue / 1_000_000 >= 0.01f) {
            AppUtil.currentTotalRevenue001Ad = 0f
            SharePreferenceUtils.updateCurrentTotalRevenue001Ad(context, 0f)
            val bundle = Bundle().apply {
                putFloat("value", revenue / 1_000_000)
            }
            FirebaseAnalytics.logTotalRevenue001Ad(context, bundle)
            FacebookEvent.logTotalRevenue001Ad(context, bundle)
        }
    }

    
    fun logTotalRevenueAdIn3DaysIfNeed(context: Context) {
        val installTime = SharePreferenceUtils.getInstallTime(context)
        val threeDaysMs = 3L * 24 * 60 * 60 * 1000
        if (!SharePreferenceUtils.isPushRevenue3Day(context) &&
            (System.currentTimeMillis() - installTime >= threeDaysMs)
        ) {
            Log.d(TAG, "logTotalRevenueAdAt3DaysIfNeed")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_3_days")
            SharePreferenceUtils.setPushedRevenue3Day(context)
        }
    }

    
    fun logTotalRevenueAdIn7DaysIfNeed(context: Context) {
        val installTime = SharePreferenceUtils.getInstallTime(context)
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        if (!SharePreferenceUtils.isPushRevenue7Day(context) &&
            (System.currentTimeMillis() - installTime >= sevenDaysMs)
        ) {
            Log.d(TAG, "logTotalRevenueAdAt7DaysIfNeed")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_7_days")
            SharePreferenceUtils.setPushedRevenue7Day(context)
        }
    }

    
    fun trackAdRevenue(id: String) {
        AdAdjust.trackAdRevenue(id)
    }

    
    fun onTrackEvent(eventName: String) {
        AdAdjust.onTrackEvent(eventName)
    }

    
    fun onTrackTokenFcm(token: String, context: Context) {
        AdAdjust.pushTrackTokenFcm(token, context)
    }

    
    fun onTrackEvent(eventName: String, id: String) {
        AdAdjust.onTrackEvent(eventName, id)
    }

    
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) {
        AdAdjust.onTrackRevenue(eventName, revenue, currency)
    }

    
    fun onTrackRevenuePurchase(revenue: Float, currency: String, idPurchase: String, typeIAP: Int) {
        AdAdjust.onTrackRevenuePurchase(revenue, currency)
        AdAppsflyer.getInstance().onTrackRevenuePurchase(revenue, currency, idPurchase, typeIAP)
    }

    
    fun pushTrackEventAdmob(adValue: AdValue, adSourceName: String) {
        AdAdjust.pushTrackEventAdmob(adValue, adSourceName)
    }

    
    fun onTrackImpression() {
        AdAdjust.onTrackImpression()
    }
}
