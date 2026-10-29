package com.lib.ads.gma.ads.util

import android.content.Context
import androidx.core.content.edit
import com.lib.ads.gma.ads.event.RevenueMath

object SharePreferenceUtils {
    private const val PREF_NAME = "az_ad_pref"
    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_CURRENT_TOTAL_REVENUE_AD = "KEY_CURRENT_TOTAL_REVENUE_AD"
    private const val KEY_CURRENT_TOTAL_REVENUE_001_AD = "KEY_CURRENT_TOTAL_REVENUE_001_AD"
    private const val KEY_TOTAL_REVENUE_MICROS = "KEY_TOTAL_REVENUE_MICROS"
    private const val KEY_BUCKET_001_MICROS = "KEY_BUCKET_001_MICROS"
    private const val KEY_PUSH_EVENT_REVENUE_3_DAY = "KEY_PUSH_EVENT_REVENUE_3_DAY"
    private const val KEY_PUSH_EVENT_REVENUE_7_DAY = "KEY_PUSH_EVENT_REVENUE_7_DAY"
    private const val COMPLETE_RATED = "COMPLETE_RATED"
    private const val KEY_DAILY_ADS_REVENUE_LOGGED = "KEY_DAILY_ADS_REVENUE_LOGGED"
    private const val KEY_DAILY_ADS_FIRST_IMPRESSION_DAY = "KEY_DAILY_ADS_FIRST_IMPRESSION_DAY"
    private const val KEY_DAILY_ADS_DAY1_REVENUE = "KEY_DAILY_ADS_DAY1_REVENUE"
    private const val KEY_DAILY_ADS_DAY2_IMPRESSIONS = "KEY_DAILY_ADS_DAY2_IMPRESSIONS"
    private const val KEY_TRACKED_PURCHASE_TOKENS = "KEY_TRACKED_PURCHASE_TOKENS"
    private const val KEY_CONSENT_SIGNATURE = "KEY_CONSENT_SIGNATURE"
    private const val KEY_VERIFIED_ENTITLEMENTS = "KEY_VERIFIED_ENTITLEMENTS"
    private const val MAX_TRACKED_PURCHASE_TOKENS = 100

    private fun getPrefs(context: Context) = context.applicationContext.getSharedPreferences(PREF_NAME, 0)

    @JvmStatic fun getInstallTime(context: Context): Long =
        getPrefs(context).getLong(KEY_INSTALL_TIME, 0L)

    @JvmStatic fun setInstallTime(context: Context) =
        getPrefs(context).edit { putLong(KEY_INSTALL_TIME, System.currentTimeMillis()) }

    /** Lifetime ad revenue, in currency units (USD for most AdMob accounts). */
    @JvmStatic fun getCurrentTotalRevenueAd(context: Context): Float =
        RevenueMath.microsToUnit(getTotalRevenueMicros(context)).toFloat()

    /** @param revenue micros, as the legacy API took it. */
    @JvmStatic fun updateCurrentTotalRevenueAd(context: Context, revenue: Float) {
        addAdRevenueMicros(context, RevenueMath.legacyMicrosToMicros(revenue))
    }

    /** Pending 0.01 bucket, in micros. */
    @JvmStatic fun getCurrentTotalRevenue001Ad(context: Context): Float =
        getBucket001Micros(context).toFloat()

    /** @param revenue micros — overwrites the pending 0.01 bucket. */
    @JvmStatic fun updateCurrentTotalRevenue001Ad(context: Context, revenue: Float) = synchronized(this) {
        getPrefs(context).edit { putLong(KEY_BUCKET_001_MICROS, RevenueMath.legacyMicrosToMicros(revenue)) }
    }

    internal data class RevenueSnapshot(val totalMicros: Long, val bucket001PayoutMicros: Long?)

    /**
     * Adds one impression's revenue to the lifetime total and the 0.01 bucket in one atomic step.
     * Paid callbacks arrive on SDK background threads, several at once (banner + native), so the
     * read-modify-write must not interleave.
     */
    @JvmStatic
    internal fun addAdRevenueMicros(context: Context, micros: Long): RevenueSnapshot = synchronized(this) {
        val prefs = getPrefs(context)
        val total = totalRevenueMicrosLocked(context) + micros.coerceAtLeast(0L)
        val bucket = RevenueMath.addToBucket(bucket001MicrosLocked(context), micros)
        prefs.edit {
            putLong(KEY_TOTAL_REVENUE_MICROS, total)
            putLong(KEY_BUCKET_001_MICROS, bucket.accumulatorMicros)
        }
        RevenueSnapshot(total, bucket.payoutMicros)
    }

    @JvmStatic
    internal fun getTotalRevenueMicros(context: Context): Long = synchronized(this) { totalRevenueMicrosLocked(context) }

    private fun totalRevenueMicrosLocked(context: Context): Long {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_TOTAL_REVENUE_MICROS)) return prefs.getLong(KEY_TOTAL_REVENUE_MICROS, 0L)
        val migrated = RevenueMath.legacyUsdToMicros(prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_AD, 0f))
        prefs.edit { putLong(KEY_TOTAL_REVENUE_MICROS, migrated) }
        return migrated
    }

    private fun getBucket001Micros(context: Context): Long = synchronized(this) { bucket001MicrosLocked(context) }

    private fun bucket001MicrosLocked(context: Context): Long {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_BUCKET_001_MICROS)) return prefs.getLong(KEY_BUCKET_001_MICROS, 0L)
        val migrated = RevenueMath.legacyMicrosToMicros(prefs.getFloat(KEY_CURRENT_TOTAL_REVENUE_001_AD, 0f))
        prefs.edit { putLong(KEY_BUCKET_001_MICROS, migrated) }
        return migrated
    }

    /** Returns true exactly once per flag: the caller that flips it owns sending the event. */
    @JvmStatic
    internal fun markPushedRevenue3DayIfNeeded(context: Context): Boolean = markOnce(context, KEY_PUSH_EVENT_REVENUE_3_DAY)

    @JvmStatic
    internal fun markPushedRevenue7DayIfNeeded(context: Context): Boolean = markOnce(context, KEY_PUSH_EVENT_REVENUE_7_DAY)

    private fun markOnce(context: Context, key: String): Boolean = synchronized(this) {
        val prefs = getPrefs(context)
        val alreadyPushed = prefs.getBoolean(key, false)
        if (!alreadyPushed) prefs.edit { putBoolean(key, true) }
        !alreadyPushed
    }

    @JvmStatic fun isPushRevenue3Day(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, false)

    @JvmStatic fun setPushedRevenue3Day(context: Context) =
        getPrefs(context).edit { putBoolean(KEY_PUSH_EVENT_REVENUE_3_DAY, true) }

    @JvmStatic fun isPushRevenue7Day(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, false)

    @JvmStatic fun setPushedRevenue7Day(context: Context) =
        getPrefs(context).edit { putBoolean(KEY_PUSH_EVENT_REVENUE_7_DAY, true) }

    /**
     * Records [key] (a purchase token) as revenue-tracked. Returns false when it was already
     * recorded, so a purchase reported twice (listener + restore, pending → purchased, failed
     * consume retried next launch) is counted once. Keeps the newest [MAX_TRACKED_PURCHASE_TOKENS].
     */
    @JvmStatic
    internal fun markPurchaseTrackedIfNew(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        return synchronized(this) { markPurchaseTrackedLocked(context, key) }
    }

    private fun markPurchaseTrackedLocked(context: Context, key: String): Boolean {
        val prefs = getPrefs(context)
        val current = prefs.getString(KEY_TRACKED_PURCHASE_TOKENS, null)
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        if (key in current) return false
        val updated = (current + key).takeLast(MAX_TRACKED_PURCHASE_TOKENS)
        prefs.edit { putString(KEY_TRACKED_PURCHASE_TOKENS, updated.joinToString("\n")) }
        return true
    }

    /** Entitlement names Play last confirmed; null when billing never completed a successful refresh. */
    @JvmStatic
    internal fun getVerifiedEntitlementNames(context: Context): Set<String>? =
        getPrefs(context).getStringSet(KEY_VERIFIED_ENTITLEMENTS, null)?.toSet()

    @JvmStatic
    internal fun setVerifiedEntitlementNames(context: Context, names: Set<String>) =
        getPrefs(context).edit { putStringSet(KEY_VERIFIED_ENTITLEMENTS, names.toSet()) }

    @JvmStatic
    internal fun getConsentSignature(context: Context): String? =
        getPrefs(context).getString(KEY_CONSENT_SIGNATURE, null)

    @JvmStatic
    internal fun setConsentSignature(context: Context, signature: String) =
        getPrefs(context).edit { putString(KEY_CONSENT_SIGNATURE, signature) }

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
