package com.lib.ads.gma.ads.event

enum class AdsTrackEvent(val eventName: String? = null) {
    AD_REVENUE,
    PURCHASE,
    AD_IMPRESSION("ad_impression"),
    AD_CLICK("ad_click"),
    PAID_AD_IMPRESSION("paid_ad_impression"),
    PAID_AD_IMPRESSION_VALUE("paid_ad_impression_value"),
    PAID_AD_IMPRESSION_VALUE_001("paid_ad_impression_value_001"),
    CURRENT_TOTAL_REVENUE("event_current_total_revenue_ad"),
    TOTAL_REVENUE_3_DAYS("event_total_revenue_ad_in_3_days"),
    TOTAL_REVENUE_7_DAYS("event_total_revenue_ad_in_7_days");

    companion object {
        private val byName: Map<String, AdsTrackEvent> =
            entries.mapNotNull { event -> event.eventName?.let { it to event } }.toMap()

        @JvmStatic
        fun of(eventName: String): AdsTrackEvent? = byName[eventName]

        @JvmField
        val DEFAULT: Set<AdsTrackEvent> = setOf(AD_REVENUE, PURCHASE)

        @JvmField
        val LIFECYCLE: Set<AdsTrackEvent> = DEFAULT + setOf(AD_IMPRESSION, AD_CLICK)

        @JvmField
        val ALL: Set<AdsTrackEvent> = entries.toSet()
    }
}
