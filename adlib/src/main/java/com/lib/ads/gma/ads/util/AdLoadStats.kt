package com.lib.ads.gma.ads.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized, thread-safe counter for ad load activity across the whole library.
 *
 * Every ad load path (native preload, native cold load, interstitial, banner,
 * rewarded, app-open …) reports here so the running totals of how many ads were
 * requested / loaded / failed / shown can be logged in one consistent place.
 *
 * Counters are tracked per [AdType] and (optionally) per logical [tag] so the
 * same screen/placement can be inspected in isolation.
 *
 * Enable output with [AppLogger.isEnabled] = true. Read [snapshot] for debug UI.
 */
object AdLoadStats {

    private const val TAG = "AdLoadStats"

    /** Mutable counters for a single (type, tag) bucket. */
    class Counter {
        val requested = AtomicLong(0)
        val loaded = AtomicLong(0)
        val failed = AtomicLong(0)
        val served = AtomicLong(0)
        val impression = AtomicLong(0)

        fun copy(): Snapshot = Snapshot(
            requested = requested.get(),
            loaded = loaded.get(),
            failed = failed.get(),
            served = served.get(),
            impression = impression.get()
        )
    }

    /** Immutable view of a [Counter] for reporting. */
    data class Snapshot(
        val requested: Long,
        val loaded: Long,
        val failed: Long,
        val served: Long,
        val impression: Long
    )

    // key = "TYPE" or "TYPE@tag"
    private val counters = ConcurrentHashMap<String, Counter>()

    private fun key(type: AdType, tag: String?): String =
        if (tag.isNullOrBlank()) type.name else "${type.name}@$tag"

    private fun counter(type: AdType, tag: String?): Counter =
        counters.getOrPut(key(type, tag)) { Counter() }

    fun recordRequested(type: AdType, tag: String? = null, adUnitId: String? = null) {
        counter(type, tag).requested.incrementAndGet()
        log(type, tag, "requested", adUnitId)
    }

    fun recordLoaded(type: AdType, tag: String? = null, adUnitId: String? = null) {
        // A loaded ad is also tracked on the type-level bucket so global totals stay correct.
        counter(type, tag).loaded.incrementAndGet()
        if (tag != null) counter(type, null).loaded.incrementAndGet()
        log(type, tag, "loaded", adUnitId)
    }

    fun recordFailed(type: AdType, tag: String? = null, adUnitId: String? = null, reason: String? = null) {
        counter(type, tag).failed.incrementAndGet()
        if (tag != null) counter(type, null).failed.incrementAndGet()
        log(type, tag, "failed${if (reason != null) "($reason)" else ""}", adUnitId)
    }

    /** An ad pulled from a preload buffer and handed to the UI. */
    fun recordServed(type: AdType, tag: String? = null, adUnitId: String? = null, fromBuffer: Boolean = true) {
        counter(type, tag).served.incrementAndGet()
        if (tag != null) counter(type, null).served.incrementAndGet()
        log(type, tag, "served${if (fromBuffer) "(buffer-hit)" else "(cold)"}", adUnitId)
    }

    fun recordImpression(type: AdType, tag: String? = null, adUnitId: String? = null) {
        counter(type, tag).impression.incrementAndGet()
        if (tag != null) counter(type, null).impression.incrementAndGet()
        log(type, tag, "impression", adUnitId)
    }

    /** Counters for one (type, tag) bucket, or the type-level totals when [tag] is null. */
    fun get(type: AdType, tag: String? = null): Snapshot = counter(type, tag).copy()

    /** Full snapshot keyed by "TYPE" / "TYPE@tag" for debug overlays. */
    fun snapshot(): Map<String, Snapshot> = counters.mapValues { it.value.copy() }

    fun reset() = counters.clear()

    private fun log(type: AdType, tag: String?, event: String, adUnitId: String?) {
        if (!AppLogger.isEnabled) return
        val c = counter(type, tag).copy()
        val label = if (tag.isNullOrBlank()) type.name else "${type.name}/$tag"
        val unit = adUnitId?.let { " id=$it" } ?: ""
        AppLogger.d(
            TAG,
            "[$label] $event$unit -> requested=${c.requested} loaded=${c.loaded} " +
                "failed=${c.failed} served=${c.served} impression=${c.impression}"
        )
    }
}
