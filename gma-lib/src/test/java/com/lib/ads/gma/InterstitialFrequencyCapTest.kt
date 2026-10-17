package com.lib.ads.gma

import com.lib.ads.gma.ads.helper.interstitial.InterstitialFrequencyCap
import com.lib.ads.gma.ads.helper.interstitial.InterstitialFrequencyCap.NEVER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialFrequencyCapTest {

    @Test
    fun `never shown is never cooling down`() {
        assertFalse(InterstitialFrequencyCap.isCoolingDown(now = 5_000L, lastShownAt = NEVER, intervalMs = 30_000L))
    }

    @Test
    fun `inside the interval is cooling down, at the boundary it is not`() {
        assertTrue(InterstitialFrequencyCap.isCoolingDown(now = 10_000L, lastShownAt = 1_000L, intervalMs = 30_000L))
        assertFalse(InterstitialFrequencyCap.isCoolingDown(now = 31_000L, lastShownAt = 1_000L, intervalMs = 30_000L))
    }

    @Test
    fun `no interval configured never blocks`() {
        assertFalse(InterstitialFrequencyCap.isCoolingDown(now = 1_001L, lastShownAt = 1_000L, intervalMs = 0L))
    }
}
