package com.lib.ads.gma.ads.helper.nativebanner

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.lib.adlib.R
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.params.IAdsParam
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.manager.AdmobNativeBannerManager
import com.lib.ads.gma.ads.util.AdType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────

sealed class AdmobNativeBannerState {
    object None : AdmobNativeBannerState()
    object Loading : AdmobNativeBannerState()
    object Cancel : AdmobNativeBannerState()
    data class Loaded(val nativeAd: NativeAd) : AdmobNativeBannerState()
    data class Fail(val error: ApAdError? = null) : AdmobNativeBannerState()
}

// ──────────────────────────────────────────────
// Param
// ──────────────────────────────────────────────

sealed class AdmobNativeBannerParam : IAdsParam {
    object Request : AdmobNativeBannerParam()
    data class Ready(val nativeAd: NativeAd) : AdmobNativeBannerParam()
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

class AdmobNativeBannerConfig(
    /** Ad unit ids tried in waterfall order (first = primary). A single id is a one-element list. */
    override val listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean,
    val layoutId: Int = R.layout.layout_admob_native_banner_default,
    /** Auto-reload interval once an ad is shown. 0 disables the timed reload. */
    val timeReloadMs: Long = 0L,
    /** Per-id load timeout (waterfall moves to the next id after this). */
    val loadTimeout: Long = 10_000L,
) : IAdsConfig {

    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        layoutId: Int = R.layout.layout_admob_native_banner_default,
        timeReloadMs: Long = 0L,
        loadTimeout: Long = 10_000L,
    ) : this(listOf(idAds), canShowAds, canReloadAds, layoutId, timeReloadMs, loadTimeout)
}

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

/**
 * Helper for AdMob native ads rendered in a compact banner-sized layout.
 *
 * Usage:
 * ```kotlin
 * private val admobNativeBannerHelper by lazy {
 *     AdmobNativeBannerHelper(
 *         context = this,
 *         lifecycleOwner = this,
 *         config = AdmobNativeBannerConfig(
 *             idAds = BuildConfig.ad_native,
 *             canShowAds = true,
 *             canReloadAds = true,
 *         )
 *     ).setContainer(binding.flNativeBannerAd)
 * }
 *
 * admobNativeBannerHelper.requestAds(AdmobNativeBannerParam.Request)
 * ```
 */
@OptIn(FlowPreview::class)
class AdmobNativeBannerHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val config: AdmobNativeBannerConfig,
) : AdsHelper<AdmobNativeBannerConfig, AdmobNativeBannerParam>(context, lifecycleOwner, config) {

    companion object {
        private const val TAG = "AdmobNativeBannerHelper"
    }

    private val adState: MutableStateFlow<AdmobNativeBannerState> =
        MutableStateFlow(if (canRequestAds()) AdmobNativeBannerState.None else AdmobNativeBannerState.Fail())

    private val listAdCallback: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList()
    private val resumeCount: AtomicInteger = AtomicInteger(0)

    private var container: FrameLayout? = null
    var currentNativeAd: NativeAd? = null
        private set
    private var timedReloadJob: Job? = null

    var adVisibility: AdOptionVisibility = AdOptionVisibility.GONE

    init {
        lifecycleEventState.onEach {
            if (it == Lifecycle.Event.ON_CREATE && !canRequestAds()) {
                container?.checkAdVisibility(false)
            }
            if (it == Lifecycle.Event.ON_RESUME && !canShowAds() && isActiveState()) {
                cancel()
            }
            if (it == Lifecycle.Event.ON_DESTROY) {
                timedReloadJob?.cancel()
                timedReloadJob = null
                currentNativeAd?.destroy()
                currentNativeAd = null
            }
        }.launchIn(lifecycleOwner.lifecycleScope)

        lifecycleEventState.debounce(300).onEach { event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeCount.incrementAndGet()
                logZ("Resume repeat ${resumeCount.get()} times")
            }
            if (event == Lifecycle.Event.ON_RESUME && resumeCount.get() > 1
                && currentNativeAd != null && canRequestAds() && canReloadAd() && isActiveState()
            ) {
                requestAds(AdmobNativeBannerParam.Request)
            }
        }.launchIn(lifecycleOwner.lifecycleScope)

        adState
            .onEach { logZ("adState(${it::class.java.simpleName})") }
            .launchIn(lifecycleOwner.lifecycleScope)

        adState.onEach { handleShowAds(it) }.launchIn(lifecycleOwner.lifecycleScope)
    }

    fun getAdState(): StateFlow<AdmobNativeBannerState> = adState.asStateFlow()

    fun setContainer(frameLayout: FrameLayout) = apply {
        this.container = frameLayout
        if (lifecycleOwner.lifecycle.currentState in Lifecycle.State.CREATED..Lifecycle.State.RESUMED) {
            if (!canRequestAds()) frameLayout.checkAdVisibility(false)
        }
    }

    private fun handleShowAds(state: AdmobNativeBannerState) {
        // Visible while Loading (first request) or Loaded; collapse on Cancel/Fail so a dropped or
        // destroyed ad is not left on screen as a blank view.
        val visible = state !is AdmobNativeBannerState.Cancel &&
            state !is AdmobNativeBannerState.Fail && canShowAds()
        container?.checkAdVisibility(visible)
        when (state) {
            is AdmobNativeBannerState.Loaded -> {
                val c = container ?: return
                AdmobNativeBannerManager.inflate(context, state.nativeAd, c, config.layoutId)
                if (AdsManager.showMessageForTester) {
                    Log.d(TAG, "Loaded: ${config.idAds}")
                }
            }
            is AdmobNativeBannerState.Fail,
            is AdmobNativeBannerState.Cancel -> container?.removeAllViews()
            else -> Unit
        }
    }

    private suspend fun loadAd() {
        if (!canRequestAds()) return

        // Silent reload: keep the current ad on screen WHILE loading (no shimmer flash); only show
        // Loading on the first request (no ad yet). The new result swaps in on success / replaces
        // it on failure. Mirrors the Compose native-banner behavior.
        val previousAd = currentNativeAd
        if (previousAd == null) {
            adState.update { AdmobNativeBannerState.Loading }
        }

        val appCtx = context.applicationContext
        // Waterfall: try each id in order with a per-id timeout; only Fail after the last one.
        val ids = config.listId
        val timeout = config.loadTimeout.coerceAtLeast(1L)

        var loaded: NativeAd? = null
        var loadedId: String? = null
        for (adId in ids) {
            if (!isActiveState()) return
            val result = withTimeoutOrNull(timeout) { loadOneNativeAd(adId) }
            if (result != null) {
                loaded = result
                loadedId = adId
                break
            }
        }

        if (!isActiveState()) {
            loaded?.destroy()
            return
        }

        if (loaded == null) {
            val apError = ApAdError("Native banner failed to load")
            currentNativeAd = null
            previousAd?.destroy()
            adState.emit(AdmobNativeBannerState.Fail(apError))
            invokeAdListener { it.onAdFailedToLoad(apError) }
            if (AdsManager.showMessageForTester) {
                (context as? Activity)?.runOnUiThread {
                    Toast.makeText(context, "Native banner fail: ${ids.lastOrNull()}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val winner = loaded
        val winnerId = loadedId ?: config.idAds
        winner.setOnPaidEventListener { adValue ->
            Log.d(TAG, "OnPaidEvent: ${adValue.valueMicros}")
            AdsManager.logPaidEvent(appCtx, adValue, winnerId, winner.responseInfo, AdType.NATIVE)
        }
        currentNativeAd = winner
        adState.emit(AdmobNativeBannerState.Loaded(winner))
        // Destroy the old ad AFTER the new one is published (handleShowAds swaps the view).
        previousAd?.takeIf { it !== winner }?.destroy()
        invokeAdListener { it.onAdLoaded() }
        scheduleTimedReload()
        if (AdsManager.showMessageForTester) {
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(context, "Native banner loaded: $winnerId", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-reload once [AdmobNativeBannerConfig.timeReloadMs] elapses (0 disables). The reload is
    // silent — the current ad stays on screen until the new one swaps in.
    private fun scheduleTimedReload() {
        timedReloadJob?.cancel()
        val delayMs = config.timeReloadMs.coerceAtLeast(0L)
        if (delayMs <= 0L || !config.canReloadAds) return
        timedReloadJob = lifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            if (isActiveState() && canRequestAds() && canReloadAd() &&
                currentNativeAd != null && adState.value is AdmobNativeBannerState.Loaded
            ) {
                requestAds(AdmobNativeBannerParam.Request)
            }
        }
    }

    // Load a single AdMob native ad. Resumes null on failure; the caller applies a per-id timeout
    // and moves to the next waterfall id. Click / impression are forwarded to the holder callbacks.
    private suspend fun loadOneNativeAd(adId: String): NativeAd? =
        suspendCancellableCoroutine { cont ->
            val adOptions = NativeAdOptions.Builder()
                .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                .build()
            AdLoader.Builder(context, adId)
                .forNativeAd { nativeAd ->
                    if (cont.isActive) cont.resume(nativeAd) else nativeAd.destroy()
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "onAdFailedToLoad($adId): ${error.message}")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onAdImpression() {
                        AdsManager.handleAdImpression()
                        invokeAdListener { it.onAdImpression() }
                    }

                    override fun onAdClicked() {
                        AdsManager.handleAdClick(context.applicationContext, adId)
                        invokeAdListener { it.onAdClicked() }
                    }
                })
                .withNativeAdOptions(adOptions)
                .build()
                .loadAd(AdsManager.getAdRequest())
    }

    override fun requestAds(param: AdmobNativeBannerParam) {
        lifecycleOwner.lifecycleScope.launch {
            when (param) {
                is AdmobNativeBannerParam.Request -> {
                    flagActive.compareAndSet(false, true)
                    loadAd()
                }
                is AdmobNativeBannerParam.Ready -> {
                    flagActive.compareAndSet(false, true)
                    currentNativeAd = param.nativeAd
                    adState.emit(AdmobNativeBannerState.Loaded(param.nativeAd))
                }
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        timedReloadJob?.cancel()
        timedReloadJob = null
        currentNativeAd?.destroy()
        currentNativeAd = null
        lifecycleOwner.lifecycleScope.launch {
            adState.emit(AdmobNativeBannerState.Cancel)
        }
    }

    fun registerAdListener(callback: AdsCallback) = listAdCallback.add(callback)
    fun unregisterAdListener(callback: AdsCallback) = listAdCallback.remove(callback)
    fun unregisterAllAdListeners() = listAdCallback.clear()

    private fun invokeAdListener(action: (AdsCallback) -> Unit) = listAdCallback.forEach(action)

    private fun View.checkAdVisibility(isVisible: Boolean) {
        visibility = if (isVisible) View.VISIBLE
        else when (adVisibility) {
            AdOptionVisibility.GONE -> View.GONE
            AdOptionVisibility.INVISIBLE -> View.INVISIBLE
        }
    }
}
