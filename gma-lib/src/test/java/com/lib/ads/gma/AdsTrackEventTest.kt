package com.lib.ads.gma

import com.lib.ads.gma.ads.config.AppsflyerConfig
import com.lib.ads.gma.ads.event.AdsTrackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsTrackEventTest {
    @Test
    fun appsflyerConfig_defaultsToRevenueAndPurchaseOnly() {
        val config = AppsflyerConfig(true, "dev-key")

        assertEquals(setOf(AdsTrackEvent.AD_REVENUE, AdsTrackEvent.PURCHASE), config.events)
    }

    @Test
    fun namedEventsResolveBackToEnum() {
        assertEquals(AdsTrackEvent.AD_IMPRESSION, AdsTrackEvent.of("ad_impression"))
        assertEquals(AdsTrackEvent.AD_CLICK, AdsTrackEvent.of("ad_click"))
        assertEquals(AdsTrackEvent.PAID_AD_IMPRESSION_VALUE, AdsTrackEvent.of("paid_ad_impression_value"))
        assertNull(AdsTrackEvent.of("unknown"))
    }

    @Test
    fun lifecyclePresetIncludesDefaultRevenueEvents() {
        assertTrue(AdsTrackEvent.AD_REVENUE in AdsTrackEvent.LIFECYCLE)
        assertTrue(AdsTrackEvent.PURCHASE in AdsTrackEvent.LIFECYCLE)
        assertTrue(AdsTrackEvent.AD_IMPRESSION in AdsTrackEvent.LIFECYCLE)
        assertTrue(AdsTrackEvent.AD_CLICK in AdsTrackEvent.LIFECYCLE)
    }
}
