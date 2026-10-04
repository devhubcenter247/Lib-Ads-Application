package com.lib.ads.gma.ads.helper.adnative

import android.app.Activity
import android.util.LruCache
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.manager.NativeAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.ads.model.wrapper.resolvedAdUnitId
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Tag-owned native cache. An ad is inserted only after a request started for that tag succeeds. */
object NativeAds {
    private val states = mutableMapOf<String, MutableStateFlow<PreloadBufferState>>()
    private val specs = mutableMapOf<String, NativeAdSpec>()
    private val preloadStartedAt = ConcurrentHashMap<String, Long>()
    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val targetBuffers = ConcurrentHashMap<String, Int>()

    /** Number of network load slots currently running for each placement key. */
    private val inFlightRequests = ConcurrentHashMap<String, AtomicInteger>()

    /** Total load slots started for each placement key, useful for diagnostics. */
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()

    private val tagCaches = object : LruCache<String, ArrayDeque<ApNativeAd>>(32) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: ArrayDeque<ApNativeAd>,
            newValue: ArrayDeque<ApNativeAd>?,
        ) {
            if (evicted) {
                oldValue.forEach { runCatching { it.nativeAd?.destroy() } }
            }
        }
    }
    private val tagGenerations = ConcurrentHashMap<String, AtomicInteger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun state(tag: String) =
        states.getOrPut(tag) { MutableStateFlow(PreloadBufferState.Idle) }

    fun register(spec: NativeAdSpec) {
        val previous = specs[spec.tag]
        if (previous != null && previous.adUnitIds != spec.adUnitIds) {
            preloadJobs.remove(spec.tag)?.cancel()
            tagCaches.remove(spec.tag)?.forEach { runCatching { it.nativeAd?.destroy() } }
            tagGenerations.getOrPut(spec.tag) { AtomicInteger() }.incrementAndGet()
            targetBuffers.remove(spec.tag)
            inFlightRequests.remove(spec.tag)
            requestCounts.remove(spec.tag)
            state(spec.tag).value = PreloadBufferState.Idle
        }
        specs[spec.tag] = spec
        state(spec.tag)
    }

    /** The ad-unit waterfall owned by a placement tag, used by consumers to poll the same cache. */
    fun adUnitIds(tag: String): List<String> = specs[tag]?.adUnitIds.orEmpty()

    fun preload(tag: String, adUnitIds: List<String>, bufferSize: Int) {
        if (specs[tag]?.adUnitIds != adUnitIds) {
            register(NativeAdSpec(tag = tag, adUnitIds = adUnitIds, bufferSize = bufferSize))
        }
        preloadInternal(tag, adUnitIds, bufferSize, force = false)
    }

    fun safePreload(spec: NativeAdSpec) {
        register(spec)
        preloadInternal(spec.tag, spec.adUnitIds, spec.bufferSize, force = false)
    }

    fun forcePreload(spec: NativeAdSpec) {
        register(spec)
        preloadInternal(spec.tag, spec.adUnitIds, spec.bufferSize, force = true)
    }

    private fun preloadInternal(
        tag: String,
        adUnitIds: List<String>,
        bufferSize: Int,
        force: Boolean
    ) {
        val target = bufferSize.coerceAtLeast(1)
        val previousTarget = targetBuffers[tag] ?: 0
        if (force || target > previousTarget) targetBuffers[tag] = target
        val requestedTarget = targetBuffers[tag] ?: target
        val cache = tagCaches.get(tag) ?: ArrayDeque<ApNativeAd>().also { tagCaches.put(tag, it) }
        if (!force && isExpired(tag, specs[tag] ?: return)) {
            preloadJobs.remove(tag)?.cancel()
            cache.forEach { runCatching { it.nativeAd?.destroy() } }
            cache.clear()
            tagGenerations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        }
        if (force) {
            AdLogger.d(
                AdFormat.NATIVE,
                tag,
                "PRELOAD",
                "mode=FORCE target=$requestedTarget cached=${cache.size}"
            )
            preloadJobs.remove(tag)?.cancel()
            cache.forEach { it.nativeAd?.destroy() }
            cache.clear()
            inFlightRequests.remove(tag)
            requestCounts[tag]?.set(0)
            tagGenerations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        } else if (cache.size >= requestedTarget) {
            AdLogger.d(
                AdFormat.NATIVE,
                tag,
                "PRELOAD_SKIP",
                "reason=BUFFER_SUFFICIENT cached=${cache.size} target=$requestedTarget"
            )
            state(tag).value = PreloadBufferState.Ready(cache.size)
            return
        } else if (preloadJobs[tag]?.isActive == true) {
            if (requestedTarget <= previousTarget) {
                AdLogger.d(
                    AdFormat.NATIVE,
                    tag,
                    "PRELOAD_SKIP",
                    "reason=IN_FLIGHT cached=${cache.size} target=$requestedTarget"
                )
                return
            }
            AdLogger.d(
                AdFormat.NATIVE,
                tag,
                "PRELOAD_RESTART",
                "reason=TARGET_INCREASE old=$previousTarget new=$requestedTarget"
            )
            preloadJobs.remove(tag)?.cancel()
            tagGenerations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        }
        val generation = tagGenerations.getOrPut(tag) { AtomicInteger() }.get()
        AdLogger.d(
            AdFormat.NATIVE,
            tag,
            "PRELOAD_START",
            "ids=$adUnitIds cached=${cache.size} target=$requestedTarget parallel=${(requestedTarget - cache.size).coerceAtLeast(1).coerceAtMost(MAX_PARALLEL_NATIVE_PRELOADS)}"
        )
        state(tag).value = PreloadBufferState.Loading(cache.size, requestedTarget)
        preloadStartedAt[tag] = System.currentTimeMillis()
        preloadJobs[tag] = scope.launch {
            val context = AdsProvider.getInstance().applicationContextOrNull()
            if (context == null || adUnitIds.isEmpty()) {
                state(tag).value =
                    PreloadBufferState.Error("No application context or ad unit for tag=$tag")
                return@launch
            }
            val reservedSlots = AtomicInteger(cache.size)
            val workerCount = (requestedTarget - cache.size).coerceAtLeast(1).coerceAtMost(MAX_PARALLEL_NATIVE_PRELOADS)
            coroutineScope {
                List(workerCount) {
                    async(Dispatchers.Main.immediate) {
                        while (isActive) {
                            val slot = reservedSlots.getAndIncrement()
                            if (slot >= requestedTarget) break
                            val inFlight = inFlightRequests.getOrPut(tag) { AtomicInteger() }
                            val requestCount = requestCounts.getOrPut(tag) { AtomicInteger() }
                            inFlight.incrementAndGet()
                            requestCount.incrementAndGet()
                            val result = try {
                                loadNativeWaterfall(context, tag, adUnitIds)
                            } finally {
                                inFlight.decrementAndGet()
                            }
                            val ad = result?.first
                            val winningId = result?.second
                            if (ad != null && tagGenerations[tag]?.get() == generation) {
                                cache.addLast(ad)
                                AdLogger.d(
                                    AdFormat.NATIVE,
                                    tag,
                                    "FILL",
                                    "id=$winningId cached=${cache.size} target=$requestedTarget"
                                )
                                state(tag).value =
                                    PreloadBufferState.ItemLoaded(cache.size, requestedTarget)
                            } else if (ad != null) {
                                runCatching { ad.nativeAd?.destroy() }
                                break
                            } else {
                                reservedSlots.decrementAndGet()
                                break
                            }
                        }
                    }
                }.awaitAll()
            }
            if (tagGenerations[tag]?.get() != generation) return@launch
            preloadJobs.remove(tag)
            state(tag).value = if (cache.size >= requestedTarget) {
                AdLogger.d(
                    AdFormat.NATIVE,
                    tag,
                    "PRELOAD_COMPLETE",
                    "cached=${cache.size} target=$requestedTarget"
                )
                PreloadBufferState.Ready(cache.size)
            } else {
                AdLogger.e(
                    AdFormat.NATIVE,
                    tag,
                    "PRELOAD_ERROR",
                    "cached=${cache.size} target=$requestedTarget"
                )
                PreloadBufferState.Error("Native fill incomplete for tag=$tag cached=${cache.size} target=$requestedTarget")
            }
        }
    }

    fun preload(tag: String, count: Int) {
        val spec = specs[tag]
        if (spec == null) {
            state(tag).value = PreloadBufferState.Error("No NativeAdSpec registered for tag=$tag")
            return
        }
        preload(tag, spec.adUnitIds, count)
    }

    fun preload(spec: NativeAdSpec) {
        safePreload(spec)
    }

    /** Stops [tag]'s running preload; ads already buffered stay available. */
    fun stopPreload(tag: String) {
        val job = preloadJobs.remove(tag) ?: return
        tagGenerations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        job.cancel()
        inFlightRequests.remove(tag)
        state(tag).value = PreloadBufferState.Cancelled
        AdLogger.d(AdFormat.NATIVE, tag, "PRELOAD_STOP", "cached=${tagCaches.get(tag)?.size ?: 0}")
    }

    fun stateFlow(tag: String): StateFlow<PreloadBufferState> = state(tag)
    fun available(tag: String): Int {
        val spec = specs[tag] ?: return 0
        if (isExpired(tag, spec)) return 0
        return tagCaches.get(tag)?.size ?: 0
    }

    fun bufferCount(tag: String): Int = available(tag)

    fun requestCount(tag: String): Int = requestCounts[tag]?.get() ?: 0

    fun inFlightRequests(tag: String): Int = inFlightRequests[tag]?.get() ?: 0

    /**
     * Which ad unit id in this tag's waterfall currently has a ready buffer, without consuming it
     * — e.g. `idHigh` failed to preload but `idNormal` succeeded. Use this (or
     * [ApNativeAd.resolvedAdUnitId] on whatever [get] returns) to see per-id fill behavior for a
     * placement instead of only a pass/fail count for the whole list.
     */
    fun readyAdUnitId(tag: String): String? {
        val spec = specs[tag] ?: return null
        return tagCaches.get(tag)?.firstOrNull()?.resolvedAdUnitId()
    }

    /** Replaces a buffer whose configured TTL has elapsed before it is consumed. */
    fun ensureFresh(tag: String) {
        val spec = specs[tag] ?: return
        if (isExpired(tag, spec)) {
            preload(spec)
        }
    }

    fun refillNextIfEmpty(tag: String, nextAdUnitIds: List<String>, bufferSize: Int = 1) {
        if (available(tag) > 0 || nextAdUnitIds.isEmpty()) return
        val current = specs[tag]
        val spec = NativeAdSpec.waterfall(
            tag = tag,
            adUnitIds = nextAdUnitIds,
            bufferSize = bufferSize,
            layoutId = current?.defaultLayoutId ?: 0,
            canShowAds = current?.canShowAds ?: true,
            canReloadAds = current?.canReloadAds ?: true,
        )
        safePreload(spec)
    }

    fun preloadHighestWeight(tag: String, selfUnits: List<com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit>) {
        val current = specs[tag]
        val normalized = com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
            .dedupeMaxWeight(selfUnits)
            .sortedByDescending { it.weight }
        if (normalized.isEmpty()) return
        val readyId = readyAdUnitId(tag)
        val readyWeight = current?.weightedAdUnits()
            ?.firstOrNull { it.adUnitId == readyId }
            ?.weight
            ?: Float.NEGATIVE_INFINITY
        if (available(tag) > 0 && readyWeight >= normalized.first().weight) return
        safePreload(
            NativeAdSpec.weightedWaterfall(
                tag = tag,
                adUnits = normalized,
                bufferSize = current?.bufferSize ?: 1,
                layoutId = current?.defaultLayoutId ?: 0,
                canShowAds = current?.canShowAds ?: true,
                canReloadAds = current?.canReloadAds ?: true,
            )
        )
    }

    /**
     * Keeps an ad whose holder no longer wants it (arrived after a newer request or after the holder
     * left the screen, or returned unimpressed by a disposed list item) in [tag]'s buffer, so the
     * next request shows it instead of wasting a matched request. Such ads may exceed the preload
     * target up to [MAX_OFFERED_NATIVE_BUFFER]: in a list many holders share one tag, and capping at
     * the preload target (usually 1) destroyed every other late fill. The ad is destroyed when the
     * tag is not registered, its buffer has expired or is full. Safe to call from any thread.
     */
    internal fun offer(tag: String, ad: ApNativeAd) {
        scope.launch {
            val spec = specs[tag]
            val cache = tagCaches.get(tag)
            val target = (targetBuffers[tag] ?: spec?.bufferSize ?: 1).coerceAtLeast(1)
            val capacity = maxOf(target, MAX_OFFERED_NATIVE_BUFFER)
            if (spec == null || isExpired(tag, spec) || (cache?.size ?: 0) >= capacity) {
                AdLogger.d(AdFormat.NATIVE, tag, "LATE_AD", "destroyed: buffer unavailable or full")
                runCatching { ad.nativeAd?.destroy() }
                return@launch
            }
            // A tag filled only by holders' cold loads has no preload timestamp; start its TTL here
            // so offered ads cannot be kept forever.
            preloadStartedAt.putIfAbsent(tag, System.currentTimeMillis())
            val buffer = cache ?: ArrayDeque<ApNativeAd>().also { tagCaches.put(tag, it) }
            buffer.addLast(ad)
            AdLogger.d(AdFormat.NATIVE, tag, "LATE_AD", "kept for next request cached=${buffer.size}")
            if (buffer.size >= target) state(tag).value = PreloadBufferState.Ready(buffer.size)
        }
    }

    /** Polls a buffered ad for the registered spec. Check [ApNativeAd.resolvedAdUnitId] on the result to see which id in the waterfall won. */
    fun get(tag: String): ApNativeAd? {
        val spec = specs[tag] ?: return null
        if (isExpired(tag, spec)) return null
        val cache = tagCaches.get(tag) ?: return null
        val ad = if (cache.isEmpty()) null else cache.removeFirst()
        // Polling only consumes the buffer. Preload is explicitly started by preload()/safePreload();
        // consuming an ad never creates an implicit refill request.
        val target = targetBuffers[tag] ?: spec.bufferSize
        state(tag).value = when {
            cache.size >= target -> PreloadBufferState.Ready(cache.size)
            inFlightRequests(tag) > 0 -> PreloadBufferState.Loading(cache.size, target)
            else -> PreloadBufferState.Error("Native buffer below target for tag=$tag")
        }
        AdLogger.d(AdFormat.NATIVE, tag, "POLL", "success=${ad != null} remaining=${cache.size}")
        return ad
    }

    /** Polls one buffered ad. Refill is explicit; polling must not create an implicit load. */
    fun get(context: Activity, tag: String): ApNativeAd? {
        return get(tag)
    }

    private fun isExpired(tag: String, spec: NativeAdSpec): Boolean {
        if (spec.ttlMs <= 0L) return false
        val startedAt = preloadStartedAt[tag] ?: return false
        return System.currentTimeMillis() - startedAt >= spec.ttlMs
    }

    private suspend fun loadNativeWaterfall(
        context: android.content.Context,
        tag: String,
        adUnitIds: List<String>,
    ): Pair<ApNativeAd, String>? {
        val options = specs[tag]?.requestOptions ?: NativeAdRequestOptions()
        val ids = specs[tag]?.weightedAdUnits()
            ?.sortedByDescending { it.weight }
            ?.map { it.adUnitId }
            ?: adUnitIds
        for (id in ids) {
            val ad = loadOneNative(context, tag, id, options)
            if (ad != null) return ad to id
        }
        return null
    }

    private suspend fun loadOneNative(
        context: android.content.Context,
        tag: String,
        id: String,
        options: NativeAdRequestOptions,
    ): ApNativeAd? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                NativeAdManager.loadNativeAdResultCallback(
                    context = context,
                    id = id,
                    layoutCustomNative = 0,
                    options = options,
                    callback = object : NativeAdListener {
                        override fun onLoaded(ad: ApNativeAd) {
                            if (continuation.isActive) continuation.resume(ad)
                            else runCatching { ad.nativeAd?.destroy() }
                        }

                        override fun onFailed(error: ApAdError) {
                            AdLogger.d(AdFormat.NATIVE, tag, "LOAD_FAILED", "id=$id error=$error")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }

    private const val MAX_PARALLEL_NATIVE_PRELOADS = 3
    private const val MAX_OFFERED_NATIVE_BUFFER = 3
}
