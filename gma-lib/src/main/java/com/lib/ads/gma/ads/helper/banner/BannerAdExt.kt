package com.lib.ads.gma.ads.helper.banner

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.helper.AdViewRenderer
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.banner.params.loadingHeightDp
import com.lib.ads.gma.ads.helper.banner.preload.BannerAd
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdDisplayState
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdHolder
import com.lib.ads.gma.ads.helper.banner.preload.BannerPreloadRequestController
import com.lib.ads.gma.ads.helper.banner.preload.rememberBannerAd
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.gma.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import com.lib.ads.gma.compose.AdDebugInfo
import com.lib.ads.gma.compose.addDebugOverlayToView
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * One-liner mirroring `interstitialAdWaterfall` / `rewardedAdWaterfall`: a banner holder bound to
 * this Activity's lifecycle and rendered into [container].
 */
fun ComponentActivity.bannerAdWaterfall(
    adUnitIds: List<String>,
    container: () -> FrameLayout,
    collapsibleGravity: BannerCollapseGravity? = null,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    autoRequest: Boolean = true,
    adCallback: BannerAdListener? = null,
    tag: String = "banner:" + adUnitIds.joinToString(","),
): Lazy<BannerAdHolder> = lazy {
    createBannerAdHolder(
        tag = tag,
        options = BannerAdPreloadHolderOptions(
            fallbackAdUnitIds = adUnitIds,
            lifecycleOwner = this,
            autoRequestOnStart = autoRequest,
            size = if (useInline) BannerSize.InlineAdaptive(maxHeightDp) else BannerSize.LargePortraitAdaptive,
            collapsibleGravity = collapsibleGravity,
        ),
    ).value.also { holder ->
        adCallback?.let(holder::registerAdCallback)
        holder.bindToContainer(this, container(), collapsibleGravity)
    }
}

/**
 * Renders [this] holder into an XML [container] for as long as [owner] lives: the container's
 * `shimmer_container_banner` child while nothing is shown, the banner once loaded, and the
 * container hidden on error/cancel.
 */
fun BannerAdHolder.bindToContainer(
    owner: LifecycleOwner,
    container: FrameLayout,
    collapsibleGravity: BannerCollapseGravity? = null,
): Job {
    val shimmer = container.findViewById<View>(R.id.shimmer_container_banner)
    val dividerPx = container.resources.getDimensionPixelOffset(R.dimen._1sdp)
    val holder = this
    return owner.lifecycleScope.launch {
        combine(state, lastError) { state, error -> state to error }.collect { (state, error) ->
            val tester = AdsProvider.getInstance().adConfigOrNull?.showMessageForTester == true
            when (state) {
                is BannerAdDisplayState.Loaded -> {
                    container.visibility = View.VISIBLE
                    AdViewRenderer.banner(container, state.adView, collapsibleGravity?.value, dividerPx)
                }
                BannerAdDisplayState.Idle, BannerAdDisplayState.Loading -> {
                    container.visibility = View.VISIBLE
                    shimmer?.visibility = View.VISIBLE
                }
                is BannerAdDisplayState.Error -> {
                    // Testers keep the empty container so the overlay can show why it failed.
                    container.visibility = if (tester) View.VISIBLE else View.GONE
                    shimmer?.visibility = View.GONE
                }
                BannerAdDisplayState.Cancelled -> container.visibility = View.GONE
            }
            if (tester) {
                addDebugOverlayToView(
                    container = container,
                    context = container.context,
                    info = AdDebugInfo(
                        adType = "Banner",
                        state = when (state) {
                            BannerAdDisplayState.Idle -> "Idle"
                            BannerAdDisplayState.Loading -> "Loading"
                            is BannerAdDisplayState.Loaded -> "Loaded"
                            is BannerAdDisplayState.Error -> "Error"
                            BannerAdDisplayState.Cancelled -> "Cancelled"
                        },
                        adUnitId = (state as? BannerAdDisplayState.Loaded)?.adUnitId,
                        cacheCount = holder.tag?.let(BannerAds::available),
                        requestCount = holder.tag?.let(BannerAds::requestCount),
                        inFlightRequests = holder.tag?.let(BannerAds::inFlightRequests),
                        tag = holder.tag,
                        error = error,
                    ),
                )
            }
        }
    }
}

/**
 * Creates a banner holder that consumes the cached ad registered under [tag]. If the tag has not
 * been registered, [BannerAdPreloadHolderOptions.fallbackAdUnitIds] enables a normal cold load.
 * No Activity is needed: loading uses the application context and the holder hands out a ready
 * `AdView` for the caller to attach.
 *
 * For XML/Activity screens only — [options]'s `lifecycleOwner` binds the holder's request/reload
 * to that owner's lifecycle for as long as the holder lives. In Compose, use [rememberBannerAd] or
 * [BannerAd] instead: they bind to `LocalLifecycleOwner.current`, i.e. the Compose screen's own
 * lifecycle, not the hosting Activity's. Creating a holder here with the Activity and passing it
 * down into Compose ties the ad's lifetime to the Activity even if the Compose screen is swapped
 * out (e.g. by Navigation) while the Activity stays alive.
 */
fun createBannerAdHolder(
    tag: String,
    enabled: Boolean = true,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    options: BannerAdPreloadHolderOptions = BannerAdPreloadHolderOptions(),
): Lazy<BannerAdHolder> = lazy {
    buildBannerAdHolder(tag, enabled, scope, options).also { holder ->
        if (enabled) options.lifecycleOwner?.let { bindBannerHolderLifecycle(it, holder, options) }
    }
}

/** Builds the holder + request controller. Shared by [createBannerAdHolder], Compose and lists. */
internal fun buildBannerAdHolder(
    tag: String,
    enabled: Boolean,
    scope: CoroutineScope,
    options: BannerAdPreloadHolderOptions,
): BannerAdHolder {
    val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Idle)
    val lastError = MutableStateFlow<String?>(null)
    lateinit var controller: BannerPreloadRequestController

    val holder = BannerAdHolder(
        _state = state,
        job = null,
        restartBlock = { controller.request() },
        onDispose = {
            controller.cancel()
            (state.value as? BannerAdDisplayState.Loaded)?.adView?.destroy()
        },
        loadingHeightDp = options.size.loadingHeightDp(AdsProvider.getInstance().applicationContextOrNull()),
        tag = tag,
        _lastError = lastError,
    )
    controller = BannerPreloadRequestController(tag, enabled, scope, options, state, holder.callbackRelay, lastError)
    controller.ensureRegistered()
    if (!enabled) state.value = BannerAdDisplayState.Cancelled
    return holder
}

/**
 * Binds request/reload/pause/dispose to [owner]. Shared by [createBannerAdHolder] and
 * [rememberBannerAd]. Returns a function that detaches the observer early (Compose leaving
 * composition).
 */
internal fun bindBannerHolderLifecycle(
    owner: LifecycleOwner,
    holder: BannerAdHolder,
    options: BannerAdPreloadHolderOptions,
): () -> Unit {
    var hasResumed = false
    var pauseJob: Job? = null
    lateinit var observer: LifecycleEventObserver
    observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                pauseJob?.cancel()
                pauseJob = null
                val isFirstResume = !hasResumed
                hasResumed = true
                when {
                    isFirstResume && options.autoRequestOnStart &&
                            holder.currentState is BannerAdDisplayState.Idle -> holder.request()
                    !isFirstResume && options.autoReloadOnResume -> holder.reload()
                }
            }
            Lifecycle.Event.ON_PAUSE -> {
                if (options.cancelOnPause) {
                    pauseJob?.cancel()
                    pauseJob = owner.lifecycleScope.launch {
                        delay(500.milliseconds)
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            holder.pause()
                        }
                    }
                }
            }
            Lifecycle.Event.ON_DESTROY -> {
                pauseJob?.cancel()
                owner.lifecycle.removeObserver(observer)
                holder.dispose()
            }
            else -> Unit
        }
    }
    // addObserver replays ON_CREATE..ON_RESUME for an owner that is already resumed, so the first
    // request fires from the observer itself; requesting again here would consume a second banner.
    owner.lifecycle.addObserver(observer)
    return {
        pauseJob?.cancel()
        owner.lifecycle.removeObserver(observer)
    }
}
