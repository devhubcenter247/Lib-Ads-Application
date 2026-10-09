package com.lib.ads.gma.ads.helper.banner

import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.manager.BannerAdManager
import com.lib.ads.gma.ads.helper.banner.params.BannerPreloadState
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.util.AppLogger
import com.lib.ads.gma.ads.util.AdLogger
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.shortAdUnit
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import android.util.LruCache
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private data class CachedBanner(val ad: BannerAd, val sourceView: AdView)

object BannerAds {
    private const val TAG = "BannerAds"
    private val states = ConcurrentHashMap<String, MutableStateFlow<BannerPreloadState>>()
    private val specs = ConcurrentHashMap<String, BannerAdSpec>()
    private val preloadStartedAt = ConcurrentHashMap<String, Long>()
    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val targetBuffers = ConcurrentHashMap<String, Int>()
    /** Number of banner load slots currently running for each placement key. */
    private val inFlightRequests = ConcurrentHashMap<String, AtomicInteger>()
    /** Total banner load slots started for each placement key, for diagnostics. */
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val generations = ConcurrentHashMap<String, AtomicInteger>()
    private val tagCaches = object : LruCache<String, ArrayDeque<CachedBanner>>(32) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: ArrayDeque<CachedBanner>, newValue: ArrayDeque<CachedBanner>?) {
            if (evicted) oldValue.forEach { destroyCached(it) }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val MAX_PARALLEL_BANNER_PRELOADS = 3
    private const val MAX_UNCLAIMED_BANNER_BUFFER = 3

    private fun state(tag: String) = states.getOrPut(tag) { MutableStateFlow(BannerPreloadState.Idle) }
    fun isRegistered(tag: String): Boolean = specs.containsKey(tag)
    fun register(spec: BannerAdSpec) {
        val previous = specs[spec.tag]
        if (previous != null && (previous.adUnitIds != spec.adUnitIds || previous.placementId != spec.placementId || previous.widthDp != spec.widthDp || previous.heightConfig != spec.heightConfig || previous.maxHeightDp != spec.maxHeightDp || previous.collapsibleGravity != spec.collapsibleGravity || previous.customWidthDp != spec.customWidthDp || previous.customHeightDp != spec.customHeightDp)) {
            preloadJobs.remove(spec.tag)?.cancel()
            tagCaches.remove(spec.tag)?.forEach { destroyCached(it) }
            generations.getOrPut(spec.tag) { AtomicInteger() }.incrementAndGet()
            targetBuffers.remove(spec.tag)
            inFlightRequests.remove(spec.tag)
            requestCounts.remove(spec.tag)
            state(spec.tag).value = BannerPreloadState.Idle
        }
        specs[spec.tag] = spec
        state(spec.tag)
        AdLogger.d(AdFormat.BANNER, spec.tag, "REGISTER", "ids=${spec.adUnitIds} buffer=${spec.bufferSize} widthDp=${spec.widthDp} heightConfig=${spec.heightConfig} collapsibleGravity=${spec.collapsibleGravity}")
    }

    fun preload(tag: String, adUnitIds: List<String>, placementId: Long? = null) {
        val spec = specs[tag]
        if (spec == null || spec.adUnitIds != adUnitIds || spec.placementId != placementId) {
            register(BannerAdSpec(tag, adUnitIds, placementId))
        }
        val current = specs[tag]!!
        preloadInternal(current, force = false)
    }

    fun safePreload(spec: BannerAdSpec) { register(spec); preloadInternal(spec, force = false) }
    fun forcePreload(spec: BannerAdSpec) { register(spec); preloadInternal(spec, force = true) }

    private fun preloadInternal(spec: BannerAdSpec, force: Boolean) {
        val tag = spec.tag
        val target = spec.bufferSize.coerceAtLeast(1)
        val previousTarget = targetBuffers[tag] ?: 0
        if (force || target > previousTarget) targetBuffers[tag] = target
        val requestedTarget = targetBuffers[tag] ?: target
        val cache = tagCaches.get(tag) ?: ArrayDeque<CachedBanner>().also { tagCaches.put(tag, it) }
        if (!force && isExpired(tag, specs[tag] ?: spec)) {
            preloadJobs.remove(tag)?.cancel()
            cache.forEach { destroyCached(it) }
            cache.clear()
            generations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        }
        if (force) {
            preloadJobs.remove(tag)?.cancel()
            cache.forEach { destroyCached(it) }
            cache.clear()
            inFlightRequests.remove(tag)
            requestCounts[tag]?.set(0)
            generations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        } else if (cache.size >= requestedTarget) {
            state(tag).value = BannerPreloadState.Ready(cache.size)
            return
        } else if (preloadJobs[tag]?.isActive == true) {
            if (requestedTarget <= previousTarget) return
            preloadJobs.remove(tag)?.cancel()
            generations.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        }
        val generation = generations.getOrPut(tag) { AtomicInteger() }.get()
        state(tag).value = BannerPreloadState.Loading
        preloadStartedAt[tag] = System.currentTimeMillis()
        val workerCount = (requestedTarget - cache.size).coerceAtLeast(1).coerceAtMost(MAX_PARALLEL_BANNER_PRELOADS)
        AdLogger.d(AdFormat.BANNER, tag, "PRELOAD_START", "ids=${spec.adUnitIds} target=$requestedTarget parallel=$workerCount widthDp=${spec.widthDp} heightConfig=${spec.heightConfig}")
        preloadJobs[tag] = scope.launch {
            val context = AdsProvider.getInstance().applicationContextOrNull()
            if (context == null || spec.adUnitIds.isEmpty()) {
                state(tag).value = BannerPreloadState.Empty
                return@launch
            }
            val reservedSlots = AtomicInteger(cache.size)
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
                                loadBannerWaterfall(context, spec)
                            } finally {
                                inFlight.decrementAndGet()
                            }
                            if (result != null && generations[tag]?.get() == generation) {
                                cache.addLast(result)
                                val winningId = result.ad.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
                                AdLogger.d(AdFormat.BANNER, tag, "FILL", "id=$winningId cached=${cache.size} target=$requestedTarget")
                                // Keep ItemLoaded until every worker has finished. Publishing Ready
                                // here lets a holder poll the last item while sibling workers are
                                // still running, after which the final state can incorrectly be
                                // reported as Empty.
                                state(tag).value = BannerPreloadState.ItemLoaded(
                                    loaded = cache.size,
                                    requested = requestedTarget,
                                )
                            } else if (result != null) {
                                destroyCached(result)
                                break
                            } else {
                                reservedSlots.decrementAndGet()
                                break
                            }
                        }
                    }
                }.awaitAll()
            }
            if (generations[tag]?.get() != generation) return@launch
            preloadJobs.remove(tag)
            state(tag).value = if (cache.size >= requestedTarget) BannerPreloadState.Ready(cache.size) else BannerPreloadState.Empty
            AdLogger.d(AdFormat.BANNER, tag, if (cache.size >= requestedTarget) "PRELOAD_COMPLETE" else "PRELOAD_EMPTY", "cached=${cache.size} target=$requestedTarget")
        }
    }
    fun preload(spec: BannerAdSpec) { safePreload(spec) }
    fun preload(tag: String) {
        val spec = specs[tag] ?: run {
            state(tag).value = BannerPreloadState.Cancelled
            return
        }
        preloadInternal(spec, force = false)
    }

    /** Buffers [count] banners for [tag] with its registered spec; no-op if the tag is unknown. */
    fun preload(tag: String, count: Int) {
        val spec = specs[tag] ?: return
        preloadInternal(
            BannerAdSpec(
                tag = tag,
                adUnitIds = spec.adUnitIds,
                placementId = spec.placementId,
                bufferSize = count,
                ttlMs = spec.ttlMs,
                size = spec.size,
                collapsibleGravity = spec.collapsibleGravity,
            ),
            force = false,
        )
    }

    fun stateFlow(tag: String): StateFlow<BannerPreloadState> = state(tag)

    fun available(tag: String): Int {
        val spec = specs[tag] ?: return 0
        if (isExpired(tag, spec)) return 0
        return tagCaches.get(tag)?.size ?: 0
    }

    fun requestCount(tag: String): Int = requestCounts[tag]?.get() ?: 0

    fun inFlightRequests(tag: String): Int = inFlightRequests[tag]?.get() ?: 0

    /**
     * Which ad unit id in this tag's waterfall currently has a ready cached ad, without consuming it
     * — e.g. `idHigh` failed to preload but `idNormal` succeeded. Use this for per-placement
     * fill-rate debugging instead of only a pass/fail for the whole list.
     */
    fun readyAdUnitId(tag: String): String? {
        val spec = specs[tag] ?: return null
        return tagCaches.get(tag)?.firstOrNull()?.ad?.getResponseInfo()?.extractAdUnitIdOrNull()
    }

    /** Replaces cached ads whose configured TTL has elapsed before they are consumed. */
    fun ensureFresh(tag: String) {
        val spec = specs[tag] ?: return
        if (isExpired(tag, spec)) preload(spec)
    }

    /** Starts a new normal load when this placement has no cached banner available. */
    fun ensureAvailable(
        tag: String,
        size: BannerSize? = null,
        collapsibleGravity: BannerCollapseGravity? = null,
    ) {
        val spec = specs[tag] ?: run {
            AppLogger.w(TAG, "ensureAvailable tag=$tag failed: no registered spec")
            return
        }
        val requestedSize = size ?: spec.size
        val requestedCollapsibleGravity = collapsibleGravity ?: spec.collapsibleGravity
        val hasCorrectCache = available(tag) >= spec.bufferSize && requestedSize == spec.size && requestedCollapsibleGravity == spec.collapsibleGravity
        if (isExpired(tag, spec) || !hasCorrectCache) {
            AdLogger.d(AdFormat.BANNER, tag, "ENSURE", "restarting normal load")
            val requestSpec = spec.copyForRequest(requestedSize, requestedCollapsibleGravity)
            if (requestSpec.size != spec.size || requestSpec.collapsibleGravity != spec.collapsibleGravity) {
                register(requestSpec)
            }
            preloadInternal(specs[tag] ?: requestSpec, force = false)
        }
    }

    /**
     * Polls a cached banner for the registered spec. The returned [AdView] is the view that loaded
     * the ad with the application context, so no Activity is needed to consume it.
     */
    fun get(tag: String, callback: BannerAdListener = object : BannerAdListener {}): AdView? {
        val spec = specs[tag] ?: run {
            AppLogger.w(TAG, "get tag=$tag ignored: no registered spec")
            return null
        }
        if (isExpired(tag, spec)) {
            AppLogger.w(TAG, "get tag=$tag ignored: preload expired")
            return null
        }
        val cache = tagCaches.get(tag)
        val cached = cache?.let { if (it.isEmpty()) null else it.removeFirst() }
        val adView = cached?.let {
            // Re-point click/impression/paid events from the loader's internal listener to the caller.
            val appContext = it.sourceView.context.applicationContext
            BannerAdManager.bindBannerAdListener(appContext, it.ad, callback)
            it.sourceView
        }
        val target = targetBuffers[tag] ?: spec.bufferSize
        // Polling only consumes the buffer. Preload is explicit and never refills implicitly.
        state(tag).value = when {
            cache == null || cache.size >= target -> BannerPreloadState.Ready(cache?.size ?: 0)
            inFlightRequests(tag) > 0 || preloadJobs[tag]?.isActive == true -> BannerPreloadState.Loading
            else -> BannerPreloadState.Empty
        }
        AdLogger.d(AdFormat.BANNER, tag, "POLL", "result=${adView != null} available=${cache?.size ?: 0}")
        return adView
    }

    /**
     * Loads one banner for a holder whose poll found nothing left — e.g. several list items share
     * [tag] and a sibling already took the cached one. The result goes straight to the caller and is
     * not added to the buffer. If the caller is cancelled first (item scrolled away, timeout), the
     * banner is kept in the buffer for the next holder instead of being destroyed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun loadForHolder(
        tag: String,
        callback: BannerAdListener,
        /** Receives one `"<unit>: <reason>"` entry per ad unit that failed. */
        failures: MutableList<String> = mutableListOf(),
    ): Pair<AdView, String>? {
        val spec = specs[tag] ?: return null
        val context = AdsProvider.getInstance().applicationContextOrNull() ?: return null
        val inFlight = inFlightRequests.getOrPut(tag) { AtomicInteger() }
        requestCounts.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        inFlight.incrementAndGet()
        AdLogger.d(AdFormat.BANNER, tag, "HOLDER_LOAD", "ids=${spec.adUnitIds}")
        var callerGone = false
        val deferred = scope.async {
            val result = try {
                loadBannerWaterfall(context, spec, failures)
            } finally {
                inFlight.decrementAndGet()
            }
            if (result != null && callerGone) keepUnclaimed(tag, result)
            result
        }
        val cached = try {
            deferred.await()
        } catch (e: CancellationException) {
            // Main-thread only: either the load already finished (hand its banner to the buffer
            // here) or it will see callerGone when it does.
            if (deferred.isCompleted) runCatching { deferred.getCompleted() }.getOrNull()?.let { keepUnclaimed(tag, it) }
            else callerGone = true
            throw e
        } ?: return null
        BannerAdManager.bindBannerAdListener(cached.sourceView.context.applicationContext, cached.ad, callback)
        return cached.sourceView to cached.ad.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
    }

    private fun keepUnclaimed(tag: String, cached: CachedBanner) {
        val spec = specs[tag]
        val cache = tagCaches.get(tag)
        val capacity = maxOf(targetBuffers[tag] ?: spec?.bufferSize ?: 1, MAX_UNCLAIMED_BANNER_BUFFER)
        if (spec == null || isExpired(tag, spec) || (cache?.size ?: 0) >= capacity) {
            AdLogger.d(AdFormat.BANNER, tag, "LATE_AD", "destroyed: buffer unavailable or full")
            destroyCached(cached)
            return
        }
        preloadStartedAt.putIfAbsent(tag, System.currentTimeMillis())
        val buffer = cache ?: ArrayDeque<CachedBanner>().also { tagCaches.put(tag, it) }
        buffer.addLast(cached)
        AdLogger.d(AdFormat.BANNER, tag, "LATE_AD", "kept for next request cached=${buffer.size}")
        if (preloadJobs[tag]?.isActive != true) state(tag).value = BannerPreloadState.Ready(buffer.size)
    }

    private fun isExpired(tag: String, spec: BannerAdSpec): Boolean {
        if (spec.ttlMs <= 0L) return false
        val startedAt = preloadStartedAt[tag] ?: return false
        return System.currentTimeMillis() - startedAt >= spec.ttlMs
    }

    private fun destroyCached(cached: CachedBanner) {
        runCatching { cached.sourceView.destroy() }
        runCatching { cached.ad.destroy() }
    }

    private fun BannerAdSpec.copyForRequest(size: BannerSize, collapsibleGravity: BannerCollapseGravity?) = BannerAdSpec(
        tag = tag,
        adUnitIds = adUnitIds,
        placementId = placementId,
        bufferSize = bufferSize,
        ttlMs = ttlMs,
        size = size,
        collapsibleGravity = collapsibleGravity,
    )

    private suspend fun loadBannerWaterfall(
        context: android.content.Context,
        spec: BannerAdSpec,
        failures: MutableList<String> = mutableListOf(),
    ): CachedBanner? {
        for (id in spec.adUnitIds) {
            val result = loadOneBanner(context, spec, id, failures)
            if (result != null) return result
        }
        return null
    }

    private suspend fun loadOneBanner(
        context: android.content.Context,
        spec: BannerAdSpec,
        id: String,
        failures: MutableList<String>,
    ): CachedBanner? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var loadedAd: BannerAd? = null
                BannerAdManager.requestLoadBannerRaw(
                    context = context,
                    id = id,
                    collapsibleGravity = spec.collapsibleGravity?.value,
                    useInlineAdaptive = spec.heightConfig == BannerHeightConfig.INLINE_ADAPTIVE,
                    maxHeight = spec.maxHeightDp,
                    placementId = spec.placementId,
                    onLoadedAd = { loadedAd = it },
                    widthDp = spec.widthDp,
                    heightConfig = spec.heightConfig,
                    customWidthDp = spec.customWidthDp,
                    customHeightDp = spec.customHeightDp,
                    callback = object : BannerAdListener {
                        override fun onLoaded(adView: AdView?) {
                            val ad = loadedAd
                            if (adView != null && ad != null && continuation.isActive) continuation.resume(CachedBanner(ad, adView))
                            else if (continuation.isActive) {
                                adView?.destroy()
                                ad?.destroy()
                                failures += "${shortAdUnit(id)}: loaded without a banner view"
                                continuation.resume(null)
                            }
                            else {
                                runCatching { adView?.destroy() }
                                runCatching { ad?.destroy() }
                            }
                        }
                        override fun onFailed(error: ApAdError) {
                            AdLogger.d(AdFormat.BANNER, spec.tag, "LOAD_FAILED", "id=$id error=$error")
                            failures += "${shortAdUnit(id)}: ${error.shortDescription()}"
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }
}
