package com.lib.ads.gma.ads.config

/**
 * `Daily_Ads_Revenue` — one Firebase event per install that marks a valuable user, for UA
 * campaigns optimising on it (same rules and names as Azura's AzTaichi):
 * - day 1 (first impression day): fires once lifetime ad revenue exceeds [revenueThreshold];
 * - day 2: fires once the user reaches [day2ImpressionThreshold] impressions.
 * It fires at most once per install.
 *
 * @param logPaidImpressionEvent also log `azura_paid_ad_impression` on every paid impression,
 *   for dashboards built on the Azura event set.
 */
class DailyAdsRevenueConfig @JvmOverloads constructor(
    val revenueThreshold: Double = 0.01,
    val day2ImpressionThreshold: Int = 10,
    val logPaidImpressionEvent: Boolean = false,
)
