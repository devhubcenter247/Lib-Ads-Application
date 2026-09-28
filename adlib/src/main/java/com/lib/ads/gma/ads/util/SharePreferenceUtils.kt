package com.lib.ads.gma.ads.util

import android.content.Context

/**
 * Utility object for SharedPreferences operations related to ads.
 */
object SharePreferenceUtils {
    private const val PREF_NAME = "app_ad_pref"

    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_CURRENT_TOTAL_REVENUE_AD = "KEY_CURRENT_TOTAL_REVENUE_AD"
    private const val KEY_CURRENT_TOTAL_REVENUE_001_AD = "KEY_CURRENT_TOTAL_REVENUE_001_AD"
    private const val KEY_PUSH_EVENT_REVENUE_3_DAY = "KEY_PUSH_EVENT_REVENUE_3_DAY"
    private const val KEY_PUSH_EVENT_REVENUE_7_DAY = "KEY_PUSH_EVENT_REVENUE_7_DAY"
    private const val KEY_LAST_IMPRESSION_INTERSTITIAL_TIME = "KEY_LAST_IMPRESSION_INTERSTITIAL_TIME"
    private const val COMPLETE_RATED = "COMPLETE_RATED"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    
    fun getInstallTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_INSTALL_TIME, 0)
    }

    
    fun setInstallTime(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_INSTALL_TIME, System.currentTimeMillis())
            .apply()
    }

    
    fun getCurrentTotalRevenueAd(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f)
    }

    
    fun updateCurrentTotalRevenueAd(context: Context, revenue: Float) {
        val prefs = getPrefs(context)
        val currentTotalRevenue = prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f)
        prefs.edit()
            .putFloat(KEY_CURRENT_TOTAL_REVENUE_AD, currentTotalRevenue + revenue / 1_000_000f)
            .apply()
    }

    
    fun getCurrentTotalRevenue001Ad(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, 0f)
    }

    
    fun updateCurrentTotalRevenue001Ad(context: Context, revenue: Float) {
        getPrefs(context).edit()
            .putFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, revenue)
            .apply()
    }

    
    fun isPushRevenue3Day(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, false)
    }

    
    fun setPushedRevenue3Day(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, true)
            .apply()
    }

    
    fun isPushRevenue7Day(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, false)
    }

    
    fun setPushedRevenue7Day(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, true)
            .apply()
    }

    
    fun getLastImpressionInterstitialTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, 0)
    }

    
    fun setLastImpressionInterstitialTime(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, System.currentTimeMillis())
            .apply()
    }

    
    fun getCompleteRated(context: Context): Boolean {
        return getPrefs(context).getBoolean(COMPLETE_RATED, false)
    }

    
    fun setCompleteRated(context: Context, isCompleteRated: Boolean) {
        getPrefs(context).edit()
            .putBoolean(COMPLETE_RATED, isCompleteRated)
            .apply()
    }
}
