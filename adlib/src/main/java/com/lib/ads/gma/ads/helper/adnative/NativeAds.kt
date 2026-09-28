package com.lib.ads.gma.ads.helper.adnative

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.nativead.NativeAd
import com.lib.ads.gma.ads.admob.AdsConsentManager
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.helper.adnative.core.NativeAdLoader
import com.lib.ads.gma.ads.helper.adnative.provider.NativeAdEventRelay
import com.lib.ads.gma.ads.manager.NativeAdManager
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single public entry point for native ads.
 *
 * Typical usage:
 * ```
 * // once, at app start
 * NativeAds.register(NativeAdSpec.simple("home", BuildConfig.ad_native, R.layout.native_common, bufferSize = 2))
 * NativeAds.preload(context, "home")
 *
 * // anywhere a fresh ad is needed
 * val ad = NativeAds.get("home")               // pop one (or null)
 * NativeAds.show(activity, ad, container, shimmer)
 *
 * // or wait for one
 * val ad2 = NativeAds.await("home", timeoutMs = 8_000)
 * ```
 *
 * All loading is delegated to [NativeAdLoader] so cold loads and preloaded buffer
 * loads behave identically (paid logging, impression/click tracking, [AdLoadStats]).
 */
object NativeAds {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Caps concurrent native loads across all tags to avoid request floods. */
    private val globalSemaphore = Semaphore(permits = 3)

    private data class BufferEntry(
        val ad: ApNativeAd,
        val adUnitId: String,
        val responseInfo: ResponseInfo?,
        val loadedAt: Long,
        val relay: NativeAdEventRelay
    )

    private class Slot(@Volatile var spec: NativeAdSpec) {
        val buffer = ArrayDeque<BufferEntry>()
        var warmJob: Job? = null
        @Volatile var loadContextRef: WeakReference<Context>? = null
        val state = MutableStateFlow<PreloadBufferState>(PreloadBufferState.Idle)
        val callbacks = CopyOnWriteArrayList<AdsCallback>()

        /** Update the buffer state and log the transition (no-op log if unchanged). */
        fun setState(newState: PreloadBufferState) {
            val previous = state.value
            state.value = newState
            if (previous != newState) {
                NativeAdLog.d("state", spec.tag, "${previous.describe()} -> ${newState.describe()} (buffered=${buffer.size})")
            }
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** How many live owners (screens/holders) currently want [tag] kept warm. */
    private val activeOwners = ConcurrentHashMap<String, AtomicInteger>()

    // ==================== LIFECYCLE / OWNERSHIP ====================

    /**
     * Mark that a live owner (a screen / helper / holder) wants [tag] preloaded.
     * Pair every call with [release] when the owner is destroyed so the background
     * warm job does not keep requesting ads after the screen is gone
     * (which wastes requests and lowers show rate).
     */
    fun acquire(tag: String) {
        val n = activeOwners.getOrPut(tag) { AtomicInteger(0) }.incrementAndGet()
        NativeAdLog.d("preload", tag, "acquire -> activeOwners=$n")
    }

    /**
     * Release one owner of [tag]. When the count reaches zero the warm job is stopped
     * **and the buffer is cleared** — any ad that is no longer going to be shown is
     * destroyed rather than left to lower the show rate. A later [acquire]+[preload]
     * starts a fresh fill.
     */
    fun release(tag: String) {
        val counter = activeOwners[tag] ?: return
        val n = counter.decrementAndGet()
        NativeAdLog.d("preload", tag, "release -> activeOwners=$n")
        if (n <= 0) {
            activeOwners.remove(tag)
            NativeAdLog.d("preload", tag, "no active owners — stopping preload and clearing buffer")
            clear(tag)
        }
    }

    // ==================== REGISTRATION ====================

    /** Register (or update) a placement spec by its tag. Call once at app start. */
    fun register(spec: NativeAdSpec) = apply {
        val slot = slots[spec.tag]
        if (slot == null) {
            slots[spec.tag] = Slot(spec)
            NativeAdLog.d(
                "register", spec.tag,
                "registered: units=${spec.getAllAdUnitIds()}, bufferSize=${spec.bufferSize}, " +
                    "ttlMs=${spec.ttlMs}"
            )
        } else {
            slot.spec = spec
            NativeAdLog.d("register", spec.tag, "updated: units=${spec.getAllAdUnitIds()}, bufferSize=${spec.bufferSize}")
        }
    }

    fun isRegistered(tag: String): Boolean = slots.containsKey(tag)

    fun specOf(tag: String): NativeAdSpec? = slots[tag]?.spec

    // ==================== PRELOAD ====================

    /**
     * Start filling the buffer for [tag] up to [count] (defaults to the spec's bufferSize).
     * Requires a prior [register]. Fills the buffer once to the requested target.
     */
    fun preload(context: Context, tag: String, count: Int? = null) {
        val slot = slots[tag] ?: run {
            NativeAdLog.w("preload", tag, "preload() called before register() — ignored")
            return
        }
        val target = count ?: slot.spec.bufferSize
        NativeAdLog.d("preload", tag, "preload() requested target=$target, currentlyBuffered=${available(tag)}")
        // Preserve an Activity context when supplied: Unity Ads mediation requires it to load
        // native ads. The slot stores only a weak reference so preloading cannot leak a screen.
        startWarm(context, slot, target)
    }

    /** Convenience: register a spec on the fly and immediately preload it. */
    fun preload(
        context: Context,
        tag: String,
        adUnitIds: List<String>,
        bufferSize: Int = 1,
        @LayoutRes layoutId: Int = 0,
    ) {
        register(NativeAdSpec.waterfall(tag, adUnitIds, layoutId, bufferSize))
        preload(context, tag, bufferSize)
    }

    private fun startWarm(loadContext: Context, slot: Slot, count: Int) {
        val spec = slot.spec
        if (!canRequestLoad(loadContext)) {
            NativeAdLog.d(
                "preload", spec.tag,
                "skip warm: purchased=${AppPurchase.getInstance().isPurchased()}, " +
                    "consent=${AdsConsentManager.getConsentResult(loadContext)}, online=${isOnline(loadContext)}"
            )
            return
        }
        if (slot.warmJob?.isActive == true) {
            NativeAdLog.d("preload", spec.tag, "warm job already running — skip")
            return
        }
        val ids = spec.getAllAdUnitIds()
        if (ids.isEmpty()) {
            NativeAdLog.w("preload", spec.tag, "no ad unit ids configured — skip warm")
            return
        }

        slot.loadContextRef = WeakReference(loadContext)
        // Fill the buffer to target exactly once. No background polling: once full we stop
        // requesting; consuming an ad never starts another fill.
        NativeAdLog.d("preload", spec.tag, "start warm: fill to $count, queue now=${available(spec.tag)}, units=$ids")
        slot.warmJob = appScope.launch {
            fillTo(loadContext, slot, count)
            NativeAdLog.d("preload", spec.tag, "warm finished (buffered=${available(spec.tag)}/$count)")
        }
    }

    private suspend fun fillTo(appCtx: Context, slot: Slot, target: Int) {
        val spec = slot.spec
        prune(spec.tag)
        var loaded = available(spec.tag)
        val want = target.coerceAtLeast(1)
        NativeAdLog.d("preload", spec.tag, "fillTo target=$want, alreadyBuffered=$loaded")
        slot.setState(PreloadBufferState.Loading(requested = want, loaded = loaded))

        while (slot.warmJob?.isActive != false && loaded < want) {
            NativeAdLog.d("preload", spec.tag, "loading item ${loaded + 1}/$want via waterfall ${spec.getAllAdUnitIds()}")
            val result = try {
                globalSemaphore.withPermit {
                    NativeAdLoader.waterfall(
                        context = appCtx,
                        adUnitIds = spec.getAllAdUnitIds(),
                        timeoutsMs = spec.timeoutsMs,
                        tag = spec.tag,
                    )
                }
            } catch (e: CancellationException) {
                NativeAdLog.d("preload", spec.tag, "warm cancelled at item ${loaded + 1}/$want")
                slot.setState(PreloadBufferState.Cancelled)
                throw e
            } catch (e: Exception) {
                NativeAdLog.e("preload", spec.tag, "warm error: ${e.message}")
                null
            }
            if (result == null) {
                NativeAdLog.w("preload", spec.tag, "waterfall returned no fill — stop at buffered=${slot.buffer.size}/$want")
                break
            }

            slot.buffer.addLast(
                BufferEntry(
                    ad = ApNativeAd(result.ad),
                    adUnitId = result.adUnitId,
                    responseInfo = result.ad.responseInfo,
                    loadedAt = System.currentTimeMillis(),
                    relay = result.relay
                )
            )
            loaded++
            slot.setState(PreloadBufferState.ItemLoaded(result.adUnitId, loaded, want))
            NativeAdLog.d("buffer", spec.tag, "added -> buffered=${slot.buffer.size}/$want", unit = result.adUnitId)
        }
        slot.setState(PreloadBufferState.Ready(available = slot.buffer.size))
    }

    fun stopPreload(tag: String) {
        NativeAdLog.d("preload", tag, "stopPreload() — cancelling warm job")
        slots[tag]?.warmJob?.cancel()
    }

    // ==================== CONSUME ====================

    /**
     * Pop one preloaded ad, ready to display (layout resolved). Returns null if empty.
     * Impression/click are still tracked globally; for per-ad callbacks use [getDetailed].
     */
    fun get(tag: String): ApNativeAd? = getDetailed(tag)?.let { detail ->
        ApNativeAd().apply {
            admobNativeAd = detail.nativeAd
            layoutCustomNative = slots[tag]?.spec?.getLayoutIdByMediationNativeAd(detail.nativeAd) ?: 0
        }
    }

    /** Pop one preloaded ad with its metadata + event relay (for helpers/advanced callers). */
    fun getDetailed(tag: String): PreloadedNativeAd? {
        prune(tag)
        val slot = slots[tag]
        val entry = slot?.buffer?.pollFirst()
        if (entry == null) {
            NativeAdLog.d("buffer", tag, "getDetailed() MISS — buffer empty (will cold-load)")
            return null
        }
        AdLoadStats.recordServed(AdType.NATIVE, tag, entry.adUnitId, fromBuffer = true)
        NativeAdLog.d("buffer", tag, "getDetailed() HIT ageMs=${System.currentTimeMillis() - entry.loadedAt}, remaining=${available(tag)}", unit = entry.adUnitId)
        return PreloadedNativeAd(entry.ad.admobNativeAd, entry.adUnitId, entry.responseInfo, entry.loadedAt, entry.relay)
    }

    /** Preview the next buffered ad without consuming it. */
    fun peek(tag: String): PreloadedNativeAd? {
        prune(tag)
        val entry = slots[tag]?.buffer?.peekFirst() ?: return null
        NativeAdLog.d("buffer", tag, "peek() -> available=${available(tag)}", unit = entry.adUnitId)
        return PreloadedNativeAd(entry.ad.admobNativeAd, entry.adUnitId, entry.responseInfo, entry.loadedAt, entry.relay)
    }

    /** Suspend until a buffered ad is available (or [timeoutMs]), then pop it. */
    suspend fun await(tag: String, timeoutMs: Long = 12_000L): ApNativeAd? =
        if (awaitAvailable(tag, 1, timeoutMs)) get(tag) else null

    suspend fun awaitAvailable(tag: String, minCount: Int = 1, timeoutMs: Long = 12_000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            if (available(tag) >= minCount) return@withTimeoutOrNull true
            NativeAdLog.d("buffer", tag, "awaitAvailable(min=$minCount, timeoutMs=$timeoutMs) — waiting, current=${available(tag)}")
            stateFlow(tag).first { s ->
                available(tag) >= minCount ||
                    s is PreloadBufferState.Ready ||
                    s is PreloadBufferState.Error ||
                    s is PreloadBufferState.Cancelled
            }
            available(tag) >= minCount
        } ?: run {
            NativeAdLog.w("buffer", tag, "awaitAvailable timed out after ${timeoutMs}ms, current=${available(tag)}")
            false
        }

    // ==================== DISPLAY ====================

    /**
     * Inflate [ad] into [container] (hiding [shimmer]). Uses [layoutOverride] if > 0,
     * otherwise the ad's resolved layout.
     */
    fun show(
        activity: Activity,
        ad: ApNativeAd?,
        container: FrameLayout?,
        shimmer: ShimmerFrameLayout? = null,
        @LayoutRes layoutOverride: Int = 0,
    ) {
        if (ad == null) {
            shimmer?.visibility = android.view.View.GONE
            return
        }
        if (layoutOverride != 0) ad.layoutCustomNative = layoutOverride
        NativeAdManager.populateNativeAdView(activity, ad, container, shimmer)
    }

    // ==================== STATE / QUERY ====================

    fun stateFlow(tag: String): StateFlow<PreloadBufferState> =
        slots[tag]?.state?.asStateFlow()
            ?: MutableStateFlow<PreloadBufferState>(PreloadBufferState.Idle).asStateFlow()

    fun available(tag: String): Int {
        prune(tag)
        return slots[tag]?.buffer?.size ?: 0
    }

    fun isAvailable(tag: String): Boolean = available(tag) > 0

    fun isLoading(tag: String): Boolean = slots[tag]?.warmJob?.isActive == true

    fun registerCallback(tag: String, callback: AdsCallback) {
        slots[tag]?.callbacks?.add(callback)
    }

    fun unregisterCallback(tag: String, callback: AdsCallback) {
        slots[tag]?.callbacks?.remove(callback)
    }

    // ==================== CLEANUP ====================

    fun clear(tag: String) {
        slots[tag]?.let { slot ->
            NativeAdLog.d("buffer", tag, "clear() — destroying ${slot.buffer.size} buffered ad(s)")
            slot.warmJob?.cancel()
            slot.buffer.forEach { it.relay.clearListeners(); it.ad.destroy() }
            slot.buffer.clear()
            slot.callbacks.clear()
            slot.loadContextRef = null
            slot.setState(PreloadBufferState.Idle)
        }
    }

    fun clearAll() {
        NativeAdLog.d("buffer", null, "clearAll() — destroying ${slots.size} slot(s)")
        slots.values.forEach { slot ->
            slot.warmJob?.cancel()
            slot.buffer.forEach { it.relay.clearListeners(); it.ad.destroy() }
            slot.buffer.clear()
            slot.callbacks.clear()
            slot.loadContextRef = null
        }
        slots.clear()
    }

    // ==================== INTERNAL ====================

    private fun canRequestLoad(context: Context): Boolean =
        !AppPurchase.getInstance().isPurchased() &&
            AdsConsentManager.getConsentResult(context) &&
            isOnline(context)

    private fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun prune(tag: String) {
        val slot = slots[tag] ?: return
        val now = System.currentTimeMillis()
        var pruned = 0
        val it = slot.buffer.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.loadedAt >= slot.spec.ttlMs) {
                NativeAdLog.d("buffer", tag, "prune expired ageMs=${now - entry.loadedAt} (ttl=${slot.spec.ttlMs})", unit = entry.adUnitId)
                entry.relay.clearListeners()
                entry.ad.destroy()
                it.remove()
                pruned++
            }
        }
        if (pruned > 0) NativeAdLog.d("buffer", tag, "pruned $pruned expired ad(s), remaining=${slot.buffer.size}")
    }
}

/** Status of a preload buffer for a tag. */
sealed class PreloadBufferState {
    data object Idle : PreloadBufferState()
    data class Loading(val requested: Int, val loaded: Int) : PreloadBufferState()
    data class ItemLoaded(val adUnitId: String, val loaded: Int, val requested: Int) : PreloadBufferState()
    data class Ready(val available: Int) : PreloadBufferState()
    data class Error(val message: String) : PreloadBufferState()
    data object Cancelled : PreloadBufferState()
}

/** A buffered native ad with metadata and its travelling event relay. */
data class PreloadedNativeAd(
    val nativeAd: NativeAd?,
    val adUnitId: String,
    val responseInfo: ResponseInfo?,
    val loadedAt: Long,
    val eventRelay: NativeAdEventRelay = NativeAdEventRelay()
) {
    val ageMs: Long get() = System.currentTimeMillis() - loadedAt
}
