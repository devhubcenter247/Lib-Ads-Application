package com.lib.ads.gma.ads.helper.nativebanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.lib.adlib.R
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.admob.AdsConsentManager
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.banner.DefaultBannerAdLoading
import com.lib.ads.gma.ads.manager.AdmobNativeBannerManager
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

// ──────────────────────────────────────────────
// "Native banner" = an AdMob native ad rendered in a compact, banner-shaped layout. The display
// logic mirrors the regular Banner: waterfall over a list of ids, a per-id load timeout, an
// optional auto time-reload, and a silent reload on resume that swaps the ad in without flashing
// the shimmer again.
// ──────────────────────────────────────────────

sealed class NativeBannerAdDisplayState {
    data object Idle : NativeBannerAdDisplayState()
    data object Loading : NativeBannerAdDisplayState()
    data class Loaded(val nativeAd: NativeAd) : NativeBannerAdDisplayState()
    data class Error(val error: ApAdError?) : NativeBannerAdDisplayState()
    data object Cancelled : NativeBannerAdDisplayState()
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

class NativeBannerAdConfig(
    /** Ad unit ids tried in waterfall order (first = primary). A single id is a one-element list. */
    override val listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean = true,
    val layoutId: Int = R.layout.layout_admob_native_banner_default,
    val timeReloadMs: Long = 0L,
    /** Per-id load timeout (waterfall moves to the next id after this). Mirrors the banner. */
    val loadTimeoutMs: Long = 10_000L,
) : IAdsConfig {

    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean = true,
        layoutId: Int = R.layout.layout_admob_native_banner_default,
        timeReloadMs: Long = 0L,
        loadTimeoutMs: Long = 10_000L,
    ) : this(listOf(idAds), canShowAds, canReloadAds, layoutId, timeReloadMs, loadTimeoutMs)
}

// ──────────────────────────────────────────────
// Holder
// ──────────────────────────────────────────────

@Stable
class NativeBannerAdHolder internal constructor(
    private val _state: MutableStateFlow<NativeBannerAdDisplayState>,
    private var job: Job?,
    private val restartBlock: (() -> Job)?,
    private val callbacks: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList(),
    private val canRequestAds: () -> Boolean = { true },
    private val canReloadAds: () -> Boolean = { true },
    private val timeReloadMs: () -> Long = { 0L },
    internal val layoutId: Int,
    internal val lifecycleManaged: Boolean = false,
    private val onDispose: () -> Unit,
) {
    val state: StateFlow<NativeBannerAdDisplayState> = _state.asStateFlow()
    val currentState: NativeBannerAdDisplayState get() = _state.value
    val isLoaded: Boolean get() = currentState is NativeBannerAdDisplayState.Loaded
    val isLoading: Boolean get() = currentState is NativeBannerAdDisplayState.Loading
    val isRequestInFlight: Boolean
        get() = job?.isActive == true || currentState is NativeBannerAdDisplayState.Loading
    val nativeAd: NativeAd?
        get() = (currentState as? NativeBannerAdDisplayState.Loaded)?.nativeAd

    private var lastRequestAtMs = 0L
    private var disposed = false

    fun registerAdCallback(callback: AdsCallback) {
        val added = callbacks.addIfAbsent(callback)
        if (!added) return
        if (currentState is NativeBannerAdDisplayState.Loaded) callback.onAdLoaded()
    }

    fun unregisterAdCallback(callback: AdsCallback) {
        callbacks.remove(callback)
    }

    fun unregisterAllAdCallbacks() {
        callbacks.clear()
    }

    fun request() {
        if (isRequestInFlight) return
        if (!canRequestAds()) {
            _state.value = NativeBannerAdDisplayState.Cancelled
            return
        }
        lastRequestAtMs = System.currentTimeMillis()
        job = restartBlock?.invoke()
    }

    fun reload() {
        if (isRequestInFlight) return
        if (!canRequestAds()) {
            _state.value = NativeBannerAdDisplayState.Cancelled
            return
        }
        if (!canReloadAds() || !canReloadNow()) return
        lastRequestAtMs = System.currentTimeMillis()
        job = restartBlock?.invoke()
    }

    fun cancel() {
        job?.cancel()
        destroyCurrentAd()
        onDispose()
        _state.value = NativeBannerAdDisplayState.Cancelled
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        job?.cancel()
        destroyCurrentAd()
        callbacks.clear()
        onDispose()
    }

    internal fun notifyLoaded() {
        callbacks.forEach { it.onAdLoaded() }
    }

    internal fun notifyFailedToLoad(error: ApAdError?) {
        callbacks.forEach { it.onAdFailedToLoad(error) }
    }

    internal fun notifyClicked() {
        callbacks.forEach { it.onAdClicked() }
    }

    internal fun notifyImpression() {
        callbacks.forEach { it.onAdImpression() }
    }

    private fun canReloadNow(): Boolean {
        val minInterval = timeReloadMs()
        return minInterval <= 0L || lastRequestAtMs == 0L ||
            lastRequestAtMs + minInterval <= System.currentTimeMillis()
    }

    private fun destroyCurrentAd() {
        (currentState as? NativeBannerAdDisplayState.Loaded)?.nativeAd?.destroy()
    }
}

// ──────────────────────────────────────────────
// Remember
// ──────────────────────────────────────────────

@Composable
fun rememberNativeBannerAd(
    config: NativeBannerAdConfig,
    autoReloadOnResume: Boolean = true,
    callback: AdsCallback? = null,
): NativeBannerAdHolder {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val holder = remember(
        config.idAds,
        config.listId.hashCode(),
        config.layoutId,
        config.canShowAds,
        config.canReloadAds,
        config.timeReloadMs,
        inspection,
    ) {
        if (inspection) {
            previewNativeBannerAdHolder(config.layoutId)
        } else {
            createNativeBannerAdHolder(
                context = context,
                config = config,
                scope = scope,
                autoReloadOnResume = false,
            )
        }
    }

    if (inspection) return holder

    DisposableEffect(holder, callback) {
        callback?.let(holder::registerAdCallback)
        onDispose { callback?.let(holder::unregisterAdCallback) }
    }

    var resumeCount by remember(holder) { mutableIntStateOf(0) }
    if (!holder.lifecycleManaged) {
        DisposableEffect(lifecycleOwner, holder) {
            fun handleResume() {
                resumeCount++
                // Never initiate a request from the lifecycle — the caller drives the first
                // request(). On return to the screen we only silently reload a loaded ad.
                if (resumeCount > 1 && autoReloadOnResume &&
                    holder.currentState is NativeBannerAdDisplayState.Loaded
                ) {
                    holder.reload()
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> handleResume()
                    Lifecycle.Event.ON_DESTROY -> holder.dispose()
                    else -> Unit
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
    }

    return holder
}

@Composable
fun rememberNativeBannerAd(
    holder: NativeBannerAdHolder?,
    autoReloadOnResume: Boolean = true,
    callback: AdsCallback? = null,
): NativeBannerAdHolder? {
    if (LocalInspectionMode.current) {
        return holder ?: previewNativeBannerAdHolder(R.layout.layout_admob_native_banner_default)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    if (holder == null) return null

    DisposableEffect(holder, callback) {
        callback?.let(holder::registerAdCallback)
        onDispose { callback?.let(holder::unregisterAdCallback) }
    }

    var resumeCount by remember(holder) { mutableIntStateOf(0) }
    if (!holder.lifecycleManaged) {
        DisposableEffect(lifecycleOwner, holder) {
            fun handleResume() {
                resumeCount++
                if (resumeCount > 1 && autoReloadOnResume &&
                    holder.currentState is NativeBannerAdDisplayState.Loaded
                ) {
                    holder.reload()
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> handleResume()
                    Lifecycle.Event.ON_DESTROY -> holder.dispose()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                handleResume()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    return holder
}

// ──────────────────────────────────────────────
// Display composables
// ──────────────────────────────────────────────

@Composable
fun NativeBannerAdCard(
    holder: NativeBannerAdHolder,
    modifier: Modifier = Modifier,
    loading: @Composable (Modifier) -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((NativeBannerAdDisplayState) -> Unit)? = null,
    content: (@Composable (ApNativeAd) -> Unit)? = null,
) {
    if (LocalInspectionMode.current) {
        Box(modifier) { loading(modifier) }
        return
    }

    val state by holder.state.collectAsStateWithLifecycle(NativeBannerAdDisplayState.Idle)
    LaunchedEffect(state) {
        onStateChange?.invoke(state)
    }

    when (val current = state) {
        is NativeBannerAdDisplayState.Idle,
        is NativeBannerAdDisplayState.Loading -> Box(modifier) { loading(modifier) }

        is NativeBannerAdDisplayState.Error -> Box(modifier) { error(current.error) }
        is NativeBannerAdDisplayState.Cancelled -> Box(modifier.height(0.dp))
        is NativeBannerAdDisplayState.Loaded ->
            key(current.nativeAd) {
                if (content != null) {
                    val apNativeAd = remember(current.nativeAd) { ApNativeAd(current.nativeAd) }
                    NativeBannerAdComposeView(
                        apNativeAd = apNativeAd,
                        modifier = modifier,
                        content = content,
                    )
                } else {
                    NativeBannerAdView(
                        nativeAd = current.nativeAd,
                        layoutId = holder.layoutId,
                        modifier = modifier
                    )
                }
            }
    }
}

@Composable
fun NativeBannerAd(
    config: NativeBannerAdConfig,
    modifier: Modifier = Modifier,
    autoReloadOnResume: Boolean = true,
    loading: @Composable (Modifier) -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((NativeBannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
    content: (@Composable (ApNativeAd) -> Unit)? = null,
) {
    val holder = rememberNativeBannerAd(
        config = config,
        autoReloadOnResume = autoReloadOnResume,
        callback = callback
    )
    if (!LocalInspectionMode.current) {
        LaunchedEffect(holder) { holder.request() }
    }
    NativeBannerAdCard(holder, modifier, loading, error, onStateChange, content)
}

/**
 * Display an AdMob native ad in a compact banner-shaped view with minimal setup — no
 * [NativeBannerAdConfig] needed. Loads on first composition, auto-reloads on resume, shows
 * [loading] while loading and collapses when there is no ad.
 *
 * ```kotlin
 * NativeBannerAd(placementId = BuildConfig.ad_native)
 *
 * // custom small layout / auto-refresh every 30s:
 * NativeBannerAd(
 *     placementId = BuildConfig.ad_native,
 *     layoutId = R.layout.my_native_banner,
 *     timeReloadMs = 30_000L,
 * )
 * ```
 */
@Composable
fun NativeBannerAd(
    placementId: String,
    modifier: Modifier = Modifier,
    layoutId: Int = R.layout.layout_admob_native_banner_default,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    timeReloadMs: Long = 0L,
    loadTimeoutMs: Long = 10_000L,
    autoReloadOnResume: Boolean = true,
    loading: @Composable (Modifier) -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((NativeBannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
    content: (@Composable (ApNativeAd) -> Unit)? = null,
) {
    val config = remember(placementId, layoutId, canShowAds, canReloadAds, timeReloadMs, loadTimeoutMs) {
        NativeBannerAdConfig(
            idAds = placementId,
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
            layoutId = layoutId,
            timeReloadMs = timeReloadMs,
            loadTimeoutMs = loadTimeoutMs,
        )
    }
    NativeBannerAd(
        config = config,
        modifier = modifier,
        autoReloadOnResume = autoReloadOnResume,
        loading = loading,
        error = error,
        onStateChange = onStateChange,
        callback = callback,
        content = content,
    )
}

/**
 * Waterfall variant of [NativeBannerAd]: tries [placementIds] in order (with a per-id timeout)
 * until one fills.
 *
 * ```kotlin
 * NativeBannerAd(
 *     placementIds = NativePlacement.NativeSmallAll.listId,
 *     timeReloadMs = 30_000L,
 *     modifier = Modifier.fillMaxWidth(),
 * )
 * ```
 */
@Composable
fun NativeBannerAd(
    placementIds: List<String>,
    modifier: Modifier = Modifier,
    layoutId: Int = R.layout.layout_admob_native_banner_default,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    timeReloadMs: Long = 0L,
    loadTimeoutMs: Long = 10_000L,
    autoReloadOnResume: Boolean = true,
    loading: @Composable (Modifier) -> Unit = { DefaultBannerAdLoading() },
    error: @Composable (ApAdError?) -> Unit = {},
    onStateChange: ((NativeBannerAdDisplayState) -> Unit)? = null,
    callback: AdsCallback? = null,
    content: (@Composable (ApNativeAd) -> Unit)? = null,
) {
    val config = remember(placementIds.hashCode(), layoutId, canShowAds, canReloadAds, timeReloadMs, loadTimeoutMs) {
        NativeBannerAdConfig(
            listId = placementIds,
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
            layoutId = layoutId,
            timeReloadMs = timeReloadMs,
            loadTimeoutMs = loadTimeoutMs,
        )
    }
    NativeBannerAd(
        config = config,
        modifier = modifier,
        autoReloadOnResume = autoReloadOnResume,
        loading = loading,
        error = error,
        onStateChange = onStateChange,
        callback = callback,
        content = content,
    )
}

// ──────────────────────────────────────────────
// AndroidView wrapper
// ──────────────────────────────────────────────

@Composable
private fun NativeBannerAdView(
    nativeAd: NativeAd,
    layoutId: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val key = System.identityHashCode(nativeAd)
            if (container.tag != key) {
                AdmobNativeBannerManager.inflate(container.context, nativeAd, container, layoutId)
                container.tag = key
            }
        }
    )
}

/**
 * Renders the loaded native ad with a caller-supplied Compose [content] instead of an XML layout.
 *
 * The content is hosted inside a [NativeAdView] (required by the AdMob SDK) and the whole area is
 * registered as the call-to-action view so taps are reported and impressions tracked. The [content]
 * draws from the passed [ApNativeAd] assets (e.g. `apNativeAd.admobNativeAd?.headline`).
 */
@Composable
private fun NativeBannerAdComposeView(
    apNativeAd: ApNativeAd,
    modifier: Modifier = Modifier,
    content: @Composable (ApNativeAd) -> Unit,
) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        factory = { context ->
            val composeView = ComposeView(context)
            NativeAdView(context).apply {
                addView(composeView)
                callToActionView = composeView
            }
        },
        update = { adView ->
            val composeView = adView.getChildAt(0) as ComposeView
            composeView.setContent { content(apNativeAd) }
            apNativeAd.admobNativeAd?.let { adView.setNativeAd(it) }
        }
    )
}

// ──────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────

fun createNativeBannerAdHolder(
    context: Context,
    idAds: String,
    scope: CoroutineScope,
    layoutId: Int = R.layout.layout_admob_native_banner_default,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    timeReloadMs: Long = 0L,
    loadTimeoutMs: Long = 10_000L,
    listId: List<String> = emptyList(),
    callback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
): NativeBannerAdHolder = createNativeBannerAdHolder(
    context = context,
    config = NativeBannerAdConfig(
        listId = listId.ifEmpty { listOf(idAds) },
        canShowAds = canShowAds,
        canReloadAds = canReloadAds,
        layoutId = layoutId,
        timeReloadMs = timeReloadMs,
        loadTimeoutMs = loadTimeoutMs,
    ),
    scope = scope,
    callback = callback,
    lifecycleOwner = lifecycleOwner,
    autoReloadOnResume = autoReloadOnResume,
)

/** Waterfall factory: tries [adUnitIds] in order (per-id [loadTimeoutMs]) until one fills. */
fun createNativeBannerAdHolder(
    context: Context,
    adUnitIds: List<String>,
    scope: CoroutineScope,
    layoutId: Int = R.layout.layout_admob_native_banner_default,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    timeReloadMs: Long = 0L,
    loadTimeoutMs: Long = 10_000L,
    callback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
): NativeBannerAdHolder = createNativeBannerAdHolder(
    context = context,
    config = NativeBannerAdConfig(
        listId = adUnitIds,
        canShowAds = canShowAds,
        canReloadAds = canReloadAds,
        layoutId = layoutId,
        timeReloadMs = timeReloadMs,
        loadTimeoutMs = loadTimeoutMs,
    ),
    scope = scope,
    callback = callback,
    lifecycleOwner = lifecycleOwner,
    autoReloadOnResume = autoReloadOnResume,
)

private fun previewNativeBannerAdHolder(layoutId: Int): NativeBannerAdHolder = NativeBannerAdHolder(
    _state = MutableStateFlow(NativeBannerAdDisplayState.Idle),
    job = null,
    restartBlock = null,
    layoutId = layoutId,
    onDispose = {},
)

fun createNativeBannerAdHolder(
    context: Context,
    config: NativeBannerAdConfig,
    scope: CoroutineScope,
    callback: AdsCallback? = null,
    lifecycleOwner: LifecycleOwner? = null,
    autoReloadOnResume: Boolean = true,
): NativeBannerAdHolder {
    val state = MutableStateFlow<NativeBannerAdDisplayState>(
        if (canRequestNativeBannerAds(context, config)) {
            NativeBannerAdDisplayState.Idle
        } else {
            NativeBannerAdDisplayState.Cancelled
        }
    )
    val callbacks = CopyOnWriteArrayList<AdsCallback>().apply {
        callback?.let(::add)
    }
    var holderRef: NativeBannerAdHolder? = null
    var activeAd: NativeAd? = null
    var timedReloadJob: Job? = null
    var resumeCount = 0

    fun NativeBannerAdHolder.scheduleTimedReload() {
        timedReloadJob?.cancel()
        val delayMs = config.timeReloadMs.coerceAtLeast(0L)
        if (delayMs <= 0L || !config.canReloadAds) return
        timedReloadJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            if (currentState is NativeBannerAdDisplayState.Loaded) {
                reload()
            }
        }
    }

    // Load a single AdMob native ad. Resumes null on failure; the caller applies a timeout and
    // moves to the next waterfall id. Click / impression are forwarded to the holder callbacks.
    suspend fun loadOne(adId: String): NativeAd? = suspendCancellableCoroutine { cont ->
        val adOptions = NativeAdOptions.Builder()
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .build()
        val adLoader = AdLoader.Builder(context, adId)
            .forNativeAd { nativeAd ->
                if (cont.isActive) cont.resume(nativeAd) else nativeAd.destroy()
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onAdClicked() {
                    AdsManager.handleAdClick(context, adId)
                    holderRef?.notifyClicked()
                }

                override fun onAdImpression() {
                    AdsManager.handleAdImpression()
                    holderRef?.notifyImpression()
                }
            })
            .withNativeAdOptions(adOptions)
            .build()
        adLoader.loadAd(AdsManager.getAdRequest())
    }

    val launchOnce: () -> Job = {
        scope.launch(Dispatchers.Main.immediate) {
            if (!canRequestNativeBannerAds(context, config)) {
                state.value = NativeBannerAdDisplayState.Cancelled
                return@launch
            }

            // Ad currently on screen (only present on a reload). On a silent reload we keep it
            // visible WHILE loading so the shimmer never flashes again; the new result is still
            // published — swap on success, Error on failure (banner-faithful).
            val previousAd = (state.value as? NativeBannerAdDisplayState.Loaded)?.nativeAd
            if (previousAd == null) {
                state.value = NativeBannerAdDisplayState.Loading
            }
            activeAd?.takeIf { it !== previousAd }?.destroy()
            activeAd = null

            // Waterfall: try each id in order with a per-id timeout; only Error after the last.
            val ids = config.listId
            val timeout = config.loadTimeoutMs.coerceAtLeast(1L)
            var loaded: NativeAd? = null
            var loadedId: String? = null
            for (adId in ids) {
                val result = withTimeoutOrNull(timeout) { loadOne(adId) }
                if (result != null) {
                    loaded = result
                    loadedId = adId
                    break
                }
            }

            if (loaded == null) {
                val apError = ApAdError("Native banner failed to load")
                state.value = NativeBannerAdDisplayState.Error(apError)
                previousAd?.destroy()
                holderRef?.notifyFailedToLoad(apError)
                return@launch
            }

            val winner = loaded
            val appCtx = context.applicationContext
            winner.setOnPaidEventListener { adValue ->
                AdsManager.logPaidEvent(appCtx, adValue, loadedId ?: config.idAds, winner.responseInfo, AdType.NATIVE)
            }
            activeAd = winner
            state.value = NativeBannerAdDisplayState.Loaded(winner)
            previousAd?.takeIf { it !== winner }?.destroy()
            holderRef?.notifyLoaded()
            holderRef?.scheduleTimedReload()
        }
    }

    fun handleResume(holder: NativeBannerAdHolder) {
        resumeCount++
        // Never initiate a request from the lifecycle — the caller drives the first request().
        // On return to the screen we only silently reload an already-loaded ad.
        if (resumeCount > 1 && autoReloadOnResume &&
            holder.currentState is NativeBannerAdDisplayState.Loaded
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

    return NativeBannerAdHolder(
        _state = state,
        job = null,
        restartBlock = launchOnce,
        callbacks = callbacks,
        canRequestAds = { canRequestNativeBannerAds(context, config) },
        canReloadAds = { config.canReloadAds },
        timeReloadMs = { config.timeReloadMs.coerceAtLeast(0L) },
        layoutId = config.layoutId,
        lifecycleManaged = lifecycleOwner != null,
        onDispose = {
            timedReloadJob?.cancel()
            timedReloadJob = null
            if (state.value !is NativeBannerAdDisplayState.Loaded) {
                activeAd?.destroy()
            }
            activeAd = null
            observer?.let { lifecycle?.removeObserver(it) }
        }
    ).also {
        holderRef = it
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            handleResume(it)
        }
    }
}

private fun canRequestNativeBannerAds(context: Context, config: NativeBannerAdConfig): Boolean {
    return config.canShowAds &&
        !AppPurchase.getInstance().isPurchased() &&
        AdsConsentManager.getConsentResult(context) &&
        context.isOnline()
}

@Suppress("DEPRECATION")
private fun Context.isOnline(): Boolean {
    return runCatching {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return@runCatching false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            val netInfo = connectivityManager.activeNetworkInfo
            netInfo != null && netInfo.isConnected
        }
    }.getOrDefault(false)
}
