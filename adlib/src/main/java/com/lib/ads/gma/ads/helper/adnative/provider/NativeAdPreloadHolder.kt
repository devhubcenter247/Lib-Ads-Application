package com.lib.ads.gma.ads.helper.adnative.provider

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.helper.adnative.NativeAdLog
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.core.NativeAdLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import com.lib.ads.gma.ads.debug.AdDebugInfo
import com.lib.ads.gma.ads.debug.AdDebugOverlayCompose
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer

/**
 * State representing the native ad loading status for Compose
 */
sealed class NativeAdDisplayState {
    data object Idle : NativeAdDisplayState()
    data object Loading : NativeAdDisplayState()
    data class Success(
        val ad: ApNativeAd,
        val adUnitId: String,
        val fromPreload: Boolean,
        val eventRelay: NativeAdEventRelay? = null
    ) : NativeAdDisplayState()

    data class Error(val error: LoadAdError?) : NativeAdDisplayState()
    data class Cancelled(val adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE) :
        NativeAdDisplayState()
}

/**
 * Holder class for managing native ad state in Compose
 */
@Stable
class NativeAdPreloadHolder internal constructor(
    val tag: String,
    private val _state: MutableStateFlow<NativeAdDisplayState>,
    private var job: Job?,
    private val restartBlock: ((preserveCurrent: Boolean) -> Job)?,
    private val adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    private val adCallbacks: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList(),
    internal val lifecycleManaged: Boolean = false,
    private val destroyOnDispose: Boolean = true,
    private val onDispose: () -> Unit
) {
    val state: StateFlow<NativeAdDisplayState> = _state.asStateFlow()
    val currentState: NativeAdDisplayState get() = _state.value
    val isLoaded: Boolean get() = currentState is NativeAdDisplayState.Success
    val isLoading: Boolean get() = currentState is NativeAdDisplayState.Loading
    val isRequestInFlight: Boolean get() = job?.isActive == true
    val isCancelled: Boolean get() = currentState is NativeAdDisplayState.Cancelled
    val adVisibility: AdOptionVisibility get() = adOptionVisibility
    val ad: ApNativeAd? get() = (currentState as? NativeAdDisplayState.Success)?.ad
    val eventRelay: NativeAdEventRelay? get() = (currentState as? NativeAdDisplayState.Success)?.eventRelay
    private var disposed = false

    fun registerAdCallback(adCallback: AdsCallback) {
        val added = adCallbacks.addIfAbsent(adCallback)
        if (!added) return
        eventRelay?.addListener(adCallback)
        (currentState as? NativeAdDisplayState.Success)?.let {
            adCallback.onAdLoaded()
            adCallback.onNativeAdLoaded(it.ad)
        }
    }

    fun unregisterAdCallback(adCallback: AdsCallback) {
        adCallbacks.remove(adCallback)
        eventRelay?.removeListener(adCallback)
    }

    fun unregisterAllAdCallbacks() {
        adCallbacks.forEach { eventRelay?.removeListener(it) }
        adCallbacks.clear()
    }

    /**
     * Request an ad if no request is currently running.
     * If an ad is already displayed, keep it visible while the fresh request runs.
     */
    fun request() {
        if (restartBlock == null) {
            NativeAdLog.d("request", tag, "request() ignored — holder has no restartBlock (inert/disabled)")
            return
        }
        if (isRequestInFlight) {
            NativeAdLog.d("request", tag, "request() ignored — request already in flight")
            return
        }

        val preserveCurrent = currentState is NativeAdDisplayState.Success
        NativeAdLog.d(
            "request",
            tag,
            "request() from ${currentState.displayLabel()} -> launching load, preserveCurrent=$preserveCurrent"
        )
        if (!preserveCurrent) {
            _state.value = NativeAdDisplayState.Idle
        }
        job = restartBlock.invoke(preserveCurrent)
    }

    /**
     * Cancel only the ongoing load request.
     * If an ad is already loaded, this is a no-op (the ad is preserved).
     * Use [cancel] to force-cancel regardless of state.
     */
    fun cancelLoad() {
        if (isRequestInFlight) {
            NativeAdLog.d("request", tag, "cancelLoad() — cancelling in-flight load")
            job?.cancel()
            if (currentState is NativeAdDisplayState.Loading) {
                _state.value = NativeAdDisplayState.Cancelled(adOptionVisibility)
            }
        }
    }

    /**
     * Force cancel regardless of state. Cancels the job and sets state to [NativeAdDisplayState.Cancelled].
     * Destroys an already-loaded ad before the state is cleared.
     * Also detaches lifecycle automation so an Activity-scoped owner cannot auto-request after a page leaves.
     */
    fun cancel() {
        NativeAdLog.d("request", tag, "cancel() from ${currentState.displayLabel()}")
        job?.cancel()
        (currentState as? NativeAdDisplayState.Success)?.destroy()
        _state.value = NativeAdDisplayState.Cancelled(adOptionVisibility)
        onDispose()
    }

    /**
     * Request a fresh ad. If an ad is already displayed, keep it visible while loading.
     */
    fun reload() {
        if (restartBlock == null) {
            NativeAdLog.d("request", tag, "reload() ignored — holder has no restartBlock (inert/disabled)")
            return
        }
        if (isRequestInFlight) {
            NativeAdLog.d("request", tag, "reload() ignored — request already in flight")
            return
        }

        val preserveCurrent = currentState is NativeAdDisplayState.Success
        NativeAdLog.d(
            "request",
            tag,
            "reload() from ${currentState.displayLabel()} -> launching load, preserveCurrent=$preserveCurrent"
        )
        if (!preserveCurrent) {
            _state.value = NativeAdDisplayState.Idle
        }
        job = restartBlock.invoke(preserveCurrent)
    }

    /**
     * Release the holder: cancel any in-flight load and destroy the current ad.
     *
     * Compose-owned holders call this automatically on `ON_DESTROY` / leaving composition.
     * For a holder created via [createNativeAdPreloadHolder] the **caller owns the lifecycle**
     * and must call this (e.g. `ViewModel.onCleared`) to avoid leaking the native ad.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        NativeAdLog.d("compose", tag, "dispose() from ${currentState.displayLabel()}")
        job?.cancel()
        if (destroyOnDispose) {
            (currentState as? NativeAdDisplayState.Success)?.destroy()
        }
        adCallbacks.clear()
        onDispose()
    }
}

/** Short label of a display state for logging. */
internal fun NativeAdDisplayState.displayLabel(): String = when (this) {
    is NativeAdDisplayState.Idle -> "Idle"
    is NativeAdDisplayState.Loading -> "Loading"
    is NativeAdDisplayState.Success -> "Success(unit=$adUnitId, fromPreload=$fromPreload)"
    is NativeAdDisplayState.Error -> "Error(${error?.code})"
    is NativeAdDisplayState.Cancelled -> "Cancelled"
}

@Composable
fun rememberNativeAdPreload(
    tag: String,
    enabled: Boolean = true,
    fallbackAdUnitIds: List<String> = emptyList(),
    timeoutPerIdMs: Long = 10_000L,
    options: NativeAdOptions = remember { NativeAdOptions.Builder().build() },
    adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    destroyOnDispose: Boolean = true,
    adCallback: AdsCallback? = null,
): NativeAdPreloadHolder {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Keyed only by stable identity (tag + ad unit set). This survives recomposition,
    // so a Composable that recomposes many times does NOT recreate the holder, cancel
    // the in-flight load, or re-request the ad — the holder is created exactly once.
    val holder = remember(tag, fallbackAdUnitIds.hashCode(), inspection, enabled) {
        if (inspection) {
            // @Preview / LocalInspectionMode: return an inert holder so request()/reload() are
            // no-ops and nothing touches the ads SDK. NativeAdCard renders its shimmer branch,
            // so previews work regardless of how the caller drives the holder.
            previewNativeAdHolder(tag, adOptionVisibility)
        } else if (!enabled) {
            // Disabled placement: unlike the preview holder, this fires onAdFailedToLoad once
            // on adCallback and starts Cancelled (not Idle) so NativeAdCard collapses instead
            // of shimmering forever.
            disabledNativeAdHolder(tag, adOptionVisibility, adCallback)
        } else {
            NativeAdLog.d(
                "compose",
                tag,
                "rememberNativeAdPreload — creating holder (fallback=$fallbackAdUnitIds)"
            )
            createPreloadHolder(
                tag = tag,
                // Keep Activity context for mediation adapters such as Unity Ads.
                context = context,
                fallbackAdUnitIds = fallbackAdUnitIds,
                timeoutPerIdMs = timeoutPerIdMs,
                adOptionVisibility = adOptionVisibility,
                options = options,
                scope = scope,
                destroyOnDispose = destroyOnDispose
            )
        }
    }

    // This holder is Compose-owned: dispose it when the Composable leaves for good.
    // Skipped in preview / when disabled — the inert holder has no lifecycle/SDK work to wire up.
    if (!inspection && enabled) {
        HolderLifecycleEffects(
            holder = holder,
            autoReloadOnResume = autoReloadOnResume,
            cancelOnPause = cancelOnPause,
            adCallback = adCallback,
            disposeOnLeave = true,
        )
        LaunchedEffect(holder) {
            holder.request()
        }
    }

    return holder
}

/**
 * Inert [NativeAdPreloadHolder] for `@Preview` / [LocalInspectionMode]: it holds no SDK
 * wiring (restartBlock == null), so [NativeAdPreloadHolder.request] / [NativeAdPreloadHolder.reload]
 * are no-ops and never load a real ad. Paired with [NativeAdCard]'s inspection-mode shimmer.
 */
private fun previewNativeAdHolder(
    tag: String,
    adOptionVisibility: AdOptionVisibility,
): NativeAdPreloadHolder = NativeAdPreloadHolder(
    tag = tag,
    _state = MutableStateFlow(NativeAdDisplayState.Idle),
    job = null,
    restartBlock = null,
    adOptionVisibility = adOptionVisibility,
    onDispose = {},
)

/**
 * Holder for a placement disabled via `enabled = false`. Unlike [previewNativeAdHolder] this
 * fires [AdsCallback.onAdFailedToLoad] once on [adCallback] and starts in
 * [NativeAdDisplayState.Cancelled] (not [NativeAdDisplayState.Idle]) so callers observing either
 * the callback or [NativeAdPreloadHolder.state] get a definitive "not loading" signal instead of
 * silently hanging forever.
 */
private fun disabledNativeAdHolder(
    tag: String,
    adOptionVisibility: AdOptionVisibility,
    adCallback: AdsCallback?,
): NativeAdPreloadHolder {
    val callbacks = CopyOnWriteArrayList<AdsCallback>().apply { adCallback?.let(::add) }
    NativeAdLog.d("request", tag, "enabled=false — returning disabled holder (Cancelled) and notifying callback")
    callbacks.forEach { it.onAdFailedToLoad(null) }
    return NativeAdPreloadHolder(
        tag = tag,
        _state = MutableStateFlow(NativeAdDisplayState.Cancelled(adOptionVisibility)),
        job = null,
        restartBlock = null,
        adOptionVisibility = adOptionVisibility,
        adCallbacks = callbacks,
        onDispose = {},
    )
}

/**
 * Bind an **externally-owned** [holder] (e.g. created in a ViewModel via
 * [createNativeAdPreloadHolder]) to the current lifecycle so it keeps the convenient
 * auto-request / auto-reload / cancel-on-pause behaviour — **without** letting Compose
 * destroy it. The holder is *not* disposed when the Composable leaves composition, so it
 * survives configuration changes and back-stack navigation and stays controllable from
 * anywhere (`holder.request()` / `holder.cancel()` / `holder.reload()`).
 *
 * The owner (ViewModel) is responsible for [NativeAdPreloadHolder.dispose].
 *
 * ```kotlin
 * // vm.nativeHolder = createNativeAdPreloadHolder(app, tag, viewModelScope, listId)
 * val holder = rememberNativeAdPreload(holder = vm.nativeHolder)
 * NativeAdCard(holder = holder, loading = { ... }, nativeView = { ad -> ... })
 * ```
 */
@Composable
fun rememberNativeAdPreload(
    holder: NativeAdPreloadHolder?,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    adCallback: AdsCallback? = null,
): NativeAdPreloadHolder? {
    HolderLifecycleEffects(
        holder = holder,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        adCallback = adCallback,
        disposeOnLeave = false,
    )
    return holder
}

/**
 * Shared lifecycle + callback wiring for a [NativeAdPreloadHolder].
 *
 * @param disposeOnLeave when true the holder is disposed on `ON_DESTROY` / leaving
 *   composition (Compose-owned). When false the holder outlives the composition and the
 *   external owner is responsible for [NativeAdPreloadHolder.dispose].
 */
@Composable
private fun HolderLifecycleEffects(
    holder: NativeAdPreloadHolder?,
    autoReloadOnResume: Boolean,
    cancelOnPause: Boolean,
    adCallback: AdsCallback?,
    disposeOnLeave: Boolean,
) {
    // @Preview / LocalInspectionMode: no lifecycle automation, no callback collection, no
    // SDK-driven state — the inspection-mode shimmer in NativeAdCard is all that renders.
    if (LocalInspectionMode.current) return
    val lifecycleOwner = LocalLifecycleOwner.current
    if (holder == null) return
    val tag = holder.tag
    val currentCallback by rememberUpdatedState(adCallback)

    // Wire adCallback to state transitions and the per-ad event relay.
    LaunchedEffect(holder) {
        var lastRelay: NativeAdEventRelay? = null
        val relayForwarder = object : AdsCallback() {
            override fun onAdImpression() {
                currentCallback?.onAdImpression()
            }

            override fun onAdClicked() {
                currentCallback?.onAdClicked()
            }

            override fun onAdClosed() {
                currentCallback?.onAdClosed()
            }
        }
        try {
            holder.state.collect { state ->
                lastRelay?.removeListener(relayForwarder)
                lastRelay = null
                when (state) {
                    is NativeAdDisplayState.Success -> {
                        currentCallback?.onAdLoaded()
                        currentCallback?.onNativeAdLoaded(state.ad)
                        state.eventRelay?.addListener(relayForwarder)
                        lastRelay = state.eventRelay
                    }

                    is NativeAdDisplayState.Error -> currentCallback?.onAdFailedToLoad(null)
                    // Covers a disabled (enabled=false) externally-owned holder whose adCallback
                    // was only bound here (not at createNativeAdPreloadHolder time) — otherwise
                    // this callback would never learn the placement was skipped.
                    is NativeAdDisplayState.Cancelled -> currentCallback?.onAdFailedToLoad(null)
                    else -> Unit
                }
            }
        } finally {
            lastRelay?.removeListener(relayForwarder)
        }
    }

    var resumeCount by remember(holder) { mutableIntStateOf(0) }

    if (!holder.lifecycleManaged) {
        DisposableEffect(lifecycleOwner, holder) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        resumeCount++
                        NativeAdLog.d(
                            "compose", tag,
                            "ON_RESUME #$resumeCount, state=${holder.currentState.displayLabel()}, " +
                                    "autoReload=$autoReloadOnResume"
                        )
                        // The remember overload starts the first request once. On return to
                        // the screen, silently reload only an ad that is already displayed.
                        if (resumeCount > 1 && autoReloadOnResume &&
                            holder.currentState is NativeAdDisplayState.Success
                        ) {
                            NativeAdLog.d("compose", tag, "auto-reload on resume #$resumeCount")
                            holder.reload()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        if (cancelOnPause) {
                            NativeAdLog.d("compose", tag, "ON_PAUSE — cancelling load")
                            holder.cancelLoad()
                        }
                    }

                    Lifecycle.Event.ON_DESTROY -> if (disposeOnLeave) holder.dispose()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                if (disposeOnLeave) holder.dispose()
            }
        }
    } else if (disposeOnLeave) {
        DisposableEffect(holder) {
            onDispose { holder.dispose() }
        }
    }
}

/**
 * Complete Native Ad Card with preload support
 */
@Composable
fun NativeAdCard(
    holder: NativeAdPreloadHolder?,
    modifier: Modifier = Modifier,
    loading: @Composable (Boolean) -> Unit = { isShow -> DefaultNativeAdLoading(isShow) },
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = { }
) {
    if (LocalInspectionMode.current) {
        val nativeAd by remember {
            mutableStateOf(ApNativeAd())
        }
        CompositionLocalProvider(
            LocalApNativeAd provides nativeAd,
            LocalNativeAdView provides null,
        ) {
            Box(modifier = modifier) {
                loading(true)
            }
        }
        return
    }
    if (holder == null) {
        loading(false)
        return
    }
    // Seed with the holder's current state (not Idle) so an already-ready ad renders on the
    // first frame instead of flashing a shimmer.
    val state by holder.state.collectAsStateWithLifecycle(holder.currentState)
    var loadStartTime by remember { mutableLongStateOf(0L) }
    var loadTimeMs by remember { mutableStateOf<Long?>(null) }

    // Surfaces how often this Composable recomposes for a given tag so excessive
    // recomposition can be diagnosed from logcat (`adb logcat -s NativeAds`).
    val recomposeCount = remember { AtomicInteger(0) }
    SideEffect {
        NativeAdLog.d(
            "compose",
            holder.tag,
            "NativeAdCard recompose #${recomposeCount.incrementAndGet()} state=${state.displayLabel()}"
        )
    }

    LaunchedEffect(state) {
        NativeAdLog.d("compose", holder.tag, "state -> ${state.displayLabel()}")
        when (state) {
            is NativeAdDisplayState.Loading -> loadStartTime = System.currentTimeMillis()
            is NativeAdDisplayState.Success -> {
                if (loadStartTime > 0) {
                    loadTimeMs = System.currentTimeMillis() - loadStartTime
                    loadTimeMs?.let {
                        NativeAdLog.d(
                            "compose",
                            holder.tag,
                            "displayed after ${it}ms"
                        )
                    }
                    loadStartTime = 0L
                }
            }

            else -> Unit
        }
    }

    val modifierAds = remember(state) {
        when (state) {
            is NativeAdDisplayState.Cancelled -> {
                if ((state as? NativeAdDisplayState.Cancelled)?.adOptionVisibility == AdOptionVisibility.INVISIBLE) {
                    Modifier.wrapContentHeight().alpha(0f)
                } else {
                    Modifier.height(0.dp).alpha(0f)
                }
            }

            is NativeAdDisplayState.Error -> Modifier.height(0.dp)
            else -> Modifier.wrapContentHeight()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Box(modifierAds) {
            when (val currentState = state) {
                is NativeAdDisplayState.Loading, NativeAdDisplayState.Idle -> loading(true)
                is NativeAdDisplayState.Error -> error(currentState.error)
                is NativeAdDisplayState.Success -> {
                    androidx.compose.runtime.key(currentState.ad, currentState.adUnitId) {
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

        if (AdsManager.showMessageForTester) {
            val debugInfo = remember(state, loadTimeMs) {
                AdDebugInfo(
                    adType = "Native",
                    state = state.debugLabel(),
                    adUnitId = (state as? NativeAdDisplayState.Success)?.adUnitId,
                    fromPreload = (state as? NativeAdDisplayState.Success)?.fromPreload,
                    cacheCount = NativeAds.available(holder.tag),
                    loadTimeMs = loadTimeMs,
                    tag = holder.tag
                )
            }
            AdDebugOverlayCompose(
                info = debugInfo,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
fun DefaultNativeAdLoading(
    isShowShimmer: Boolean,
    modifier: Modifier = Modifier,
) {
    val shimmer = rememberShimmerState()
    val placeholder = Color(0xFFE0E0E0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Color.White)
            .shimmer(state = shimmer, visible = isShowShimmer)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholder)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(placeholder)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(placeholder)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(placeholder)
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(placeholder)
        )
    }
}

private fun NativeAdDisplayState.debugLabel(): String = when (this) {
    is NativeAdDisplayState.Idle -> "Idle"
    is NativeAdDisplayState.Loading -> "Loading"
    is NativeAdDisplayState.Success -> "Loaded"
    is NativeAdDisplayState.Error -> "Error: ${error?.code}"
    is NativeAdDisplayState.Cancelled -> "Cancelled"
}

/**
 * Simplified Native Ad with automatic preload management
 */
@Composable
fun NativeAdWithPreload(
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fallbackAdUnitIds: List<String> = emptyList(),
    timeoutPerIdMs: Long = 10_000L,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    adCallback: AdsCallback? = null,
    loading: @Composable (Boolean) -> Unit = { isShow -> DefaultNativeAdLoading(isShow) },
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = {}
) {
    val holder = rememberNativeAdPreload(
        tag = tag,
        fallbackAdUnitIds = fallbackAdUnitIds,
        timeoutPerIdMs = timeoutPerIdMs,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        adCallback = adCallback,
        enabled = enabled,
    )

    NativeAdCard(
        holder = holder,
        modifier = modifier,
        loading = loading,
        error = error,
        nativeView = nativeView
    )
}

/**
 * Remember and manage a Native Ad from preload buffer using a [NativeAdSpec].
 * Automatically registers the config's preload settings and extracts tag + ad unit IDs.
 *
 * This overload lets Compose callers share the same [NativeAdSpec] used by XML code
 * without needing to manually extract tag / ad unit IDs.
 */
@Composable
fun rememberNativeAdPreload(
    spec: NativeAdSpec,
    enabled: Boolean = true,
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    options: NativeAdOptions = remember { NativeAdLoader.defaultOptions() },
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    destroyOnDispose: Boolean = true,
    adCallback: AdsCallback? = null,
): NativeAdPreloadHolder {

    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    // Tie the shared preload buffer to this Composable's lifecycle: register + start
    // warming on enter, and release on leave so the background warm job stops
    // once no screen needs this tag anymore (avoids wasted requests / lower show rate).
    // Skipped under @Preview, or when this placement is disabled via [enabled], so no real
    // ad loading is kicked off.
    DisposableEffect(spec.tag, enabled) {
        if (!inspection && enabled && spec.usePreloadBuffer) {
            NativeAds.register(spec)
            NativeAds.acquire(spec.tag)
            NativeAds.preload(context, spec.tag)
        }
        onDispose {
            if (!inspection && enabled && spec.usePreloadBuffer) NativeAds.release(spec.tag)
        }
    }

    return rememberNativeAdPreload(
        tag = spec.tag,
        fallbackAdUnitIds = spec.getAllAdUnitIds(),
        timeoutPerIdMs = timeoutPerIdMs,
        options = options,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        destroyOnDispose = destroyOnDispose,
        adCallback = adCallback,
        enabled = enabled,
    )
}

/**
 * Simplified Native Ad with automatic preload management, accepting a [NativeAdSpec].
 *
 * Convenience composable that combines [rememberNativeAdPreload] (config overload) with
 * [NativeAdCard]. No layout ID is required on the config when using this Compose path.
 */
@Composable
fun NativeAdWithPreload(
    spec: NativeAdSpec,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
    adCallback: AdsCallback? = null,
    loading: @Composable (Boolean) -> Unit = { isShow -> DefaultNativeAdLoading(isShow) },
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = { }
) {
    val holder = rememberNativeAdPreload(
        spec = spec,
        timeoutPerIdMs = timeoutPerIdMs,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        adCallback = adCallback,
        enabled = enabled,
    )

    NativeAdCard(
        holder = holder, modifier = modifier,
        loading = loading, error = error, nativeView = nativeView
    )
}


/**
 * Create a [NativeAdPreloadHolder] **outside of Compose** so its [NativeAdPreloadHolder.request],
 * [NativeAdPreloadHolder.cancel] and [NativeAdPreloadHolder.reload] can be driven from a
 * ViewModel, a click handler, a coroutine — anywhere — not only from the Composable that shows it.
 *
 * Pass the returned holder straight to [NativeAdCard]. The **caller owns the lifecycle**: call
 * [NativeAdPreloadHolder.dispose] when finished (e.g. `ViewModel.onCleared`) to release the ad.
 *
 * ```kotlin
 * // 1) hold it somewhere stable (ViewModel recommended)
 * class LanguageVM(app: Application) : AndroidViewModel(app) {
 *     val nativeHolder = createNativeAdPreloadHolder(
 *         context = app,
 *         tag = NativePlacement.Language1.tag,
 *         fallbackAdUnitIds = NativePlacement.Language1.listId,
 *         scope = viewModelScope,
 *     )
 *     override fun onCleared() { nativeHolder.dispose() }
 * }
 *
 * // 2) trigger from ANY place
 * vm.nativeHolder.request()   // or .cancel() / .reload()
 *
 * // 3) just render it in Compose
 * NativeAdCard(holder = vm.nativeHolder, loading = { ... }, nativeView = { ad -> ... })
 * ```
 *
 * @param scope coroutine scope the load runs in (e.g. `viewModelScope`). When it is cancelled
 *   any in-flight load is cancelled too.
 * @param enabled gate for this specific placement, independent of the global
 *   [AdsManager.canRequestAds] switch. Defaults to true. Pass false (e.g. from a remote-config
 *   flag scoped to this screen/placement) to skip all SDK work entirely: the returned holder is
 *   inert (same as the `@Preview` holder) so [NativeAdPreloadHolder.request] /
 *   [NativeAdPreloadHolder.reload] are no-ops and nothing is ever requested from this location.
 */
fun createNativeAdPreloadHolder(
    context: Context,
    tag: String,
    scope: CoroutineScope,
    enabled: Boolean = true,
    fallbackAdUnitIds: List<String> = emptyList(),
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    options: NativeAdOptions = NativeAdLoader.defaultOptions(),
    adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    destroyOnDispose: Boolean = true,
    adCallback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
): NativeAdPreloadHolder {
    if (!enabled) {
        return disabledNativeAdHolder(tag, adOptionVisibility, adCallback)
    }
    return createPreloadHolder(
        tag = tag,
        // Callers enabling Unity mediation should pass an Activity context here.
        context = context,
        fallbackAdUnitIds = fallbackAdUnitIds,
        timeoutPerIdMs = timeoutPerIdMs,
        adOptionVisibility = adOptionVisibility,
        options = options,
        scope = scope,
        destroyOnDispose = destroyOnDispose,
        adCallback = adCallback,
        lifecycleOwner = lifecycleOwner,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
    )
}

/**
 * [createNativeAdPreloadHolder] driven by a [NativeAdSpec]; also registers the spec's
 * preload buffer so the holder pulls from a warm buffer when available.
 */
fun createNativeAdPreloadHolder(
    context: Context,
    spec: NativeAdSpec,
    scope: CoroutineScope,
    enabled: Boolean = true,
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    options: NativeAdOptions = NativeAdLoader.defaultOptions(),
    destroyOnDispose: Boolean = true,
    adCallback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
): NativeAdPreloadHolder {
    if (spec.usePreloadBuffer && enabled) NativeAds.register(spec)
    return createNativeAdPreloadHolder(
        context = context,
        tag = spec.tag,
        scope = scope,
        fallbackAdUnitIds = spec.getAllAdUnitIds(),
        timeoutPerIdMs = timeoutPerIdMs,
        options = options,
        destroyOnDispose = destroyOnDispose,
        adCallback = adCallback,
        lifecycleOwner = lifecycleOwner,
        autoReloadOnResume = autoReloadOnResume,
        cancelOnPause = cancelOnPause,
        enabled = enabled,
    )
}

private fun createPreloadHolder(
    tag: String,
    context: Context,
    fallbackAdUnitIds: List<String>,
    timeoutPerIdMs: Long,
    options: NativeAdOptions,
    adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    scope: CoroutineScope,
    destroyOnDispose: Boolean,
    adCallback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
    cancelOnPause: Boolean = false,
): NativeAdPreloadHolder {
    val state = MutableStateFlow<NativeAdDisplayState>(NativeAdDisplayState.Idle)
    val ids = fallbackAdUnitIds.filter { it.isNotBlank() }.distinct()
    val callbacks = CopyOnWriteArrayList<AdsCallback>().apply {
        adCallback?.let(::add)
    }

    val launchOnce: (Boolean) -> Job = { preserveCurrent ->
        scope.launch(Dispatchers.Main.immediate) {
            val previousSuccess = state.value as? NativeAdDisplayState.Success
            // Ready ad in the buffer → fill immediately, never flash the shimmer/Loading.
            var preloaded = NativeAds.getDetailed(tag)
            if (preloaded == null && NativeAds.isLoading(tag)) {
                if (!preserveCurrent) {
                    state.value = NativeAdDisplayState.Loading
                }
                val waitTimeoutMs = (timeoutPerIdMs.coerceAtLeast(1L) * ids.size.coerceAtLeast(1)) + 500L
                NativeAdLog.d(
                    "request",
                    tag,
                    "buffer empty but preload is in-flight — waiting up to ${waitTimeoutMs}ms"
                )
                NativeAds.awaitAvailable(tag, minCount = 1, timeoutMs = waitTimeoutMs)
                preloaded = NativeAds.getDetailed(tag)
                if (preloaded == null) {
                    NativeAdLog.w("request", tag, "preload finished/timeout with no ready ad — not starting duplicate cold load")
                    state.value = NativeAdDisplayState.Error(null)
                    previousSuccess?.takeIf { !preserveCurrent }?.destroy()
                    callbacks.toList().notifyFailedToLoad()
                    return@launch
                }
            }
            if (preloaded != null) {
                NativeAdLog.d(
                    "request",
                    tag,
                    "ready — fill from buffer immediately (no shimmer), queue remaining=${
                        NativeAds.available(tag)
                    }",
                    unit = preloaded.adUnitId
                )
                val ad = ApNativeAd(preloaded.nativeAd)
                preloaded.eventRelay.addListeners(callbacks.toList())
                state.value = NativeAdDisplayState.Success(
                    ad = ad,
                    adUnitId = preloaded.adUnitId,
                    fromPreload = true,
                    eventRelay = preloaded.eventRelay
                )
                previousSuccess?.destroyIfDifferent(ad)
                return@launch
            }

            // No ready ad → now show loading and cold-load.
            if (!preserveCurrent) {
                state.value = NativeAdDisplayState.Loading
            }
            if (ids.isNotEmpty()) {
                NativeAdLog.d(
                    "request",
                    tag,
                    "buffer empty (queue=0) — cold loading (compose) units=$ids"
                )
                runColdRequest(
                    context,
                    tag,
                    ids,
                    timeoutPerIdMs,
                    options,
                    state,
                    callbacks,
                    previousSuccess = previousSuccess.takeIf { preserveCurrent }
                )
            } else {
                NativeAdLog.w("request", tag, "buffer empty and no fallback ad unit ids — failing")
                state.value = NativeAdDisplayState.Error(null)
                previousSuccess?.destroy()
                callbacks.toList().notifyFailedToLoad()
            }
        }
    }

    var holderRef: NativeAdPreloadHolder? = null
    var resumeCount = 0
    fun handleResume(holder: NativeAdPreloadHolder) {
        resumeCount++
        NativeAdLog.d(
            "request",
            tag,
            "lifecycle ON_RESUME #$resumeCount, state=${holder.currentState.displayLabel()}, " +
                "autoReload=$autoReloadOnResume"
        )
        // Never initiate a request from the lifecycle — the caller drives the first
        // request(). Only silently reload an already-loaded ad when returning to screen.
        if (resumeCount > 1 && autoReloadOnResume &&
            holder.currentState is NativeAdDisplayState.Success
        ) {
            NativeAdLog.d("request", tag, "lifecycle auto-reload on resume #$resumeCount")
            holder.reload()
        }
    }
    val observer = lifecycleOwner?.let {
        LifecycleEventObserver { _, event ->
            val holder = holderRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> handleResume(holder)

                Lifecycle.Event.ON_PAUSE -> {
                    if (cancelOnPause) {
                        NativeAdLog.d("request", tag, "lifecycle ON_PAUSE — cancelling load")
                        holder.cancelLoad()
                    }
                }

                Lifecycle.Event.ON_DESTROY -> holder.dispose()
                else -> Unit
            }
        }
    }
    val lifecycle = lifecycleOwner?.lifecycle
    observer?.let { lifecycle?.addObserver(it) }

    return NativeAdPreloadHolder(
        adOptionVisibility = adOptionVisibility, adCallbacks = callbacks,
        lifecycleManaged = lifecycleOwner != null,
        destroyOnDispose = destroyOnDispose,
        tag = tag, _state = state, job = null, restartBlock = launchOnce, onDispose = {
            observer?.let { lifecycle?.removeObserver(it) }
        }).also {
            holderRef = it
            if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
                handleResume(it)
            }
        }
}

private suspend fun runColdRequest(
    context: android.content.Context,
    tag: String,
    adUnitIds: List<String>,
    timeoutPerIdMs: Long,
    options: NativeAdOptions,
    state: MutableStateFlow<NativeAdDisplayState>,
    callbacks: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList(),
    previousSuccess: NativeAdDisplayState.Success? = null,
) {
    val loadCallback = object : AdsCallback() {
        override fun onAdLoaded() {
            callbacks.forEach { it.onAdLoaded() }
        }

        override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
            callbacks.forEach { it.onNativeAdLoaded(nativeAd) }
        }
    }
    val result = NativeAdLoader.waterfall(
        context = context,
        adUnitIds = adUnitIds,
        timeoutsMs = adUnitIds.map { timeoutPerIdMs },
        options = options,
        tag = tag,
        callbacks = listOf(loadCallback)
    )
    if (result != null) {
        NativeAdLog.d("request", tag, "cold load success (compose)", unit = result.adUnitId)
        val ad = ApNativeAd(result.ad)
        result.relay.addListeners(callbacks.toList())
        state.value = NativeAdDisplayState.Success(
            ad = ad,
            adUnitId = result.adUnitId,
            fromPreload = false,
            eventRelay = result.relay
        )
        previousSuccess?.destroyIfDifferent(ad)
    } else {
        NativeAdLog.w("request", tag, "cold load failed (compose)")
        state.value = NativeAdDisplayState.Error(null)
        previousSuccess?.destroy()
        callbacks.toList().notifyFailedToLoad()
    }
}

private fun NativeAdDisplayState.Success.destroyIfDifferent(newAd: ApNativeAd) {
    if (ad !== newAd) destroy()
}

private fun NativeAdDisplayState.Success.destroy() {
    eventRelay?.clearListeners()
    ad.destroy()
}

private fun NativeAdEventRelay.addListeners(callbacks: List<AdsCallback>) {
    callbacks.forEach(::addListener)
}

private fun List<AdsCallback>.notifyFailedToLoad() {
    forEach { it.onAdFailedToLoad(null) }
}
