package com.lib.ads.gma.ads.helper.adnative.preload

import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.PreloadBufferState
import com.lib.ads.gma.ads.manager.NativeAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.ads.model.wrapper.resolvedAdUnitId
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import com.lib.ads.gma.ads.util.AppLogger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives one native holder's requests, shared by `createNativeAdHolder` (XML) and
 * `rememberNativeAdPreload` (Compose): consume the cached ad for [tag] (waiting for a preload
 * already in flight), otherwise cold-load the placement's ad unit ids as a waterfall.
 *
 * Every request/reload/resume follows the same rule: a request already in flight is reused; a new
 * ad replaces (and destroys) the one on screen; a failure keeps the ad on screen untouched and only
 * an empty holder moves to Error.
 */
internal class NativePreloadRequestController(
    private val tag: String,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
    private val options: NativeAdPreloadHolderOptions,
    private val state: MutableStateFlow<NativeAdDisplayState>,
    private val callbackRelay: NativeAdListener,
    private val lastError: MutableStateFlow<String?>,
) {
    /** Ids used for a cold load: the registered spec's ids, else the holder's fallback ids. */
    private val ids: List<String> = resolveAdUnitIds()
    private var job: Job? = null
    private var loadInFlight = false

    fun request(): Job {
        // NativeAdManager reports through a later SDK callback, after this coroutine may already
        // have completed. A Job.isActive check alone therefore cannot prevent duplicate loads.
        // A canceled job means the caller explicitly requested a replacement via reload().
        if (loadInFlight && job?.isCancelled != true) return job!!
        loadInFlight = true
        job?.cancel()
        return scope.launch(Dispatchers.Main.immediate) { load() }.also { job = it }
    }

    fun cancel() {
        job?.cancel()
        job = null
        loadInFlight = false
    }

    /** Wires click/impression/paid tracking onto the SDK object, relayed to registered callbacks. */
    fun bindAdCallback(ad: ApNativeAd) {
        val nativeAd = ad.nativeAd ?: return
        val context = AdsProvider.getInstance().applicationContextOrNull() ?: return
        NativeAdManager.bindNativeAdEventCallback(context, nativeAd, callbackRelay)
    }

    /**
     * Registers the holder's fallback spec when nothing is registered for [tag], so the placement
     * layer can track this tag's buffer state.
     */
    private fun resolveAdUnitIds(): List<String> {
        val registeredIds = NativeAds.adUnitIds(tag).filter(String::isNotBlank).distinct()
        if (registeredIds.isNotEmpty()) return registeredIds

        val fallbackIds = options.fallbackAdUnitIds.filter(String::isNotBlank).distinct()
        if (fallbackIds.isEmpty()) {
            AdLogger.w(AdFormat.NATIVE, tag, "CONFIG", "no NativeAdSpec registered for tag; preload/poll disabled")
            return emptyList()
        }
        AdLogger.d(AdFormat.NATIVE, tag, "CONFIG", "tag not registered; using holder fallback for cold load ids=$fallbackIds")
        NativeAds.register(
            NativeAdSpec(
                tag = tag,
                adUnitIds = fallbackIds,
                canShowAds = true,
                canReloadAds = true,
            ),
        )
        return fallbackIds
    }

    private suspend fun load() {
        if (!enabled || !options.canShowAds) {
            loadInFlight = false
            state.value = NativeAdDisplayState.Cancelled(options.adOptionVisibility)
            return
        }
        val myJob = coroutineContext[Job]
        val previousAd = state.value as? NativeAdDisplayState.Success
        // Do not flash the loading UI when a preload is already available. The holder stays in
        // its current state until the cached ad is consumed and the Success state is published.
        if (previousAd == null && NativeAds.available(tag) == 0) {
            state.value = NativeAdDisplayState.Loading
        }

        val preloaded = awaitOrPollPreloadedNative(tag, options.timeoutPerIdMs)
        if (preloaded != null) {
            loadInFlight = false
            publishSuccess(preloaded.ad, preloaded.adUnitId, fromPreload = true, previousAd)
            return
        }

        // Loading never needs an Activity; the SDK only needs the application context.
        val context = AdsProvider.getInstance().applicationContextOrNull()
        if (ids.isEmpty() || context == null) {
            loadInFlight = false
            publishFailure(ApAdError("No fallback ad unit ids and nothing preloaded"), previousAd)
            return
        }

        NativeAdManager.loadNativeListTimeOut(
            context = context,
            listId = ids,
            timeOutPerId = listOf(options.timeoutPerIdMs),
            layoutCustomNative = 0,
            callback = object : NativeAdListener {
                override fun onLoaded(ad: ApNativeAd) {
                    // A newer request (reload/cancel) owns the holder now. Keep the ad for the next
                    // request instead of dropping a matched request.
                    if (job !== myJob) {
                        NativeAds.offer(tag, ad)
                        return
                    }
                    loadInFlight = false
                    publishSuccess(ad, ad.resolvedAdUnitId().orEmpty(), fromPreload = false, previousAd)
                }

                override fun onFailed(error: ApAdError) {
                    if (job !== myJob) return
                    loadInFlight = false
                    publishFailure(error, previousAd)
                }
            },
            options = options.requestOptions,
            onLateAd = { late -> NativeAds.offer(tag, late) },
        )
    }

    private fun publishSuccess(
        ad: ApNativeAd,
        adUnitId: String,
        fromPreload: Boolean,
        previousAd: NativeAdDisplayState.Success?,
    ) {
        lastError.value = null
        bindAdCallback(ad)
        callbackRelay.onLoaded(ad)
        previousAd?.let { old -> runCatching { old.ad.nativeAd?.destroy() } }
        state.value = NativeAdDisplayState.Success(ad, adUnitId, fromPreload)
    }

    /**
     * A failed refresh keeps the ad already on screen and does not report onFailed: callbacks mirror
     * the holder state, and the holder still shows a loaded ad. Only an empty holder moves to Error.
     */
    private fun publishFailure(error: ApAdError, previousAd: NativeAdDisplayState.Success?) {
        val reason = error.diagnosticLines().joinToString("\n")
        lastError.value = if (previousAd != null) "refresh failed:\n$reason" else reason
        if (previousAd != null) {
            AdLogger.d(AdFormat.NATIVE, tag, "REFRESH_FAILED", "keeping current ad error=$error")
            return
        }
        callbackRelay.onFailed(error)
        state.value = NativeAdDisplayState.Error(null)
    }
}

internal data class PreloadedNative(
    val ad: ApNativeAd,
    val adUnitId: String,
)

/**
 * Consumes an available preload immediately. If a preload for these ids is already running,
 * waits for its terminal state before deciding whether a cold request is needed. This prevents
 * screen B from issuing a second GMA request while screen A is still warming the same cache.
 */
internal suspend fun awaitOrPollPreloadedNative(
    tag: String,
    timeoutMs: Long,
): PreloadedNative? {
    fun poll(): PreloadedNative? = NativeAds.get(tag)?.let {
        PreloadedNative(it, it.resolvedAdUnitId().orEmpty())
    }

    poll()?.let { return it }
    if (NativeAds.stateFlow(tag).value !is PreloadBufferState.Loading &&
        NativeAds.stateFlow(tag).value !is PreloadBufferState.ItemLoaded
    ) return null

    AppLogger.d(tag, "cache miss; waiting for existing preload timeoutMs=$timeoutMs")
    val terminalState = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L).milliseconds) {
        NativeAds.stateFlow(tag).first {
            it is PreloadBufferState.Ready ||
                it is PreloadBufferState.Error ||
                it is PreloadBufferState.Cancelled
        }
    }
    AppLogger.d(tag, "existing preload resolved state=${terminalState ?: "Timeout"}")

    // Complete means the SDK has finished warming; poll once more because another consumer may
    // have consumed the ad between the state callback and this poll. If it is gone, the caller
    // falls through to the normal cold-request path.
    return poll()
}
