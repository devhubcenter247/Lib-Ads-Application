package com.lib.ads.gma.ads.util

import android.content.Context
import androidx.core.content.edit

object SharePreferenceUtils {
    private const val PREF_NAME = "az_ad_pref"
    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_CURRENT_TOTAL_REVENUE_AD = "KEY_CURRENT_TOTAL_REVENUE_AD"
    private const val KEY_CURRENT_TOTAL_REVENUE_001_AD = "KEY_CURRENT_TOTAL_REVENUE_001_AD"
    private const val KEY_PUSH_EVENT_REVENUE_3_DAY = "KEY_PUSH_EVENT_REVENUE_3_DAY"
    private const val KEY_PUSH_EVENT_REVENUE_7_DAY = "KEY_PUSH_EVENT_REVENUE_7_DAY"
    private const val COMPLETE_RATED = "COMPLETE_RATED"
    private const val KEY_DAILY_ADS_REVENUE_LOGGED = "KEY_DAILY_ADS_REVENUE_LOGGED"
    private const val KEY_DAILY_ADS_FIRST_IMPRESSION_DAY = "KEY_DAILY_ADS_FIRST_IMPRESSION_DAY"
    private const val KEY_DAILY_ADS_DAY1_REVENUE = "KEY_DAILY_ADS_DAY1_REVENUE"
    private const val KEY_DAILY_ADS_DAY2_IMPRESSIONS = "KEY_DAILY_ADS_DAY2_IMPRESSIONS"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREF_NAME, 0)

    @JvmStatic fun getInstallTime(context: Context): Long =
        getPrefs(context).getLong(KEY_INSTALL_TIME, 0L)

    @JvmStatic fun setInstallTime(context: Context) =
        getPrefs(context).edit { putLong(KEY_INSTALL_TIME, System.currentTimeMillis()) }

    @JvmStatic fun getCurrentTotalRevenueAd(context: Context): Float =
        getPrefs(context).getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f)

    @JvmStatic fun updateCurrentTotalRevenueAd(context: Context, revenue: Float) {
        val prefs = getPrefs(context)
        val newTotal = prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f) + revenue / 1_000_000f
        prefs.edit { putFloat(KEY_CURRENT_TOTAL_REVENUE_AD, newTotal) }
    }

    @JvmStatic fun getCurrentTotalRevenue001Ad(context: Context): Float =
        getPrefs(context).getFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, 0f)

    @JvmStatic fun updateCurrentTotalRevenue001Ad(context: Context, revenue: Float) =
        getPrefs(context).edit { putFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, revenue) }

    @JvmStatic fun isPushRevenue3Day(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, false)

    @JvmStatic fun setPushedRevenue3Day(context: Context) =
        getPrefs(context).edit { putBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, true) }

    @JvmStatic fun isPushRevenue7Day(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, false)

    @JvmStatic fun setPushedRevenue7Day(context: Context) =
        getPrefs(context).edit { putBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, true) }

    @JvmStatic fun getCompleteRated(context: Context): Boolean =
        getPrefs(context).getBoolean(COMPLETE_RATED, false)

    @JvmStatic fun setCompleteRated(context: Context, isCompleteRated: Boolean) =
        getPrefs(context).edit { putBoolean(COMPLETE_RATED, isCompleteRated) }

    @JvmStatic fun isCompleteRated(context: Context): Boolean = getCompleteRated(context)

    @JvmStatic fun isDailyAdsRevenueLogged(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DAILY_ADS_REVENUE_LOGGED, false)

    @JvmStatic fun setDailyAdsRevenueLogged(context: Context) =
        getPrefs(context).edit { putBoolean(KEY_DAILY_ADS_REVENUE_LOGGED, true) }

    @JvmStatic fun getFirstImpressionDay(context: Context): Long =
        getPrefs(context).getLong(KEY_DAILY_ADS_FIRST_IMPRESSION_DAY, 0L)

    @JvmStatic fun setFirstImpressionDay(context: Context, dayTimestamp: Long) =
        getPrefs(context).edit { putLong(KEY_DAILY_ADS_FIRST_IMPRESSION_DAY, dayTimestamp) }

    @JvmStatic fun getDay1Revenue(context: Context): Double =
        java.lang.Double.longBitsToDouble(getPrefs(context).getLong(KEY_DAILY_ADS_DAY1_REVENUE, 0L))

    @JvmStatic fun addDay1Revenue(context: Context, revenueUsd: Double) {
        val prefs = getPrefs(context)
        val newTotal = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_DAILY_ADS_DAY1_REVENUE, 0L)) + revenueUsd
        prefs.edit { putLong(KEY_DAILY_ADS_DAY1_REVENUE, java.lang.Double.doubleToRawLongBits(newTotal)) }
    }

    @JvmStatic fun getDay2Impressions(context: Context): Int =
        getPrefs(context).getInt(KEY_DAILY_ADS_DAY2_IMPRESSIONS, 0)

    @JvmStatic fun incrementDay2Impressions(context: Context): Int {
        val prefs = getPrefs(context)
        val newCount = prefs.getInt(KEY_DAILY_ADS_DAY2_IMPRESSIONS, 0) + 1
        prefs.edit { putInt(KEY_DAILY_ADS_DAY2_IMPRESSIONS, newCount) }
        return newCount
    }
}
