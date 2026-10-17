package com.lib.ads.gma.ads.event

import android.content.Context
import android.util.Log
import com.lib.ads.gma.ads.config.DailyAdsRevenueConfig
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Port of Azura's AzTaichi `Daily_Ads_Revenue` with the same event names, params and stored keys
 * (an app migrating from azmoduleads keeps its progress). One fix: the day is counted in local
 * calendar days, not 24h blocks — a 23h DST day used to keep "day 2" as day 1.
 */
internal object DailyAdsRevenue {
    const val EVENT_DAILY_ADS_REVENUE = "Daily_Ads_Revenue"
    const val EVENT_PAID_AD_IMPRESSION = "azura_paid_ad_impression"
    private const val TAG = "DailyAdsRevenue"
    private const val AD_PLATFORM = "Admob"

    /** 1 on the first impression's local day, 2 the next calendar day, ... */
    fun dayNumber(firstDayMs: Long, nowMs: Long, zone: ZoneId): Long {
        val first = Instant.ofEpochMilli(firstDayMs).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(first, now) + 1
    }

    fun localMidnight(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Azura's enum names; rewarded interstitial was reported as REWARDED there. */
    fun azuraFormat(adType: AdType): String =
        if (adType == AdType.REWARDED_INTERSTITIAL) AdType.REWARDED.name else adType.name

    /** Paid callbacks arrive concurrently on SDK threads; the day/threshold bookkeeping is one step. */
    @Synchronized
    fun onPaidImpression(context: Context, impression: PaidImpression, config: DailyAdsRevenueConfig) {
        if (config.logPaidImpressionEvent) {
            log(context, EVENT_PAID_AD_IMPRESSION, impression, impression.value)
        }
        if (SharePreferenceUtils.isDailyAdsRevenueLogged(context)) return

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        var firstDay = SharePreferenceUtils.getFirstImpressionDay(context)
        if (firstDay == 0L) {
            firstDay = localMidnight(now, zone)
            SharePreferenceUtils.setFirstImpressionDay(context, firstDay)
        }
        when (dayNumber(firstDay, now, zone)) {
            1L -> {
                SharePreferenceUtils.addDay1Revenue(context, impression.value)
                val total = SharePreferenceUtils.getDay1Revenue(context)
                if (total > config.revenueThreshold) fire(context, impression, total)
            }
            2L -> {
                val impressions = SharePreferenceUtils.incrementDay2Impressions(context)
                if (impressions >= config.day2ImpressionThreshold) {
                    fire(context, impression, SharePreferenceUtils.getDay1Revenue(context) + impression.value)
                }
            }
            // Day 3+ (or a clock moved backwards): the milestone window is over.
            else -> Unit
        }
    }

    private fun fire(context: Context, impression: PaidImpression, value: Double) {
        SharePreferenceUtils.setDailyAdsRevenueLogged(context)
        log(context, EVENT_DAILY_ADS_REVENUE, impression, value)
        Log.d(TAG, "$EVENT_DAILY_ADS_REVENUE logged value=$value ${impression.currency}")
    }

    private fun log(context: Context, name: String, impression: PaidImpression, value: Double) {
        FirebaseAnalyticsUtil.logEventTracking(
            context,
            name,
            mapOf(
                "ad_platform" to AD_PLATFORM,
                "ad_format" to azuraFormat(impression.adType),
                "currency" to impression.currency,
                "value" to value,
            ).toBundle(),
        )
    }
}
