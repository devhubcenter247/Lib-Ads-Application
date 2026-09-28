package com.lib.ads.gma.ads.helper.banner.preload

import com.lib.ads.gma.ads.helper.banner.BannerAdSpec
import com.lib.ads.gma.ads.helper.banner.BannerAds
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.params.BannerPreloadState
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives one banner holder's requests: consume the cached banner for [tag], otherwise start a
 * normal load through [BannerAds] and wait for it. Preload is fill-once — a failed request is
 * reported to the caller and never retried implicitly.
 */
internal class BannerPreloadRequestController(
    private val tag: String,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
    private val options: BannerAdPreloadHolderOptions,
    private val state: MutableStateFlow<BannerAdDisplayState>,
    private val callbackRelay: BannerAdListener,
    private val lastError: MutableStateFlow<String?>,
) {
    private var job: Job? = null

    fun request(): Job {
        trace("request called current=${state.value} enabled=$enabled jobActive=${job?.isActive}")
        job?.cancel()
        return scope.launch(Dispatchers.Main.immediate) { load() }.also { job = it }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Registers the holder's fallback spec when nothing is registered for [tag]. The shared
     * placement registry can be cleared/replaced independently of the holder, so this also runs
     * before every request.
     */
    fun ensureRegistered() {
        if (BannerAds.isRegistered(tag)) return
        val fallbackIds = options.fallbackAdUnitIds.filter(String::isNotBlank).distinct()
        if (fallbackIds.isEmpty()) {
            AdLogger.w(AdFormat.BANNER, tag, "CONFIG", "no BannerAdSpec registered and no fallback ad unit ids")
            return
        }
        BannerAds.register(
            BannerAdSpec(
                tag = tag,
                adUnitIds = fallbackIds,
                placementId = options.placementId,
                size = options.size,
                collapsibleGravity = options.collapsibleGravity,
            ),
        )
    }

    private suspend fun load() {
        if (!enabled) {
            trace("request cancelled: holder disabled")
            state.value = BannerAdDisplayState.Cancelled
            return
        }

        ensureRegistered()
        if (!BannerAds.isRegistered(tag)) {
            val error = "Banner placement is not registered: $tag"
            trace(error)
            lastError.value = error
            state.value = BannerAdDisplayState.Error(null)
            callbackRelay.onFailed(ApAdError(error))
            return
        }

        val previousAd = state.value as? BannerAdDisplayState.Loaded
        showLoadingIfNothingToShow(previousAd)

        // Poll first. The preload configuration is authoritative for a cached banner and must not
        // be compared with the holder defaults.
        if (poll(previousAd)) return

        // A preload is already warming this tag: wait for it rather than issuing a second request.
        val preloadState = BannerAds.stateFlow(tag).value
        if (preloadState == BannerPreloadState.Loading || preloadState is BannerPreloadState.ItemLoaded) {
            showLoadingIfNothingToShow(previousAd)
            val resolved = withTimeoutOrNull(PRELOAD_WAIT_TIMEOUT_MS.milliseconds) {
                BannerAds.stateFlow(tag).first { it != BannerPreloadState.Loading && it !is BannerPreloadState.ItemLoaded }
            }
            trace("existing preload resolved state=${resolved ?: "Timeout"}")
            if (poll(previousAd)) return
        }

        // Nothing is left for this holder — several holders (e.g. list items) share the tag and a
        // sibling took the cached banner. Load this holder's own banner instead of failing, the same
        // way a native holder falls back to a cold load.
        showLoadingIfNothingToShow(previousAd)
        val failures = mutableListOf<String>()
        var finished = false
        val loaded = withTimeoutOrNull(PRELOAD_WAIT_TIMEOUT_MS.milliseconds) {
            BannerAds.loadForHolder(tag, callbackRelay, failures).also { finished = true }
        }
        if (loaded != null) {
            val (adView, adUnitId) = loaded
            trace("holder load success adUnitId=$adUnitId")
            lastError.value = null
            state.value = BannerAdDisplayState.Loaded(adView, adUnitId)
            previousAd?.let { runCatching { it.adView.destroy() } }
            callbackRelay.onLoaded(adView)
            return
        }

        if (!finished) failures += "timeout ${PRELOAD_WAIT_TIMEOUT_MS}ms waiting for the waterfall"
        val reason = failures.ifEmpty { listOf("no ad unit id to load") }.joinToString("\n")
        // A reload that fails keeps the banner already on screen and stays quiet about it.
        if (state.value is BannerAdDisplayState.Loaded) {
            lastError.value = "refresh failed:\n$reason"
            return
        }

        trace("holder load failed reason=$reason")
        lastError.value = reason
        callbackRelay.onFailed(ApAdError("Banner load failed for tag=$tag: $reason"))
        if (previousAd == null) state.value = BannerAdDisplayState.Error(null)
    }

    private fun poll(previousAd: BannerAdDisplayState.Loaded?): Boolean {
        val adUnitId = BannerAds.readyAdUnitId(tag).orEmpty()
        trace("poll attempt readyAdUnitId=$adUnitId available=${BannerAds.available(tag)}")
        val adView = BannerAds.get(tag, callbackRelay) ?: return false
        trace("poll success adUnitId=$adUnitId")
        lastError.value = null
        state.value = BannerAdDisplayState.Loaded(adView, adUnitId)
        previousAd?.let { runCatching { it.adView.destroy() } }
        callbackRelay.onLoaded(adView)
        return true
    }

    /**
     * Keeps the current UI while a ready preload is consumed, which avoids a loading flash between
     * request/reload and the cached banner becoming visible.
     */
    private fun showLoadingIfNothingToShow(previousAd: BannerAdDisplayState.Loaded?) {
        if (previousAd == null && BannerAds.available(tag) == 0) {
            state.value = BannerAdDisplayState.Loading
        }
    }

    private fun trace(message: String) {
        AdLogger.d(AdFormat.BANNER, tag, "HOLDER", message)
    }

    private companion object {
        const val PRELOAD_WAIT_TIMEOUT_MS = 15_000L
    }
}
