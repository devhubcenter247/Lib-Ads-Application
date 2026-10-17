package com.lib.ads.gma

import com.lib.ads.gma.ads.event.AdHealthEvents
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.event.AdsTrackEvent
import com.lib.ads.gma.ads.event.DailyAdsRevenue
import com.lib.ads.gma.ads.model.AdType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsOptInTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun millis(date: LocalDate, hour: Int, zone: ZoneId) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `day number counts calendar days`() {
        val first = DailyAdsRevenue.localMidnight(millis(LocalDate.of(2026, 6, 1), 15, berlin), berlin)
        assertEquals(1L, DailyAdsRevenue.dayNumber(first, millis(LocalDate.of(2026, 6, 1), 23, berlin), berlin))
        assertEquals(2L, DailyAdsRevenue.dayNumber(first, millis(LocalDate.of(2026, 6, 2), 0, berlin), berlin))
        assertEquals(3L, DailyAdsRevenue.dayNumber(first, millis(LocalDate.of(2026, 6, 3), 9, berlin), berlin))
    }

    @Test
    fun `dst spring-forward day still advances to day 2`() {
        // 2026-03-29 is 23h long in Berlin; 24h arithmetic kept the next morning on "day 1".
        val first = DailyAdsRevenue.localMidnight(millis(LocalDate.of(2026, 3, 29), 12, berlin), berlin)
        assertEquals(2L, DailyAdsRevenue.dayNumber(first, millis(LocalDate.of(2026, 3, 30), 0, berlin), berlin))
    }

    @Test
    fun `azura format keeps enum names and folds rewarded interstitial`() {
        assertEquals("INTERSTITIAL", DailyAdsRevenue.azuraFormat(AdType.INTERSTITIAL))
        assertEquals("REWARDED", DailyAdsRevenue.azuraFormat(AdType.REWARDED_INTERSTITIAL))
    }

    @Test
    fun `health params are typed and clipped to the GA4 limit`() {
        val load = AdHealthEvents.loadTimeParams(AdType.NATIVE, "/123/native", 850L, success = false)
        assertEquals("native", load["ad_format"])
        assertEquals(850L, load["load_time_ms"])
        assertEquals(0L, load["success"])

        val failed = AdHealthEvents.loadFailedParams(AdType.BANNER, "u", 3, "NO_FILL", "x".repeat(300))
        assertEquals(100, (failed["error_message"] as String).length)
        assertEquals(3L, failed["error_code"])
        assertEquals("NO_FILL", failed["error_name"])

        assertEquals("", AdHealthEvents.showFailedParams(AdType.APP_OPEN, "u", null)["error_message"])
    }

    @Test
    fun `health events resolve for appsflyer filtering`() {
        assertEquals(AdsTrackEvent.AD_LOAD_FAILED, AdsTrackEvent.of("ad_load_failed"))
        assertEquals(AdsTrackEvent.AD_SHOW_FAILED, AdsTrackEvent.of("ad_show_failed"))
        assertEquals(AdsTrackEvent.AD_LOAD_TIME, AdsTrackEvent.of("ad_load_time"))
        assertFalse(AdsTrackEvent.AD_LOAD_TIME in AdsTrackEvent.DEFAULT)
    }

    @Test
    fun `firebase purchase params carry transaction id only when known`() {
        val withOrder = AdsLogEventManager.purchaseParams(4.99, "USD", "remove_ads", "inapp", "GPA.1")
        assertEquals("GPA.1", withOrder["transaction_id"])
        assertEquals(4.99, withOrder["value"] as Double, 0.0)
        assertFalse("transaction_id" in AdsLogEventManager.purchaseParams(1.0, "USD", "p", "subs", " "))
    }
}
