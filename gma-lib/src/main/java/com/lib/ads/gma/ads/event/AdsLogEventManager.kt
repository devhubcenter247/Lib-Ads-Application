package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo

object AdsLogEventManager {
    private const val TAG = "AdsLogEventManager"

    @JvmStatic
    fun logPaidAdImpression(context: Context, adValue: AdValue, responseInfo: ResponseInfo, adType: AdType) {
        val loadedSource = responseInfo.loadedAdSourceResponseInfo
        val mediationAdapterClassName = loadedSource?.adapterClassName
        val adSourceName = loadedSource?.name ?: ""
        val adUnitId = responseInfo.extractAdUnitIdOrNull() ?: ""
        val precisionInt = when (adValue.precisionType) {
            PrecisionType.ESTIMATED -> 1
            PrecisionType.PUBLISHER_PROVIDED -> 2
            PrecisionType.PRECISE -> 3
            else -> 0
        }
        logEventWithAds(context, adValue.valueMicros.toFloat(), precisionInt, adUnitId, mediationAdapterClassName)
        AdsAdjust.pushTrackEvent(adValue, adSourceName)
    }

    @JvmStatic
    fun logClickAdsEvent(context: Context, adUnitId: String?) {
        Log.d(TAG, "User click ad for ad unit $adUnitId")
        if (adUnitId.isNullOrEmpty()) return
        val bundle = Bundle().apply { putString("ad_unit_id", adUnitId) }
        FirebaseAnalyticsUtil.logClickAdsEvent(context, bundle)
        FacebookEventUtils.logClickAdsEvent(context, bundle)
        AdsAdjust.onTrackClickAds()
    }

    @JvmStatic
    fun logCurrentTotalRevenueAd(context: Context, eventName: String) {
        val currentTotalRevenue = SharePreferenceUtils.getCurrentTotalRevenueAd(context)
        val bundle = Bundle().apply { putFloat("value", currentTotalRevenue) }
        FirebaseAnalyticsUtil.logCurrentTotalRevenueAd(context, eventName, bundle)
        FacebookEventUtils.logCurrentTotalRevenueAd(context, eventName, bundle)
    }

    @JvmStatic
    fun logTotalRevenue001Ad(context: Context) {
        val revenue = AppUtil.currentTotalRevenue001Ad
        if (revenue / 1_000_000 >= 0.01) {
            AppUtil.currentTotalRevenue001Ad = 0.0f
            SharePreferenceUtils.updateCurrentTotalRevenue001Ad(context, 0.0f)
            val bundle = Bundle().apply { putFloat("value", revenue / 1_000_000) }
            FirebaseAnalyticsUtil.logTotalRevenue001Ad(context, bundle)
            FacebookEventUtils.logTotalRevenue001Ad(context, bundle)
        }
    }

    @JvmStatic
    fun logTotalRevenueAdIn3DaysIfNeed(context: Context) {
        val installTime = SharePreferenceUtils.getInstallTime(context)
        if (!SharePreferenceUtils.isPushRevenue3Day(context) && System.currentTimeMillis() - installTime >= 259_200_000L) {
            Log.d(TAG, "logTotalRevenueAdAt3DaysIfNeed")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_3_days")
            SharePreferenceUtils.setPushedRevenue3Day(context)
        }
    }

    @JvmStatic
    fun logTotalRevenueAdIn7DaysIfNeed(context: Context) {
        val installTime = SharePreferenceUtils.getInstallTime(context)
        if (!SharePreferenceUtils.isPushRevenue7Day(context) && System.currentTimeMillis() - installTime >= 604_800_000L) {
            Log.d(TAG, "logTotalRevenueAdAt7DaysIfNeed")
            logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_7_days")
            SharePreferenceUtils.setPushedRevenue7Day(context)
        }
    }

    @JvmStatic
    fun trackAdRevenue(id: String) = AdsAdjust.trackAdRevenue(id)

    @JvmStatic
    fun onTrackEvent(eventName: String) = AdsAdjust.onTrackEvent(eventName)

    @JvmStatic
    fun onTrackEvent(eventName: String, id: String) = AdsAdjust.onTrackEvent(eventName, id)

    @JvmStatic
    fun onTrackTokenFcm(token: String, context: Context) = AdsAdjust.pushTrackTokenFcm(token, context)

    @JvmStatic
    fun onTrackRevenue(eventName: String, revenue: Float, currency: String) =
        AdsAdjust.onTrackRevenue(eventName, revenue, currency)

    @JvmStatic
    fun onTrackRevenuePurchase(revenue: Float, currency: String, productId: String, purchaseToken: String, typeIAP: Int) {
        AdsAdjust.onTrackRevenuePurchase(revenue, currency, productId, purchaseToken)
    }

    @JvmStatic
    fun onTrackRevenueSubscription(priceMicros: Long, currency: String, productId: String, orderId: String, signature: String, purchaseToken: String) =
        AdsAdjust.onTrackRevenueSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken)

    @JvmStatic
    fun pushTrackEvent(adValue: AdValue, adSourceName: String) =
        AdsAdjust.pushTrackEvent(adValue, adSourceName)

    @JvmStatic
    fun onTrackImpression(context: Context) {
        AdsAdjust.onTrackImpression()
        FirebaseAnalyticsUtil.logAdImpression(context)
        FacebookEventUtils.logAdImpression(context)
    }

    private fun logEventWithAds(context: Context, revenue: Float, precision: Int, adUnitId: String, network: String?) {
        Log.d(TAG, "Paid event of value %.0f microcents in currency USD of precision %s%n occurred for ad unit %s from ad network %s. mediation provider:%n"
            .format(revenue, precision, adUnitId, network))
        val params = Bundle().apply {
            putDouble("valuemicros", revenue.toDouble())
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network)
        }
        logPaidAdImpressionValue(context, revenue / 1_000_000.0, precision, adUnitId, network)
        FirebaseAnalyticsUtil.logEventWithAds(context, params)
        FacebookEventUtils.logEventWithAds(context, params)
        SharePreferenceUtils.updateCurrentTotalRevenueAd(context, revenue)
        logCurrentTotalRevenueAd(context, "event_current_total_revenue_ad")
        AppUtil.currentTotalRevenue001Ad += revenue
        SharePreferenceUtils.updateCurrentTotalRevenue001Ad(context, AppUtil.currentTotalRevenue001Ad)
        logTotalRevenue001Ad(context)
        logTotalRevenueAdIn3DaysIfNeed(context)
        logTotalRevenueAdIn7DaysIfNeed(context)
    }

    private fun logPaidAdImpressionValue(context: Context, value: Double, precision: Int, adUnitId: String, network: String?) {
        val params = Bundle().apply {
            putDouble("value", value)
            putString("currency", "USD")
            putInt("precision", precision)
            putString("adunitid", adUnitId)
            putString("network", network)
        }
        AdsAdjust.logPaidAdImpressionValue(value, "USD")
        FirebaseAnalyticsUtil.logPaidAdImpressionValue(context, params)
        FacebookEventUtils.logPaidAdImpressionValue(context, params)
    }
}
