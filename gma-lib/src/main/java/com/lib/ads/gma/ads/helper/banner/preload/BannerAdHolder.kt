package com.lib.ads.gma.ads.helper.banner.preload

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

sealed class BannerAdDisplayState {
    data object Idle : BannerAdDisplayState()
    data object Loading : BannerAdDisplayState()
    data class Loaded(val adView: AdView, val adUnitId: String = "") : BannerAdDisplayState()
    data class Error(val error: LoadAdError?) : BannerAdDisplayState()
    data object Cancelled : BannerAdDisplayState()
}

@Stable
class BannerAdHolder internal constructor(
    private val _state: MutableStateFlow<BannerAdDisplayState>,
    private var job: Job?,
    private val restartBlock: (() -> Job)?,
    private val onDispose: () -> Unit,
    val loadingHeightDp: Int = DEFAULT_BANNER_LOADING_HEIGHT_DP,
    val tag: String? = null,
    private val _lastError: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    private var disposed = false
    /** Whether the banner currently on screen has recorded an impression (it has been counted). */
    private var currentAdImpressed = false
    /** Whether the banner on screen has been counted, i.e. showing it again earns no new impression. */
    internal val isCurrentAdImpressed: Boolean get() = currentAdImpressed
    private val adCallbacks = CopyOnWriteArrayList<BannerAdListener>()
    internal val callbackRelay = object : BannerAdListener {
        override fun onLoaded(adView: AdView?) {
            currentAdImpressed = false
            adCallbacks.forEach { it.onLoaded(adView) }
        }
        override fun onFailed(error: ApAdError) = adCallbacks.forEach { it.onFailed(error) }
        override fun onClicked(adView: AdView?) = adCallbacks.forEach { it.onClicked(adView) }
        override fun onImpression(adView: AdView?) {
            currentAdImpressed = true
            adCallbacks.forEach { it.onImpression(adView) }
        }
        override fun onPaid(adValue: AdValue) = adCallbacks.forEach { it.onPaid(adValue) }
        override fun onAppEvent(name: String, data: String?) =
            adCallbacks.forEach { it.onAppEvent(name, data) }

        override fun onShownFullScreenContent() =
            adCallbacks.forEach { it.onShownFullScreenContent() }

        override fun onDismissedFullScreenContent() =
            adCallbacks.forEach { it.onDismissedFullScreenContent() }

        override fun onFailedToShowFullScreenContent(error: ApAdError) =
            adCallbacks.forEach { it.onFailedToShowFullScreenContent(error) }

        override fun onRefreshed() = adCallbacks.forEach { it.onRefreshed() }
        override fun onFailedToRefresh(error: ApAdError) =
            adCallbacks.forEach { it.onFailedToRefresh(error) }
    }
    val state: StateFlow<BannerAdDisplayState> = _state.asStateFlow()

    /**
     * Why the last load of this holder failed — one line per ad unit tried
     * (`"/1234567890: NO_FILL(3): No fill."`). Kept when a refresh fails while a banner stays on
     * screen; cleared when a new banner is shown. Shown by the debug overlay.
     */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val currentState: BannerAdDisplayState get() = _state.value
    val isLoaded: Boolean get() = currentState is BannerAdDisplayState.Loaded
    val adView: AdView? get() = (currentState as? BannerAdDisplayState.Loaded)?.adView

    fun registerAdCallback(callback: BannerAdListener) {
        if (disposed) return
        if (!adCallbacks.addIfAbsent(callback)) return
        when (val state = currentState) {
            is BannerAdDisplayState.Loaded -> callback.onLoaded(state.adView)
            is BannerAdDisplayState.Error -> callback.onFailed(ApAdError("Banner ad failed"))
            else -> Unit
        }
    }

    fun unregisterAdCallback(callback: BannerAdListener) {
        adCallbacks.remove(callback)
    }

    fun cancel() {
        job?.cancel()
        destroyCurrentAd()
        _state.value = BannerAdDisplayState.Cancelled
    }

    /** Stop loading while keeping the currently displayed ad for background refresh. */
    internal fun pause() {
        job?.cancel()
        if (currentState !is BannerAdDisplayState.Loaded) {
            _state.value = BannerAdDisplayState.Cancelled
        }
    }

    /**
     * Fetches a banner for this holder. A request already in flight is reused (lifecycle/caller
     * code may spam it). When a banner is already shown it is only replaced once it has recorded
     * an impression — replacing a banner that was never counted wastes the load and lowers show
     * rate. The shown banner stays visible until a new one arrives and is kept if the request fails.
     */
    fun request() = reload(force = false)

    /**
     * Same as [request]. Pass [force] = true to replace the shown banner even before its
     * impression.
     */
    @JvmOverloads
    fun reload(force: Boolean = false) {
        if (disposed || job?.isActive == true) return
        if (!force && currentState is BannerAdDisplayState.Loaded && !currentAdImpressed) {
            AdLogger.d(AdFormat.BANNER, tag.orEmpty(), "REFRESH_SKIPPED", "current banner has no impression yet")
            return
        }
        job = restartBlock?.invoke()
    }

    internal fun dispose() {
        if (disposed) return
        disposed = true
        job?.cancel()
        adCallbacks.clear()
        onDispose()
    }

    private fun destroyCurrentAd() {
        (_state.value as? BannerAdDisplayState.Loaded)?.adView?.destroy()
    }

    companion object {
        @VisibleForTesting
        fun testInstance(
            state: MutableStateFlow<BannerAdDisplayState> = MutableStateFlow(BannerAdDisplayState.Idle)
        ): BannerAdHolder = BannerAdHolder(
            _state = state,
            job = null,
            restartBlock = null,
            onDispose = {}
        )
    }
}

internal const val DEFAULT_BANNER_LOADING_HEIGHT_DP = 56
