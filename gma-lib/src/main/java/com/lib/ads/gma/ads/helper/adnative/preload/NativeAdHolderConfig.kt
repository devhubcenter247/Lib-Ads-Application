package com.lib.ads.gma.ads.helper.adnative.preload

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.ads.model.wrapper.resolvedAdUnitId
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import com.lib.ads.gma.compose.AdDebugInfo
import com.lib.ads.gma.compose.AdDebugOverlayCompose
import com.lib.ads.gma.ads.helper.adnative.api.LocalApNativeAd
import com.lib.ads.gma.ads.helper.adnative.api.LocalNativeAdView
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdTagConfig
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.PreloadBufferState
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

sealed class NativeAdDisplayState {
    data object Idle : NativeAdDisplayState()
    data object Loading : NativeAdDisplayState()
    data class Success(
        val ad: ApNativeAd,
        val adUnitId: String,
        val fromPreload: Boolean
    ) : NativeAdDisplayState()
    data class Error(val error: LoadAdError?) : NativeAdDisplayState()
    data class Cancelled(val adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE) :
        NativeAdDisplayState()
}

@Stable
class NativeAdHolderConfig internal constructor(
    val tag: String,
    private val _state: MutableStateFlow<NativeAdDisplayState>,
    private var job: Job?,
    private val restartBlock: (() -> Job)?,
    private val adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    /** Receives whether the ad on screen (if any) has recorded an impression. */
    private val onDispose: (currentAdImpressed: Boolean) -> Unit,
    private val bindCallback: (ApNativeAd) -> Unit = {},
    /** Drops the in-flight request so a late SDK callback cannot republish after a cancel. */
    private val onCancelLoad: () -> Unit = {},
    private val _lastError: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    private var disposed = false
    /** Whether the ad currently on screen has recorded an impression (it has been counted). */
    private var currentAdImpressed = false
    /** Whether the ad on screen has been counted, i.e. showing it again earns no new impression. */
    internal val isCurrentAdImpressed: Boolean get() = currentAdImpressed

    /**
     * The last ad handed to the UI and not yet destroyed by this holder. The UI keeps showing it
     * while a replacement loads; cleared by [cancel] so a destroyed ad is never redrawn.
     */
    internal var displayedAd: ApNativeAd? = null
        private set
    private val adCallbacks = CopyOnWriteArrayList<NativeAdListener>()
    internal val callbackRelay = object : NativeAdListener {
        override fun onLoaded(ad: ApNativeAd) {
            currentAdImpressed = false
            displayedAd = ad
            adCallbacks.forEach { it.onLoaded(ad) }
        }
        override fun onFailed(error: ApAdError) = adCallbacks.forEach { it.onFailed(error) }
        override fun onClicked(ad: ApNativeAd) = adCallbacks.forEach { it.onClicked(ad) }
        override fun onImpression(ad: ApNativeAd) {
            currentAdImpressed = true
            adCallbacks.forEach { it.onImpression(ad) }
        }
        override fun onPaid(adValue: com.google.android.libraries.ads.mobile.sdk.common.AdValue) = adCallbacks.forEach { it.onPaid(adValue) }
        override fun onShownFullScreenContent(ad: ApNativeAd) = adCallbacks.forEach { it.onShownFullScreenContent(ad) }
        override fun onDismissedFullScreenContent(ad: ApNativeAd) = adCallbacks.forEach { it.onDismissedFullScreenContent(ad) }
        override fun onFailedToShowFullScreenContent(ad: ApNativeAd, error: ApAdError) = adCallbacks.forEach { it.onFailedToShowFullScreenContent(ad, error) }
        override fun onSwipeGestureClicked(ad: ApNativeAd) = adCallbacks.forEach { it.onSwipeGestureClicked(ad) }
    }
    val state: StateFlow<NativeAdDisplayState> = _state.asStateFlow()

    /**
     * Why the last load of this holder failed — one line per ad unit tried for a waterfall
     * (`"/1234567890: NO_FILL(3): No fill."`). Kept when a refresh fails while an ad stays on
     * screen; cleared when a new ad is shown. Shown by the debug overlay.
     */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val currentState: NativeAdDisplayState get() = _state.value
    val isLoaded: Boolean get() = currentState is NativeAdDisplayState.Success
    val isLoading: Boolean get() = currentState is NativeAdDisplayState.Loading
    val isCancelled: Boolean get() = currentState is NativeAdDisplayState.Cancelled
    val adVisibility: AdOptionVisibility get() = adOptionVisibility
    val ad: ApNativeAd? get() = (currentState as? NativeAdDisplayState.Success)?.ad

    fun registerAdCallback(adCallback: NativeAdListener) {
        if (disposed) return
        if (!adCallbacks.addIfAbsent(adCallback)) return
        when (val state = currentState) {
            is NativeAdDisplayState.Success -> {
                bindCallback(state.ad)
                adCallback.onLoaded(state.ad)
            }
            is NativeAdDisplayState.Error -> adCallback.onFailed(ApAdError("Native ad failed"))
            else -> Unit
        }
    }

    fun unregisterAdCallback(adCallback: NativeAdListener) {
        adCallbacks.remove(adCallback)
    }

    /**
     * Fetches an ad for this holder. A request already in flight is reused. When an ad is already
     * shown it is only replaced once it has recorded an impression — replacing an ad that was never
     * counted wastes the load and lowers show rate. The shown ad stays visible until a new one
     * arrives and is kept if the request fails.
     */
    fun request() = reload(force = false)

    /**
     * Same as [request]. Pass [force] = true to replace the shown ad even before its impression
     * (e.g. the placement's content changed and the old ad no longer fits).
     */
    @JvmOverloads
    fun reload(force: Boolean = false) {
        if (disposed) return
        if (!force && currentState is NativeAdDisplayState.Success && !currentAdImpressed) {
            AdLogger.d(AdFormat.NATIVE, tag, "REFRESH_SKIPPED", "current ad has no impression yet")
            return
        }
        job = restartBlock?.invoke() ?: job
    }

    fun cancelLoad() {
        if (currentState is NativeAdDisplayState.Loading) {
            stopLoad()
            _state.value = NativeAdDisplayState.Cancelled(adOptionVisibility)
        }
    }

    /** Stops an in-flight refresh but keeps the currently displayed native ad intact. */
    internal fun pauseLoad() {
        stopLoad()
        if (currentState !is NativeAdDisplayState.Success) {
            _state.value = NativeAdDisplayState.Cancelled(adOptionVisibility)
        }
    }

    /**
     * Stops loading and destroys the ad on screen (same as the banner holder). The next
     * [request]/[reload] loads a fresh ad. To only stop loading while keeping the ad, let the
     * lifecycle pause the holder (`cancelOnPause`) instead.
     */
    fun cancel() {
        stopLoad()
        (currentState as? NativeAdDisplayState.Success)?.let { runCatching { it.ad.nativeAd?.destroy() } }
        displayedAd = null
        _state.value = NativeAdDisplayState.Cancelled(adOptionVisibility)
    }

    private fun stopLoad() {
        job?.cancel()
        job = null
        onCancelLoad()
    }

    internal fun dispose() {
        if (disposed) return
        disposed = true
        job?.cancel()
        adCallbacks.clear()
        onDispose(currentAdImpressed)
    }
}

@Composable
fun rememberNativeAdPreload(
    tag: String,
    options: NativeAdPreloadHolderOptions = NativeAdPreloadHolderOptions(autoRequestOnStart = true),
    adCallback: NativeAdListener? = null
): NativeAdHolderConfig {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Same holder, controller and lifecycle rules as createNativeAdHolder (XML); only the
    // lifecycle owner differs: this binds to the Compose screen, not the hosting Activity.
    //
    // NOTE: requestOptions is intentionally NOT part of this key. NativeAdRequestOptions is a
    // plain class with no equals()/hashCode() override, so keying on it (or on the whole `options`
    // data class, which embeds it) would compare by reference — and since Kotlin evaluates default
    // parameter values fresh at every call site, an uncalled-out `options` param gets a brand new
    // NativeAdRequestOptions() each recomposition, forcing the holder to be torn down and rebuilt
    // every time. Only the scalar fields that affect the holder's own request behavior are keyed;
    // a live requestOptions change requires changing tag/canShowAds/etc. too to take effect.
    val holder = remember(
        tag,
        options.fallbackAdUnitIds.hashCode(),
        options.timeoutPerIdMs,
        options.adOptionVisibility,
        options.destroyOnDispose,
        options.canShowAds,
    ) {
        buildNativeAdHolder(tag = tag, enabled = true, scope = scope, options = options)
    }

    DisposableEffect(holder, adCallback) {
        adCallback?.let(holder::registerAdCallback)
        onDispose {
            adCallback?.let(holder::unregisterAdCallback)
        }
    }

    DisposableEffect(lifecycleOwner, holder) {
        val unbind = bindNativeHolderLifecycle(lifecycleOwner, holder, options)
        onDispose {
            unbind()
            holder.dispose()
        }
    }

    return holder
}

@Composable
fun rememberNativeAdPreload(
    spec: NativeAdSpec,
    timeoutPerIdMs: Long = 10_000L,
    autoRequestOnStart: Boolean = true,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    adCallback: NativeAdListener? = null,
): NativeAdHolderConfig = rememberNativeAdPreload(
    tag = spec.tag,
    options = NativeAdPreloadHolderOptions(
        fallbackAdUnitIds = spec.adUnitIds,
        timeoutPerIdMs = timeoutPerIdMs,
        autoRequestOnStart = autoRequestOnStart,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        adOptionVisibility = adOptionVisibility,
        requestOptions = spec.requestOptions,
    ),
    adCallback = adCallback,
)

@Composable
fun NativeAdCard(
    holder: NativeAdHolderConfig? = null,
    modifier: Modifier = Modifier,
    loading: @Composable (Boolean) -> Unit = {},
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = {}
) {
    if (LocalInspectionMode.current) {
        val nativeAd by remember { mutableStateOf(ApNativeAd()) }
        CompositionLocalProvider(
            LocalApNativeAd provides nativeAd,
            LocalNativeAdView provides null,
        ) {
            Box(modifier = modifier) { loading(true) }
        }
        return
    }

    if(holder == null){
        Box(modifier = modifier) { loading(true) }
        return
    }
    // StateFlow already contains the holder's current value. Using Idle here as the initial
    // value causes a one-frame Idle/loading flash when a preloaded ad is already available.
    val state by holder.state.collectAsStateWithLifecycle(holder.currentState)
    val preloadState by NativeAds.stateFlow(holder.tag)
        .collectAsStateWithLifecycle(PreloadBufferState.Idle)
    val lastError by holder.lastError.collectAsStateWithLifecycle()
    var loadStartTime by remember { mutableLongStateOf(0L) }
    var loadTimeMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state) {
        when (val currentState = state) {
            is NativeAdDisplayState.Loading -> loadStartTime = System.currentTimeMillis()
            is NativeAdDisplayState.Success -> {
                if (loadStartTime > 0) {
                    loadTimeMs = System.currentTimeMillis() - loadStartTime
                    loadStartTime = 0L
                }
            }
            else -> Unit
        }
    }

    val modifierAds = remember(state) {
        when (state) {
            is NativeAdDisplayState.Cancelled, is NativeAdDisplayState.Error -> {
                if ((state as? NativeAdDisplayState.Cancelled)?.adOptionVisibility == AdOptionVisibility.INVISIBLE) {
                    Modifier.wrapContentHeight().alpha(0f)
                } else {
                    Modifier.height(0.dp).alpha(0f)
                }
            }
            else -> Modifier.wrapContentHeight().alpha(1f)
        }
    }

    Box(modifier.fillMaxWidth().wrapContentHeight()) {
        Box(modifierAds) {
            when (val currentState = state) {
                is NativeAdDisplayState.Loading, NativeAdDisplayState.Idle -> {
                    // A request/reload may briefly expose an intermediate state while a cached
                    // ad is being polled. Keep the last rendered ad in place to avoid a UI flash.
                    // Read from the holder: it drops the ad once destroyed (cancel), so a
                    // destroyed ad is never redrawn.
                    val cachedAd = holder.displayedAd
                    if (cachedAd != null) {
                        key(cachedAd, cachedAd.resolvedAdUnitId()) {
                            nativeView(cachedAd)
                        }
                    } else {
                        loading(true)
                    }
                }
                is NativeAdDisplayState.Error -> error(currentState.error)
                is NativeAdDisplayState.Success -> {
                    key(currentState.ad, currentState.adUnitId) {
                        nativeView(currentState.ad)
                    }
                }
                is NativeAdDisplayState.Cancelled -> {
                    if (currentState.adOptionVisibility == AdOptionVisibility.INVISIBLE) {
                        loading(false)
                    } else {
                        Box(modifier = Modifier.height(0.dp))
                    }
                }
            }
        }

        if (AdsProvider.getInstance().adConfigOrNull?.showMessageForTester == true) {
            val debugInfo = remember(state, preloadState, loadTimeMs, lastError) {
                AdDebugInfo(
                    adType = "Native",
                    state = state.debugLabel(),
                    adUnitId = (state as? NativeAdDisplayState.Success)?.adUnitId,
                    fromPreload = (state as? NativeAdDisplayState.Success)?.fromPreload,
                    cacheCount = NativeAds.available(holder.tag),
                    requestCount = NativeAds.requestCount(holder.tag),
                    inFlightRequests = NativeAds.inFlightRequests(holder.tag),
                    loadTimeMs = loadTimeMs,
                    tag = holder.tag,
                    error = lastError,
                )
            }
            AdDebugOverlayCompose(info = debugInfo, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

private fun NativeAdDisplayState.debugLabel(): String = when (this) {
    is NativeAdDisplayState.Idle -> "Idle"
    is NativeAdDisplayState.Loading -> "Loading"
    is NativeAdDisplayState.Success -> "Loaded"
    is NativeAdDisplayState.Error -> "Error"
    is NativeAdDisplayState.Cancelled -> "Cancelled"
}

@Composable
fun NativeAdWithPreload(
    tag: String,
    modifier: Modifier = Modifier,
    options: NativeAdPreloadHolderOptions = NativeAdPreloadHolderOptions(autoRequestOnStart = true),
    adCallback: NativeAdListener? = null,
    loading: @Composable (Boolean) -> Unit = {},
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = {}
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier) { loading(true) }
        return
    }
    val holder = rememberNativeAdPreload(
        tag = tag,
        options = options,
        adCallback = adCallback
    )
    NativeAdCard(holder = holder, modifier = modifier, loading = loading, error = error, nativeView = nativeView)
}

@Composable
fun NativeAdWithPreload(
    config: NativeAdTagConfig,
    modifier: Modifier = Modifier,
    options: NativeAdPreloadHolderOptions = NativeAdPreloadHolderOptions(autoRequestOnStart = true),
    adCallback: NativeAdListener? = null,
    loading: @Composable (Boolean) -> Unit = {},
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = {}
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier) { loading(true) }
        return
    }
    val holder = rememberNativeAdPreload(
        tag = config.tag,
        options = options.copy(
            fallbackAdUnitIds = config.getAllAdUnitIds(),
            canShowAds = config.canShowAds,
            canReloadAds = config.canReloadAds,
        ),
        adCallback = adCallback
    )
    NativeAdCard(holder = holder, modifier = modifier, loading = loading, error = error, nativeView = nativeView)
}
