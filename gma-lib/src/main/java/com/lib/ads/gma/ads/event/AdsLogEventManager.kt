package com.lib.ads.gma.ads.event

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.helper.extension.adUnitIdOrEmpty
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.util.SharePreferenceUtils

object AdsLogEventManager {
    private const val TAG = "AdsLogEventManager"
    private const val THREE_DAYS_MS = 259_200_000L
    private const val SEVEN_DAYS_MS = 604_800_000L

    /**
     * Preferred entry point: the ad unit and Ad Placements id are read from [ad] itself, which is
     * correct for mediated fills too. Safe to call from the SDK's background callback thread.
     */
    @JvmStatic
    fun logPaidAdImpression(context: Context, adValue: AdValue, ad: Ad, adType: AdType) {
        val placementId = runCatching { ad.placementId }.getOrDefault(0L)
        logPaidImpression(context, PaidImpression.from(adValue, ad.getResponseInfo(), adType, ad.adUnitIdOrEmpty(), placementId))
    }

    /** Legacy entry point: the ad unit is parsed from the response and is empty for mediated fills. */
    @JvmStatic
    fun logPaidAdImpression(context: Context, adValue: AdValue, responseInfo: ResponseInfo, adType: AdType) =
        logPaidImpression(
            context,
            PaidImpression.from(adValue, responseInfo, adType, responseInfo.extractAdUnitIdOrNull().orEmpty(), 0L),
        )

    private fun logPaidImpression(context: Context, impression: PaidImpression) {
        val appContext = context.applicationContext
        Log.d(
            TAG,
            "paid: ${impression.valueMicros} micros ${impression.currency} precision=${impression.precision} " +
                "format=${impression.adFormat} unit=${impression.adUnitId} source=${impression.adSourceName}",
        )
        if (AdsProvider.getInstance().adConfigOrNull?.logFirebaseAdImpression != false) {
            safely("firebase ad_impression") { FirebaseAnalyticsUtil.logAdImpression(appContext, impression) }
        }

        val paidParams = impression.paidAdImpressionParams().toBundle()
        safely("firebase paid_ad_impression") { FirebaseAnalyticsUtil.logEventWithAds(appContext, paidParams) }
        safely("facebook paid_ad_impression") { FacebookEventUtils.logEventWithAds(appContext, paidParams) }
        safely("appsflyer paid_ad_impression") { AdsAppsflyer.logEvent(appContext, "paid_ad_impression", paidParams) }

        val valueParams = impression.paidAdImpressionValueParams().toBundle()
        safely("adjust impression value") { AdsAdjust.logPaidAdImpressionValue(impression.value, impression.currency) }
        safely("firebase paid_ad_impression_value") { FirebaseAnalyticsUtil.logPaidAdImpressionValue(appContext, valueParams) }
        safely("facebook paid_ad_impression_value") { FacebookEventUtils.logPaidAdImpressionValue(appContext, valueParams) }
        safely("appsflyer paid_ad_impression_value") { AdsAppsflyer.logEvent(appContext, "paid_ad_impression_value", valueParams) }

        safely("adjust ad revenue") { AdsAdjust.trackAdRevenue(impression) }
        safely("appsflyer ad revenue") { AdsAppsflyer.logPaidAdImpression(impression) }

        val snapshot = SharePreferenceUtils.addAdRevenueMicros(appContext, impression.valueMicros)
        logCurrentTotalRevenueAd(appContext, "event_current_total_revenue_ad")
        snapshot.bucket001PayoutMicros?.let { logRevenue001Payout(appContext, it) }
        logTotalRevenueAdIn3DaysIfNeed(appContext)
        logTotalRevenueAdIn7DaysIfNeed(appContext)
    }

    @JvmStatic
    fun logClickAdsEvent(context: Context, adUnitId: String?) {
        Log.d(TAG, "User click ad for ad unit $adUnitId")
        if (adUnitId.isNullOrEmpty()) return
        val appContext = context.applicationContext
        val bundle = Bundle().apply { putString("ad_unit_id", adUnitId) }
        safely("firebase click") { FirebaseAnalyticsUtil.logClickAdsEvent(appContext, bundle) }
        safely("facebook click") { FacebookEventUtils.logClickAdsEvent(appContext, bundle) }
        safely("adjust click") { AdsAdjust.onTrackClickAds() }
        safely("appsflyer click") { AdsAppsflyer.logEvent(appContext, "ad_click", bundle) }
    }

    @JvmStatic
    fun logCurrentTotalRevenueAd(context: Context, eventName: String) {
        val currentTotalRevenue = SharePreferenceUtils.getCurrentTotalRevenueAd(context)
        val bundle = Bundle().apply { putFloat("value", currentTotalRevenue) }
        safely("firebase $eventName") { FirebaseAnalyticsUtil.logCurrentTotalRevenueAd(context, eventName, bundle) }
        safely("facebook $eventName") { FacebookEventUtils.logCurrentTotalRevenueAd(context, eventName, bundle) }
        safely("appsflyer $eventName") { AdsAppsflyer.logEvent(context, eventName, bundle) }
    }

    /** Pays out the pending 0.01 bucket if it has reached the threshold. */
    @JvmStatic
    fun logTotalRevenue001Ad(context: Context) {
        SharePreferenceUtils.addAdRevenueMicros(context, 0L).bucket001PayoutMicros
            ?.let { logRevenue001Payout(context, it) }
    }

    private fun logRevenue001Payout(context: Context, payoutMicros: Long) {
        val bundle = Bundle().apply { putFloat("value", RevenueMath.microsToUnit(payoutMicros).toFloat()) }
        safely("firebase 001") { FirebaseAnalyticsUtil.logTotalRevenue001Ad(context, bundle) }
        safely("facebook 001") { FacebookEventUtils.logTotalRevenue001Ad(context, bundle) }
        safely("appsflyer 001") { AdsAppsflyer.logEvent(context, "paid_ad_impression_value_001", bundle) }
    }

    @JvmStatic
    fun logTotalRevenueAdIn3DaysIfNeed(context: Context) {
        if (!isInstallOlderThan(context, THREE_DAYS_MS)) return
        if (!SharePreferenceUtils.markPushedRevenue3DayIfNeeded(context)) return
        Log.d(TAG, "logTotalRevenueAdAt3DaysIfNeed")
        logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_3_days")
    }

    @JvmStatic
    fun logTotalRevenueAdIn7DaysIfNeed(context: Context) {
        if (!isInstallOlderThan(context, SEVEN_DAYS_MS)) return
        if (!SharePreferenceUtils.markPushedRevenue7DayIfNeeded(context)) return
        Log.d(TAG, "logTotalRevenueAdAt7DaysIfNeed")
        logCurrentTotalRevenueAd(context, "event_total_revenue_ad_in_7_days")
    }

    /** An unrecorded install time (0) is "unknown", not "installed in 1970". */
    private fun isInstallOlderThan(context: Context, ageMs: Long): Boolean {
        val installTime = SharePreferenceUtils.getInstallTime(context)
        return installTime > 0L && System.currentTimeMillis() - installTime >= ageMs
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
    fun onTrackRevenuePurchase(revenue: Float, currency: String, productId: String, purchaseToken: String, typeIAP: Int) =
        trackPurchaseRevenue(revenue.toDouble(), currency, productId, purchaseToken, orderId = null)

    /** One-time IAP revenue to Adjust and AppsFlyer. The billing layer guarantees one call per purchase. */
    internal fun trackPurchaseRevenue(revenue: Double, currency: String, productId: String, purchaseToken: String, orderId: String?) {
        safely("adjust purchase") { AdsAdjust.trackPurchase(revenue, currency, productId, purchaseToken, orderId) }
        safely("appsflyer purchase") {
            AdsAppsflyer.logPurchase(revenue, currency, productId, AdsAppsflyer.CONTENT_TYPE_INAPP, orderId)
        }
    }

    /** Subscription start revenue (first paid phase of the purchased offer) to Adjust and AppsFlyer. */
    internal fun trackSubscriptionRevenue(
        priceMicros: Long,
        currency: String,
        productId: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long,
    ) {
        safely("adjust subscription") {
            AdsAdjust.onTrackRevenueSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken, purchaseTime)
        }
        safely("appsflyer subscription") {
            AdsAppsflyer.logPurchase(
                RevenueMath.microsToUnit(priceMicros), currency, productId, AdsAppsflyer.CONTENT_TYPE_SUBS, orderId,
            )
        }
    }

    @Deprecated(
        message = "Uses the current time as purchase time. Pass the Purchase's purchaseTime.",
        replaceWith = ReplaceWith("onTrackRevenueSubscription(priceMicros, currency, productId, orderId, signature, purchaseToken, purchaseTime)")
    )
    @JvmStatic
    fun onTrackRevenueSubscription(priceMicros: Long, currency: String, productId: String, orderId: String, signature: String, purchaseToken: String) =
        trackSubscriptionRevenue(priceMicros, currency, productId, orderId, signature, purchaseToken, System.currentTimeMillis())

    @JvmStatic
    fun onTrackRevenueSubscription(
        priceMicros: Long,
        currency: String,
        productId: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long,
    ) = trackSubscriptionRevenue(priceMicros, currency, productId, orderId, signature, purchaseToken, purchaseTime)

    @JvmStatic
    fun pushTrackEvent(adValue: AdValue, adSourceName: String) =
        AdsAdjust.pushTrackEvent(adValue, adSourceName)

    /**
     * Impression lifecycle signal. Firebase's `ad_impression` is no longer sent from here: it is
     * logged with value/currency from the paid callback instead (a value-less copy here counted
     * every impression twice with zero revenue).
     */
    @JvmStatic
    fun onTrackImpression(context: Context) {
        val appContext = context.applicationContext
        safely("adjust impression") { AdsAdjust.onTrackImpression() }
        safely("facebook impression") { FacebookEventUtils.logAdImpression(appContext) }
        safely("appsflyer impression") { AdsAppsflyer.logEvent(appContext, "ad_impression") }
    }

    /** One analytics SDK failing (not initialized, missing app id) must not drop the others or crash the SDK thread. */
    private inline fun safely(destination: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$destination failed: ${e.message}")
        }
    }
}
