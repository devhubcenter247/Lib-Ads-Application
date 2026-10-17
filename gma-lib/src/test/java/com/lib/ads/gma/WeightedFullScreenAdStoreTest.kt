package com.lib.ads.gma

import com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WeightedFullScreenAdStoreTest {

    @Test
    fun dedupeMaxWeight_keepsHighestWeightForDuplicateAdUnit() {
        val units = listOf(
            WeightedAdUnit("low", 0.1f),
            WeightedAdUnit("dup", 0.2f),
            WeightedAdUnit("dup", 0.9f),
        )

        assertEquals(
            listOf(WeightedAdUnit("low", 0.1f), WeightedAdUnit("dup", 0.9f)),
            FullScreenAdStore.dedupeMaxWeight(units),
        )
    }

    @Test
    fun putThenPollBest_returnsHighestWeightedReadyAd() {
        val store = FullScreenAdStore<String>()
        store.put("normal", weight = 0.5f, ad = "normal-ad")
        store.put("high", weight = 1.0f, ad = "high-ad")

        val best = store.pollBest(
            listOf(
                WeightedAdUnit("normal", 0.5f),
                WeightedAdUnit("high", 1.0f),
            ),
        )

        assertEquals("high-ad", best?.ad)
        assertEquals("high", best?.adUnitId)
        assertEquals(1.0f, best?.weight ?: 0f, 0.0f)
        assertEquals("normal-ad", store.pollBestAny()?.ad)
    }

    @Test
    fun pollBest_usesRequestWeightWhenItIsHigherThanStoredWeight() {
        val store = FullScreenAdStore<String>()
        store.put("a", weight = 0.1f, ad = "a-ad")
        store.put("b", weight = 0.5f, ad = "b-ad")

        val best = store.pollBest(
            listOf(
                WeightedAdUnit("a", 2.0f),
                WeightedAdUnit("b", 0.5f),
            ),
        )

        assertEquals("a-ad", best?.ad)
    }

    @Test
    fun pollBestAny_skipsExpiredAdsAndDisposesThem() {
        val disposed = mutableListOf<String>()
        val store = FullScreenAdStore<String>(
            ttlMs = 10L,
            nowMs = { 100L },
            dispose = { disposed.add(it) },
        )
        store.put("old", weight = 2.0f, ad = "old-ad", loadedAtMs = 0L)
        store.put("fresh", weight = 1.0f, ad = "fresh-ad", loadedAtMs = 95L)

        val best = store.pollBestAny()

        assertEquals("fresh-ad", best?.ad)
        assertEquals(listOf("old-ad"), disposed)
        assertFalse(store.hasReady(listOf(WeightedAdUnit("old", 2.0f))))
    }

    @Test
    fun tryStartLoading_blocksDuplicateInFlightLoadUntilCompleted() {
        val store = FullScreenAdStore<String>()

        val first = store.tryStartLoading("unit")
        val second = store.tryStartLoading("unit")

        assertTrue(first is FullScreenAdStore.StartLoad.Started)
        assertSame(FullScreenAdStore.StartLoad.Loading, second)

        store.completeIfCurrent((first as FullScreenAdStore.StartLoad.Started).token)

        assertTrue(store.tryStartLoading("unit") is FullScreenAdStore.StartLoad.Started)
    }

    @Test
    fun putIfCurrent_ignoresStaleLoadToken() {
        val store = FullScreenAdStore<String>()
        val first = store.tryStartLoading("unit") as FullScreenAdStore.StartLoad.Started
        store.completeIfCurrent(first.token)
        val second = store.tryStartLoading("unit") as FullScreenAdStore.StartLoad.Started

        assertFalse(store.putIfCurrent(first.token, weight = 2.0f, ad = "stale"))
        assertTrue(store.putIfCurrent(second.token, weight = 1.0f, ad = "fresh"))

        assertEquals("fresh", store.pollBestAny()?.ad)
        assertNull(store.pollBestAny())
    }

    @Test
    fun pollBestAwait_returnsAdThatArrivesBeforeTimeout() = runBlocking {
        var now = 0L
        val store = FullScreenAdStore<String>(nowMs = { now })
        store.put("unit", weight = 1.0f, ad = "ready")

        val served = store.pollBestAwait(
            listOf(WeightedAdUnit("unit", 1.0f)),
            timeoutMs = 1L,
            pollIntervalMs = 1L,
        )

        assertEquals("ready", served?.ad)
    }
}
