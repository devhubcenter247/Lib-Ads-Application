package com.lib.ads.gma.ads.helper.banner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.manager.BannerAdManager
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInspectionMode
import com.lib.ads.gma.ads.debug.AdDebugInfo
import com.lib.ads.gma.ads.debug.AdDebugOverlayCompose
import com.lib.ads.gma.ads.helper.BannerCollapseGravity
import com.lib.ads.gma.ads.manager.AdsManager

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────

sealed class BannerAdDisplayState {
    data object Idle : BannerAdDisplayState()
    data object Loading : BannerAdDisplayState()
    data class Loaded(val adView: AdManagerAdView) : BannerAdDisplayState()
    data class Error(val error: ApAdError?) : BannerAdDisplayState()
    data object Cancelled : BannerAdDisplayState()
}

// ──────────────────────────────────────────────
// Holder
// ──────────────────────────────────────────────

@Stable
class BannerAdHolder internal constructor(
    private val _state: MutableStateFlow<BannerAdDisplayState>,
    private var job: Job?,
    private val restartBlock: ((silent: Boolean) -> Job)?,
    private val adCallbacks: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList(),
    private val canShowAds: () -> Boolean = { true },
    private val canReloadAds: () -> Boolean = { true },
    private val timeReloadMs: () -> Long = { 0L },
    internal val lifecycleManaged: Boolean = false,
    private val onDispose: () -> Unit,
    private val setWidthBlock: (Int) -> Unit = {},
    private val hasContainerWidth: () -> Boolean = { true },
) {
    val state: StateFlow<BannerAdDisplayState> = _state.asStateFlow()
    val currentState: BannerAdDisplayState get() = _state.value
    val isLoaded: Boolean get() = currentState is BannerAdDisplayState.Loaded
    val isLoading: Boolean get() = currentState is BannerAdDisplayState.Loading
    val isRequestInFlight: Boolean get() = job?.isActive == true
    val adView: AdManagerAdView? get() = (currentState as? BannerAdDisplayState.Loaded)?.adView
    private var lastRequestAtMs: Long = 0L
    private var disposed = false
    private var pendingRequestSilent: Boolean? = null

    fun setContainerWidthPx(widthPx: Int) = apply {
        setWidthBlock(widthPx)
        if (widthPx > 0) {
            pendingRequestSilent?.let { silent ->
                pendingRequestSilent = null
                launchRequest(silent)
            }
        }
    }

    fun registerAdCallback(adCallback: AdsCallback) {
        val added = adCallbacks.addIfAbsent(adCallback)
        if (!added) return
        (currentState as? BannerAdDisplayState.Loaded)?.adView?.let {
            adCallback.onAdLoaded()
            adCallback.onBannerLoaded(it)
        }
    }

    fun unregisterAdCallback(adCallback: AdsCallback) {
        adCallbacks.remove(adCallback)
    }

    fun unregisterAllAdCallbacks() {
        adCallbacks.clear()
    }

    fun request() {
        if (isRequestInFlight) return
        if (!canShowAds()) {
            cancel()
            return
        }
        if (!hasContainerWidth()) {
            pendingRequestSilent = false
            return
        }
        launchRequest(false)
    }

    fun cancel() {
        job?.cancel()
        destroyCurrentAd()
        _state.value = BannerAdDisplayState.Cancelled
        onDispose()
    }

    fun reload() {
        if (isRequestInFlight) return
        if (!canShowAds()) {
            cancel()
            return
        }
        if (!canReloadAds() || !canReloadNow()) return
        job?.cancel()
        // Silent reload (native-style): keep the current ad visible and do NOT reset to
        // Idle/Loading, so the shimmer never flashes again. The background load swaps the
        // ad in (and destroys the old one) only once the new result is known.
        if (!hasContainerWidth()) {
            pendingRequestSilent = true
            return
        }
        launchRequest(true)
    }

    private fun launchRequest(silent: Boolean) {
        if (isRequestInFlight) return
        if (!canShowAds()) {
            cancel()
            return
        }
        lastRequestAtMs = System.currentTimeMillis()
        job = restartBlock?.invoke(silent)
    }

    internal fun dispose() {
        if (disposed) return
        disposed = true
        job?.cancel()
        destroyCurrentAd()
        adCallbacks.clear()
        onDispose()
    }

    internal fun notifyLoaded(adView: AdManagerAdView?) {
        adCallbacks.forEach {
            it.onAdLoaded()
            it.onBannerLoaded(adView)
        }
    }

    internal fun notifyFailedToLoad(adError: ApAdError?) {
        adCallbacks.forEach { it.onAdFailedToLoad(adError) }
    }

    internal fun notifyClicked() {
        adCallbacks.forEach { it.onAdClicked() }
    }

    internal fun notifyImpression() {
        adCallbacks.forEach { it.onAdImpression() }
    }

    internal fun notifyClosed() {
        adCallbacks.forEach { it.onAdClosed() }
    }

    private fun canReloadNow(): Boolean {
        val minInterval = timeReloadMs()
        return minInterval <= 0L || lastRequestAtMs == 0L ||
            lastRequestAtMs + minInterval <= System.currentTimeMillis()
    }

    private fun destroyCurrentAd() {
        (_state.value as? BannerAdDisplayState.Loaded)?.adView?.destroy()
    }
}

// ──────────────────────────────────────────────
// Remember
// ──────────────────────────────────────────────

@Composable
fun rememberBannerAd(
    config: BannerAdConfig,
    enabled: Boolean = true,
    autoReloadOnResume: Boolean = true,
    timeReloadMs: Long = config.timeReloadMs,
    callback: AdsCallback? = null,
): BannerAdHolder {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val holder = remember(config.idAds, config.listId.hashCode(), inspection, enabled, activity) {
        when {
            inspection -> previewBannerAdHolder()
            !enabled || activity == null -> disabledBannerAdHolder(callback)
            else -> createBannerAdHolder(
                activity = activity,
                config = config,
                scope = scope,
                enabled = true,
                // Only bound here for the disabled path, to fire onAdFailedToLoad exactly once at
                // creation. The enabled path keeps this null — callback wiring happens dynamically
                // below via registerAdCallback/unregisterAdCallback so it tracks a changing callback
                // instance across recomposition.
                callback = null,
                autoReloadOnResume = false,
                timeReloadMs = timeReloadMs,
            )
        }
    }

    if (inspection || !enabled || activity == null) {
        return holder
    }

    DisposableEffect(holder, callback) {
        callback?.let(holder::registerAdCallback)
        onDispose {
            callback?.let(holder::unregisterAdCallback)
        }
    }

    var resumeCount by remember(holder) { mutableStateOf(0) }

    if (!holder.lifecycleManaged) DisposableEffect(lifecycleOwner, holder) {
        fun handleResume() {
            resumeCount++
            // Never initiate a request from the lifecycle — the caller drives the first
            // request(). On return to the screen we only silently reload an already-loaded ad.
            if (resumeCount > 1 && autoReloadOnResume &&
                holder.currentState is BannerAdDisplayState.Loaded
            ) {
                holder.reload()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> handleResume()

                Lifecycle.Event.ON_DESTROY -> holder.dispose()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handleResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.dispose()
        }
    }

    return holder
}

// ──────────────────────────────────────────────
// Display composables
// ──────────────────────────────────────────────

@Composable
fun BannerAdCard(
    holder: BannerAdHolder,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
) {

    if(LocalInspectionMode.current){
        Box(modifier){
            loading()
        }
        return
    }

    val state by holder.state.collectAsStateWithLifecycle(BannerAdDisplayState.Idle)

    var loadStartTime by remember { mutableStateOf(0L) }
    var loadTimeMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state) {
        onStateChange?.invoke(state)
        when (state) {
            is BannerAdDisplayState.Loading -> loadStartTime = System.currentTimeMillis()
            is BannerAdDisplayState.Loaded -> {
                if (loadStartTime > 0) {
                    loadTimeMs = System.currentTimeMillis() - loadStartTime
                    loadStartTime = 0L
                }
            }
            else -> Unit
        }
    }

    val contentModifier = remember(state) {
        if (state is BannerAdDisplayState.Cancelled || state is BannerAdDisplayState.Error) {
            Modifier.height(0.dp)
        } else {
            Modifier.wrapContentHeight()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LaunchedEffect(holder, widthPx) {
            if (widthPx > 0) holder.setContainerWidthPx(widthPx)
        }

        Box(contentModifier.fillMaxWidth()) {
            when (val currentState = state) {
                is BannerAdDisplayState.Loading,
                is BannerAdDisplayState.Idle -> loading()

                is BannerAdDisplayState.Error -> error(currentState.error)

                is BannerAdDisplayState.Loaded -> {
                    key(currentState.adView) {
                        BannerAdViewComposable(
                            adView = currentState.adView,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                is BannerAdDisplayState.Cancelled -> Unit
            }
        }

        if (AdsManager.showMessageForTester) {
            val debugInfo = remember(state, loadTimeMs) {
                AdDebugInfo(
                    adType = "Banner",
                    state = state.debugLabel(),
                    adUnitId = (state as? BannerAdDisplayState.Loaded)?.adView?.adUnitId,
                    loadTimeMs = loadTimeMs
                )
            }
            AdDebugOverlayCompose(
                info = debugInfo,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

private fun BannerAdDisplayState.debugLabel(): String = when (this) {
    is BannerAdDisplayState.Idle -> "Idle"
    is BannerAdDisplayState.Loading -> "Loading"
    is BannerAdDisplayState.Loaded -> "Loaded"
    is BannerAdDisplayState.Error -> "Error: ${error?.message}"
    is BannerAdDisplayState.Cancelled -> "Cancelled"
}

/**
 * Display a banner with minimal setup — no [BannerAdConfig] needed.
 *
 * ```kotlin
 * BannerAd(adUnitId = BuildConfig.ad_banner)
 * BannerAd(adUnitId = BuildConfig.ad_banner_collapse, collapsibleGravity = "bottom")
 * ```
 */
@Composable
fun BannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    timeReloadMs: Long = 0L,
    autoReloadOnResume: Boolean = true,
    loading: @Composable () -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
) {
    val config = remember(adUnitId, collapsibleGravity, useInline, maxHeightDp, timeReloadMs) {
        BannerAdConfig.simple(
            adUnitId = adUnitId,
            useInline = useInline,
            maxHeightDp = maxHeightDp,
            collapsibleGravity = collapsibleGravity,
            timeReloadMs = timeReloadMs
        )
    }
    BannerAd(
        config = config,
        modifier = modifier,
        enabled = enabled,
        autoReloadOnResume = autoReloadOnResume,
        timeReloadMs = timeReloadMs,
        loading = loading,
        error = error,
        onStateChange = onStateChange,
        callback = callback
    )
}

/** Waterfall variant of the Compose [BannerAd]. */
@Composable
fun BannerAd(
    adUnitIds: List<String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    timeReloadMs: Long = 0L,
    autoReloadOnResume: Boolean = true,
    loading: @Composable () -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
) {
    val config = remember(adUnitIds.hashCode(), collapsibleGravity, useInline, maxHeightDp, timeReloadMs) {
        BannerAdConfig.waterfall(
            adUnitIds = adUnitIds,
            useInline = useInline,
            maxHeightDp = maxHeightDp,
            collapsibleGravity = collapsibleGravity,
            timeReloadMs = timeReloadMs
        )
    }
    BannerAd(
        config = config,
        modifier = modifier,
        enabled = enabled,
        autoReloadOnResume = autoReloadOnResume,
        timeReloadMs = timeReloadMs,
        loading = loading,
        error = error,
        onStateChange = onStateChange,
        callback = callback
    )
}

@Composable
fun BannerAd(
    config: BannerAdConfig,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoReloadOnResume: Boolean = true,
    timeReloadMs: Long = config.timeReloadMs,
    loading: @Composable () -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
) {
    val holder = rememberBannerAd(
        config = config,
        enabled = enabled,
        autoReloadOnResume = autoReloadOnResume,
        timeReloadMs = timeReloadMs,
        callback = callback,
    )

    LaunchedEffect(holder, enabled) {
        if (enabled) holder.request()
    }

    BannerAdCard(
        holder = holder,
        modifier = modifier,
        loading = loading,
        error = error,
        onStateChange = onStateChange,
    )
}

// ──────────────────────────────────────────────
// AndroidView wrapper
// ──────────────────────────────────────────────

@Composable
private fun BannerAdViewComposable(
    adView: AdManagerAdView,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(Color.White)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFE1E1E1))
        )

        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = {
                (adView.parent as? ViewGroup)?.removeView(adView)
                adView
            },
            update = { view ->
                val parent = view.parent
                if (parent is ViewGroup && parent !is androidx.compose.ui.platform.ComposeView) {
                    parent.removeView(view)
                }
            }
        )
    }

    DisposableEffect(adView) {
        onDispose {
            (adView.parent as? ViewGroup)?.removeView(adView)
        }
    }
}

// ──────────────────────────────────────────────
// Default loading
// ──────────────────────────────────────────────

private val ShimmerPlaceholderColor = Color(0xFFE0E0E0)

@Composable
fun DefaultBannerAdLoading(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shimmer(state = shimmer,visible = true)
            .height(56.dp)
            .background(Color.White)
            .then(modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ShimmerPlaceholderColor)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ShimmerPlaceholderColor)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ShimmerPlaceholderColor)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ShimmerPlaceholderColor))
        }
    }
}

// ──────────────────────────────────────────────
// Internal factory
// ──────────────────────────────────────────────

/**
 * Holder for a placement disabled via `enabled = false`. Fires
 * [AdsCallback.onAdFailedToLoad] once on [adCallback] and starts in
 * [BannerAdDisplayState.Cancelled] (not [BannerAdDisplayState.Idle]) so callers observing either
 * the callback or [BannerAdHolder.state] get a definitive "not loading" signal instead of
 * silently hanging forever (e.g. [BannerAdCard]'s loading composable spinning indefinitely).
 */
private fun disabledBannerAdHolder(adCallback: AdsCallback?): BannerAdHolder {
    val callbacks = CopyOnWriteArrayList<AdsCallback>().apply { adCallback?.let(::add) }
    callbacks.forEach { it.onAdFailedToLoad(null) }
    return BannerAdHolder(
        _state = MutableStateFlow(BannerAdDisplayState.Cancelled),
        job = null,
        restartBlock = null,
        adCallbacks = callbacks,
        onDispose = {},
    )
}

private fun previewBannerAdHolder(): BannerAdHolder = BannerAdHolder(
    _state = MutableStateFlow(BannerAdDisplayState.Idle),
    job = null,
    restartBlock = null,
    onDispose = {},
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun createBannerAdHolder(
    activity: Activity,
    config: BannerAdConfig,
    scope: CoroutineScope,
    enabled: Boolean = true,
    callback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
    timeReloadMs: Long = config.timeReloadMs,
): BannerAdHolder {
    if (!enabled) {
        return disabledBannerAdHolder(callback)
    }
    val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Idle)
    var containerWidthPx = config.containerWidthPx
    val callbacks = CopyOnWriteArrayList<AdsCallback>().apply {
        callback?.let(::add)
    }
    var holderRef: BannerAdHolder? = null
    var timedReloadJob: Job? = null
    var resumeCount = 0

    fun BannerAdHolder.scheduleTimedReload() {
        timedReloadJob?.cancel()
        val delayMs = timeReloadMs.coerceAtLeast(0L)
        if (delayMs <= 0L || !config.canReloadAds) return
        timedReloadJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            if (currentState is BannerAdDisplayState.Loaded) {
                reload()
            }
        }
    }

    val launchOnce: (Boolean) -> Job = { silent ->
        scope.launch(Dispatchers.Main.immediate) {
            if (!config.canShowAds) {
                state.value = BannerAdDisplayState.Cancelled
                return@launch
            }
            // Ad currently on screen (only present on a reload). On a silent reload we keep it
            // visible WHILE loading so the shimmer never flashes again; the new result is still
            // published — swap on success, Error on failure (native-faithful).
            val previousLoaded = state.value as? BannerAdDisplayState.Loaded
            val keepWhileLoading = silent && previousLoaded != null
            if (!keepWhileLoading) {
                state.value = BannerAdDisplayState.Loading
            }

            val widthPx = containerWidthPx
            val callback = object : AdsCallback() {
                override fun onBannerLoaded(adView: AdManagerAdView?) {
                    if (adView is AdManagerAdView) {
                        previousLoaded?.adView?.takeIf { it !== adView }?.destroy()
                        state.value = BannerAdDisplayState.Loaded(adView)
                        holderRef?.notifyLoaded(adView)
                        holderRef?.scheduleTimedReload()
                    } else {
                        previousLoaded?.adView?.destroy()
                        state.value = BannerAdDisplayState.Error(null)
                        holderRef?.notifyFailedToLoad(null)
                    }
                }

                override fun onAdFailedToLoad(adError: ApAdError?) {
                    previousLoaded?.adView?.destroy()
                    state.value = BannerAdDisplayState.Error(adError)
                    holderRef?.notifyFailedToLoad(adError)
                }

                override fun onAdClicked() = holderRef?.notifyClicked() ?: Unit
                override fun onAdImpression() = holderRef?.notifyImpression() ?: Unit
                override fun onAdClosed() = holderRef?.notifyClosed() ?: Unit
            }

            BannerAdManager.loadBannerList(
                context = activity,
                listId = config.listId,
                collapsibleGravity = config.collapsibleGravity.gravity,
                useInlineAdaptive = config.usingInlineBanner,
                maxHeight = config.maxHeight,
                containerWidthPx = widthPx,
                adCallback = callback
            )
        }
    }

    fun handleResume(holder: BannerAdHolder) {
        resumeCount++
        // Never initiate a request from the lifecycle — the caller drives the first
        // request(). On return to the screen we only silently reload an already-loaded ad.
        if (resumeCount > 1 && autoReloadOnResume &&
            holder.currentState is BannerAdDisplayState.Loaded
        ) {
            holder.reload()
        }
    }

    val observer = lifecycleOwner?.let {
        LifecycleEventObserver { _, event ->
            val holder = holderRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> handleResume(holder)
                Lifecycle.Event.ON_DESTROY -> holder.dispose()
                else -> Unit
            }
        }
    }
    val lifecycle = lifecycleOwner?.lifecycle
    observer?.let { lifecycle?.addObserver(it) }

    return BannerAdHolder(
        _state = state,
        job = null,
        restartBlock = launchOnce,
        adCallbacks = callbacks,
        canShowAds = { config.canShowAds },
        canReloadAds = { config.canReloadAds },
        timeReloadMs = { timeReloadMs.coerceAtLeast(0L) },
        lifecycleManaged = lifecycleOwner != null,
        onDispose = {
            timedReloadJob?.cancel()
            timedReloadJob = null
            observer?.let { lifecycle?.removeObserver(it) }
        },
        setWidthBlock = { containerWidthPx = it },
        hasContainerWidth = { containerWidthPx > 0 },
    ).also {
        holderRef = it
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            handleResume(it)
        }
    }
}
