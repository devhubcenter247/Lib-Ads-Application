package com.lib.ads.gma

import com.lib.ads.gma.ads.event.ConsentSignals
import com.lib.ads.gma.ads.event.PaidImpression
import com.lib.ads.gma.ads.event.RevenueMath
import com.lib.ads.gma.ads.model.AdType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueTrackingTest {

    private fun impression(
        valueMicros: Long = 12_345L,
        currency: String = "EUR",
        adType: AdType = AdType.REWARDED_INTERSTITIAL,
        placementId: Long = 0L,
    ) = PaidImpression(
        valueMicros = valueMicros,
        currency = currency,
        precision = 3,
        adUnitId = "/21775744923/example/rewarded",
        adSourceName = "Meta Audience Network",
        adapterClassName = "com.google.ads.mediation.facebook.FacebookMediationAdapter",
        adType = adType,
        placementId = placementId,
    )

    @Test
    fun `ga4 ad_impression carries value, currency and the filling network`() {
        val params = impression().firebaseAdImpressionParams()
        assertEquals("admob", params["ad_platform"])
        assertEquals("Meta Audience Network", params["ad_source"])
        assertEquals("rewarded_interstitial", params["ad_format"])
        assertEquals("/21775744923/example/rewarded", params["ad_unit_name"])
        assertEquals(0.012345, params["value"] as Double, 1e-12)
        assertEquals("EUR", params["currency"])
    }

    @Test
    fun `legacy paid params keep their names and use the real currency`() {
        val params = impression().paidAdImpressionParams()
        assertEquals(setOf("valuemicros", "currency", "precision", "adunitid", "network"), params.keys)
        assertEquals("EUR", params["currency"])
        assertEquals(12_345.0, params["valuemicros"] as Double, 0.0)
    }

    @Test
    fun `placement falls back to the ad unit when no Ad Placements id is set`() {
        assertEquals("/21775744923/example/rewarded", impression().placement)
        assertEquals("42", impression(placementId = 42L).placement)
    }

    @Test
    fun `bucket accumulates below the threshold and pays out the whole amount at it`() {
        val first = RevenueMath.addToBucket(0L, 4_000L)
        assertEquals(4_000L, first.accumulatorMicros)
        assertNull(first.payoutMicros)

        val second = RevenueMath.addToBucket(first.accumulatorMicros, 7_000L)
        assertEquals(0L, second.accumulatorMicros)
        assertEquals(11_000L, second.payoutMicros)
    }

    @Test
    fun `bucket ignores negative values and zero adds only re-check the threshold`() {
        assertEquals(500L, RevenueMath.addToBucket(500L, -1_000L).accumulatorMicros)
        assertEquals(10_000L, RevenueMath.addToBucket(10_000L, 0L).payoutMicros)
    }

    @Test
    fun `legacy float storage migrates to micros`() {
        assertEquals(1_250_000L, RevenueMath.legacyUsdToMicros(1.25f))
        assertEquals(9_999L, RevenueMath.legacyMicrosToMicros(9_999f))
    }

    @Test
    fun `tcf out of scope grants everything`() {
        val dma = ConsentSignals.fromTcf(0, null)!!
        assertFalse(dma.eea)
        assertTrue(dma.adStorage && dma.adUserData && dma.adPersonalization)
    }

    @Test
    fun `tcf in scope maps purposes the way google infers consent mode`() {
        // Purposes 1..10: storage yes, 3+4 personalised ads yes, 7 measurement no.
        val dma = ConsentSignals.fromTcf(1, "1111110000")!!
        assertTrue(dma.eea)
        assertTrue(dma.adStorage)
        assertFalse(dma.adUserData)
        assertTrue(dma.adPersonalization)
    }

    @Test
    fun `tcf rejected or missing purposes deny`() {
        val dma = ConsentSignals.fromTcf(1, "0")!!
        assertFalse(dma.adStorage || dma.adUserData || dma.adPersonalization)
        assertFalse(ConsentSignals.fromTcf(1, null)!!.adStorage)
    }

    @Test
    fun `tcf unknown leaves consent untouched`() {
        assertNull(ConsentSignals.fromTcf(-1, "1111111111"))
    }
}
