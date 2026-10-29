package com.lib.ads.gma

import com.lib.ads.gma.ads.util.AdConfigKeyBlocklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdConfigKeyBlocklistTest {
    @Test
    fun isBlocked_returnsFalseForBlankOrUnknownKey() {
        AdConfigKeyBlocklist.resetForTest()

        assertFalse(AdConfigKeyBlocklist.isBlocked(null))
        assertFalse(AdConfigKeyBlocklist.isBlocked(""))
        assertFalse(AdConfigKeyBlocklist.isBlocked("unknown"))
    }

    @Test
    fun block_preventsFutureRequestsForSameConfigKey() {
        AdConfigKeyBlocklist.resetForTest()

        AdConfigKeyBlocklist.block("home_inter")

        assertTrue(AdConfigKeyBlocklist.isBlocked("home_inter"))
        assertFalse(AdConfigKeyBlocklist.isBlocked("other"))
    }

    @Test
    fun unblock_allowsConfigKeyAgain() {
        AdConfigKeyBlocklist.resetForTest()
        AdConfigKeyBlocklist.block("native_feed")

        AdConfigKeyBlocklist.unblock("native_feed")

        assertFalse(AdConfigKeyBlocklist.isBlocked("native_feed"))
    }
}
