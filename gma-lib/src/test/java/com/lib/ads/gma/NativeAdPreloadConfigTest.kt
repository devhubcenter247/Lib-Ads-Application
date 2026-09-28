package com.lib.ads.gma

import com.lib.ads.gma.ads.helper.adnative.api.NativeAdTagConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdPreloadConfigTest {

    // ── NativeAdTagConfig ────────────────────────────────────────────────────

    @Test
    fun nativeAdTagConfig_canShowAds_defaultsTrue() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id"))
        assertTrue(config.canShowAds)
    }

    @Test
    fun nativeAdTagConfig_canReloadAds_defaultsTrue() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id"))
        assertTrue(config.canReloadAds)
    }

    @Test
    fun nativeAdTagConfig_canShowAds_false_isRespected() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id"), canShowAds = false)
        assertFalse(config.canShowAds)
    }

    @Test
    fun nativeAdTagConfig_getAllAdUnitIds_filtersBlanks() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id1", "", "  ", "id2"))
        assertEquals(listOf("id1", "id2"), config.getAllAdUnitIds())
    }

    @Test
    fun nativeAdTagConfig_simple_defaultsCanShowAdsTrue() {
        val config = NativeAdTagConfig.simple(tag = "t", adUnitId = "id")
        assertTrue(config.canShowAds)
    }

    // ── NativeAdPreloadHolderOptions ─────────────────────────────────────────

    @Test
    fun options_canShowAds_defaultsTrue() {
        val options = NativeAdPreloadHolderOptions()
        assertTrue(options.canShowAds)
    }

    @Test
    fun options_canReloadAds_defaultsTrue() {
        val options = NativeAdPreloadHolderOptions()
        assertTrue(options.canReloadAds)
    }

    // Mirrors what NativeAdWithPreload(config: NativeAdTagConfig) does internally: the tag
    // config's canShowAds/canReloadAds must flow into the options passed to the holder, or a
    // disabled placement silently keeps requesting ads (this was a real bug — see review).
    @Test
    fun options_copy_fromDisabledTagConfig_propagatesCanShowAds() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id"), canShowAds = false, canReloadAds = false)
        val options = NativeAdPreloadHolderOptions(autoRequestOnStart = true).copy(
            fallbackAdUnitIds = config.getAllAdUnitIds(),
            canShowAds = config.canShowAds,
            canReloadAds = config.canReloadAds,
        )
        assertFalse(options.canShowAds)
        assertFalse(options.canReloadAds)
        assertEquals(listOf("id"), options.fallbackAdUnitIds)
    }

    @Test
    fun options_copy_fromEnabledTagConfig_keepsCanShowAdsTrue() {
        val config = NativeAdTagConfig(tag = "t", listId = listOf("id"))
        val options = NativeAdPreloadHolderOptions().copy(canShowAds = config.canShowAds, canReloadAds = config.canReloadAds)
        assertTrue(options.canShowAds)
        assertTrue(options.canReloadAds)
    }
}
