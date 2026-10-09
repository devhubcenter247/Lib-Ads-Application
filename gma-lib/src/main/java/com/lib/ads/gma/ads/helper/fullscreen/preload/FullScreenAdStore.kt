package com.lib.ads.gma.ads.helper.fullscreen.preload

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

data class WeightedAdUnit(
    val adUnitId: String,
    val weight: Float = 0f,
)

class FullScreenAdStore<T : Any>(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val dispose: (T) -> Unit = {},
) {
    data class ServedAd<T : Any>(
        val adUnitId: String,
        val weight: Float,
        val ad: T,
    )

    class LoadToken internal constructor(
        val adUnitId: String,
        private val sequence: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            other is LoadToken && adUnitId == other.adUnitId && sequence == other.sequence

        override fun hashCode(): Int = 31 * adUnitId.hashCode() + sequence.hashCode()

        override fun toString(): String = "LoadToken(adUnitId=$adUnitId)"
    }

    sealed class StartLoad {
        data object AlreadyReady : StartLoad()
        data object Loading : StartLoad()
        data class Started(val token: LoadToken) : StartLoad()
    }

    private data class Entry<T : Any>(
        val adUnitId: String,
        val weight: Float,
        val ad: T,
        val loadedAtMs: Long,
    )

    private val sequence = AtomicLong()
    private val adsByUnit = LinkedHashMap<String, ArrayDeque<Entry<T>>>()
    private val inFlight = LinkedHashMap<String, LoadToken>()

    @Synchronized
    fun tryStartLoading(adUnitId: String): StartLoad {
        val id = adUnitId.trim()
        if (id.isEmpty()) return StartLoad.Loading
        dropExpiredLocked()
        if (adsByUnit[id]?.isNotEmpty() == true) return StartLoad.AlreadyReady
        if (inFlight.containsKey(id)) return StartLoad.Loading
        return LoadToken(id, sequence.incrementAndGet()).also { token ->
            inFlight[id] = token
        }.let(StartLoad::Started)
    }

    @Synchronized
    fun put(adUnitId: String, weight: Float, ad: T, loadedAtMs: Long = nowMs()) {
        val id = adUnitId.trim()
        if (id.isEmpty()) {
            dispose(ad)
            return
        }
        adsByUnit.getOrPut(id) { ArrayDeque() }
            .addLast(Entry(id, weight, ad, loadedAtMs))
    }

    @Synchronized
    fun putIfCurrent(token: LoadToken, weight: Float, ad: T, loadedAtMs: Long = nowMs()): Boolean {
        if (inFlight[token.adUnitId] != token) {
            dispose(ad)
            return false
        }
        inFlight.remove(token.adUnitId)
        put(token.adUnitId, weight, ad, loadedAtMs)
        return true
    }

    @Synchronized
    fun completeIfCurrent(token: LoadToken): Boolean {
        if (inFlight[token.adUnitId] != token) return false
        inFlight.remove(token.adUnitId)
        return true
    }

    @Synchronized
    fun hasReady(units: List<WeightedAdUnit>): Boolean {
        dropExpiredLocked()
        return dedupeMaxWeight(units).any { adsByUnit[it.adUnitId]?.isNotEmpty() == true }
    }

    @Synchronized
    fun hasAnyReady(): Boolean {
        dropExpiredLocked()
        return adsByUnit.values.any { it.isNotEmpty() }
    }

    @Synchronized
    fun pollBest(units: List<WeightedAdUnit>): ServedAd<T>? {
        dropExpiredLocked()
        val requested = dedupeMaxWeight(units)
        if (requested.isEmpty()) return null
        val candidate = requested
            .mapNotNull { unit ->
                val entry = adsByUnit[unit.adUnitId]?.peekFirst() ?: return@mapNotNull null
                Candidate(unit, entry, maxOf(unit.weight, entry.weight))
            }
            .maxWithOrNull(compareBy<Candidate<T>> { it.effectiveWeight }.thenBy { it.unit.adUnitId })
            ?: return null
        val entry = adsByUnit[candidate.unit.adUnitId]?.pollFirst() ?: return null
        cleanupEmptyQueue(candidate.unit.adUnitId)
        return ServedAd(entry.adUnitId, candidate.effectiveWeight, entry.ad)
    }

    @Synchronized
    fun pollBestAny(): ServedAd<T>? {
        dropExpiredLocked()
        val candidate = adsByUnit.values
            .mapNotNull { it.peekFirst() }
            .maxWithOrNull(compareBy<Entry<T>> { it.weight }.thenBy { it.adUnitId })
            ?: return null
        val entry = adsByUnit[candidate.adUnitId]?.pollFirst() ?: return null
        cleanupEmptyQueue(candidate.adUnitId)
        return ServedAd(entry.adUnitId, entry.weight, entry.ad)
    }

    suspend fun pollBestAwait(
        units: List<WeightedAdUnit>,
        timeoutMs: Long,
        pollIntervalMs: Long = DEFAULT_AWAIT_POLL_INTERVAL_MS,
    ): ServedAd<T>? {
        val startedAt = nowMs()
        while (true) {
            pollBest(units)?.let { return it }
            if (nowMs() - startedAt >= timeoutMs.coerceAtLeast(0L)) return null
            delay(pollIntervalMs.coerceAtLeast(1L))
        }
    }

    @Synchronized
    fun release(adUnitId: String) {
        adsByUnit.remove(adUnitId)?.forEach { dispose(it.ad) }
        inFlight.remove(adUnitId)
    }

    @Synchronized
    fun releaseAll() {
        adsByUnit.values.forEach { queue -> queue.forEach { dispose(it.ad) } }
        adsByUnit.clear()
        inFlight.clear()
    }

    private fun dropExpiredLocked() {
        if (ttlMs <= 0L) return
        val now = nowMs()
        val iterator = adsByUnit.iterator()
        while (iterator.hasNext()) {
            val (_, queue) = iterator.next()
            queue.removeIf { entry ->
                val expired = now - entry.loadedAtMs >= ttlMs
                if (expired) dispose(entry.ad)
                expired
            }
            if (queue.isEmpty()) iterator.remove()
        }
    }

    private fun cleanupEmptyQueue(adUnitId: String) {
        if (adsByUnit[adUnitId]?.isEmpty() == true) {
            adsByUnit.remove(adUnitId)
        }
    }

    private data class Candidate<T : Any>(
        val unit: WeightedAdUnit,
        val entry: Entry<T>,
        val effectiveWeight: Float,
    )

    companion object {
        const val DEFAULT_TTL_MS: Long = 55 * 60 * 1000L
        const val DEFAULT_AWAIT_POLL_INTERVAL_MS: Long = 150L

        fun dedupeMaxWeight(units: List<WeightedAdUnit>): List<WeightedAdUnit> {
            val best = LinkedHashMap<String, Float>()
            units.forEach { unit ->
                val id = unit.adUnitId.trim()
                if (id.isEmpty()) return@forEach
                val previous = best[id]
                if (previous == null || unit.weight > previous) best[id] = unit.weight
            }
            return best.map { (id, weight) -> WeightedAdUnit(id, weight) }
        }
    }
}
