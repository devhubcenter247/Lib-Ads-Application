package com.lib.ads.gma.ads.helper.nativebanner

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.facebook.shimmer.ShimmerFrameLayout
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.helper.AdViewRenderer
import com.lib.ads.gma.ads.helper.adnative.NativeAdRequestOptions
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.PreloadBufferState
import com.lib.ads.gma.ads.helper.adnative.params.NativeLayoutMediation
import com.lib.ads.gma.ads.helper.adnative.params.layoutFor
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdDisplayState
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdHolderConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.bindNativeHolderLifecycle
import com.lib.ads.gma.ads.helper.adnative.preload.buildNativeAdHolder
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.ads.model.wrapper.resolvedAdUnitId
import com.lib.ads.gma.compose.AdDebugInfo
import com.lib.ads.gma.compose.addDebugOverlayToView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

data class NativeBannerConfig(
    val listId: List<String>,
    val canShowAds: Boolean,
    val canReloadAds: Boolean,
    val layoutId: Int = 0,
    val timeReloadMs: Long = 0L,
    val loadTimeout: Long = 10_000L,
    // For a genuine banner-format native ad (Ad Manager's mixed native/banner requests, restricted
    // to Ad Manager accounts) call configureRequest { setNativeAdTypes(listOf(NativeAd.NativeAdType.BANNER)) }
    // — on plain AdMob accounts leave this at the NATIVE default or the request returns no fill.
    var requestOptions: NativeAdRequestOptions = NativeAdRequestOptions(),
    /** Placement tag in [NativeAds]; blank derives one from [listId]. Slots sharing a tag share its buffer. */
    val tag: String = "",
) {
    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        layoutId: Int = 0,
        timeReloadMs: Long = 0L,
        loadTimeout: Long = 10_000L,
        requestOptions: NativeAdRequestOptions = NativeAdRequestOptions()
    ) : this(listOf(idAds), canShowAds, canReloadAds, layoutId, timeReloadMs, loadTimeout, requestOptions)

    fun setRequestOptions(options: NativeAdRequestOptions) = apply {
        // Keep the config immutable from the caller's perspective while allowing
        // builder-style setup before creating NativeBannerHelper.
        requestOptions = options
    }

    fun configureRequest(block: NativeAdRequestOptions.() -> Unit) = apply {
        requestOptions.apply(block)
    }

    /**
     * Requests [com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd.NativeAdType.BANNER]
     * creatives for this native banner slot — Ad Manager only; plain AdMob accounts get no fill.
     */
    fun useBannerAdType(includeNativeType: Boolean = false) = apply {
        requestOptions.requestBannerAdType(includeNativeType)
    }

}

/**
 * Banner-shaped native slot for XML screens: renders into a [FrameLayout] (with an optional
 * shimmer) on top of the same holder/buffer as the Compose native APIs, plus a timed auto-reload
 * ([NativeBannerConfig.timeReloadMs]).
 *
 * Like every native holder, a shown ad is only replaced (timed reload, lifecycle resume) once it
 * has recorded an impression.
 */
class NativeBannerHelper(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val config: NativeBannerConfig
) {
    private val activity: Activity = context as? Activity
        ?: error("NativeBannerHelper requires an Activity context")

    /** This slot's placement tag in [NativeAds]. */
    val tag: String = config.tag.ifBlank { "native_banner:" + config.listId.joinToString(",") }

    private val holderOptions = NativeAdPreloadHolderOptions(
        fallbackAdUnitIds = config.listId,
        timeoutPerIdMs = config.loadTimeout,
        autoRequestOnStart = false,
        autoReloadOnResume = config.canReloadAds,
        requestOptions = config.requestOptions,
        canShowAds = config.canShowAds,
        canReloadAds = config.canReloadAds,
    )
    private val holder: NativeAdHolderConfig
    private val listeners = CopyOnWriteArrayList<NativeAdListener>()
    private var layoutMediation: List<NativeLayoutMediation> = emptyList()
    private var container: FrameLayout? = null
    private var shimmer: ShimmerFrameLayout? = null
    private var onCustomShowView: ((ApNativeAd) -> Unit)? = null
    private var renderedAd: ApNativeAd? = null
    private var reloadJob: Job? = null

    /** Visibility applied to the container/shimmer views when no ad can be shown. */
    var adVisibility: AdOptionVisibility = AdOptionVisibility.GONE
        set(value) {
            field = value
            render(holder.currentState)
        }

    init {
        NativeAds.register(spec(buffer = 1))
        holder = buildNativeAdHolder(tag, enabled = true, lifecycleOwner.lifecycleScope, holderOptions)
        if (holder.currentState !is NativeAdDisplayState.Cancelled) {
            bindNativeHolderLifecycle(lifecycleOwner, holder, holderOptions)
        }
        lifecycleOwner.lifecycleScope.launch {
            holder.state.collect { state ->
                render(state)
                if (state is NativeAdDisplayState.Success) scheduleReload()
            }
        }
        // A failed refresh keeps the state unchanged; refresh the overlay's error line anyway.
        lifecycleOwner.lifecycleScope.launch {
            holder.lastError.collect { updateDebugOverlay(holder.currentState) }
        }
    }

    fun setContainer(container: FrameLayout) = apply {
        this.container = container
        renderedAd = null
        render(holder.currentState)
    }

    /** Shown while the first ad is loading; hidden once it either loads or fails. */
    fun setShimmerLayoutView(shimmerLayoutView: ShimmerFrameLayout) = apply {
        shimmer = shimmerLayoutView
        render(holder.currentState)
    }

    /** Escape hatch to render the loaded ad yourself instead of the default layout-inflate path. */
    fun setCustomContentView(onCustom: (ApNativeAd) -> Unit) = apply {
        onCustomShowView = onCustom
    }

    /** Picks a different layout per mediation network, falling back to [NativeBannerConfig.layoutId]. */
    fun setLayoutMediation(vararg layoutMediation: NativeLayoutMediation) = apply {
        this.layoutMediation = layoutMediation.toList()
    }

    fun registerAdListener(callback: NativeAdListener) = apply {
        if (listeners.addIfAbsent(callback)) holder.registerAdCallback(callback)
    }

    fun unregisterAdListener(callback: NativeAdListener) = apply {
        listeners.remove(callback)
        holder.unregisterAdCallback(callback)
    }

    fun unregisterAllAdListener() = apply {
        listeners.forEach(holder::unregisterAdCallback)
        listeners.clear()
    }

    /** Buffers [buffer] ads for [tag] (no-op while the buffer is already full or loading). */
    fun preload(buffer: Int = 1): NativeBannerHelper = apply {
        NativeAds.safePreload(spec(buffer))
    }

    /** Like [preload], but always tears down and restarts the buffer even if it's already full. */
    fun forcePreload(buffer: Int = 1): NativeBannerHelper = apply {
        NativeAds.forcePreload(spec(buffer))
    }

    @Deprecated("A request always consumes a buffered ad for this slot's tag first; nothing to enable.")
    fun setEnablePreload(enabled: Boolean): NativeBannerHelper = this

    fun getPreloadState(): StateFlow<PreloadBufferState> = NativeAds.stateFlow(tag)

    /** True if an ad for [tag] is buffered and ready. */
    fun isPreloadAvailable(): Boolean = NativeAds.available(tag) > 0

    /** True if a preload request for [tag] is currently in flight. */
    fun isPreloadInProcess(): Boolean = NativeAds.inFlightRequests(tag) > 0

    /** Number of ads currently buffered for [tag]. */
    fun getPreloadBufferCount(): Int = NativeAds.available(tag)

    fun cancelPreload(): NativeBannerHelper = apply {
        NativeAds.stopPreload(tag)
    }

    fun requestAds() {
        Ads.getInstance().runWhenReady { holder.request() }
    }

    /** Stops loading and destroys the ad on screen; [requestAds] starts over. */
    fun cancel() {
        reloadJob?.cancel()
        reloadJob = null
        holder.cancel()
    }

    fun getAdState(): StateFlow<NativeAdDisplayState> = holder.state

    private fun spec(buffer: Int) = NativeAdSpec(
        tag = tag,
        adUnitIds = config.listId,
        defaultLayoutId = config.layoutId,
        canShowAds = config.canShowAds,
        canReloadAds = config.canReloadAds,
        bufferSize = buffer,
        timeoutsMs = listOf(config.loadTimeout),
        requestOptions = config.requestOptions,
    )

    private fun render(state: NativeAdDisplayState) {
        val hasAd = holder.displayedAd != null
        val showContent = when (state) {
            is NativeAdDisplayState.Success, NativeAdDisplayState.Loading, NativeAdDisplayState.Idle -> true
            // Testers keep the empty container so the overlay can show why it failed.
            is NativeAdDisplayState.Error -> hasAd || isTesterOverlayEnabled()
            is NativeAdDisplayState.Cancelled -> hasAd
        }
        container?.let { setVisible(it, showContent) }
        shimmer?.let { setVisible(it, state is NativeAdDisplayState.Loading && !hasAd) }

        if (state is NativeAdDisplayState.Success && state.ad !== renderedAd) {
            val ad = state.ad
            val custom = onCustomShowView
            val content = container
            if (custom != null) {
                renderedAd = ad
                custom(ad)
            } else if (content != null) {
                renderedAd = ad
                ad.layoutCustomNative = layoutMediation.layoutFor(ad.nativeAd, config.layoutId)
                AdViewRenderer.native(activity, ad, content, shimmer, holder.callbackRelay)
            }
        }
        if (state is NativeAdDisplayState.Cancelled) renderedAd = null
        updateDebugOverlay(state)
    }

    private fun isTesterOverlayEnabled(): Boolean =
        Ads.getInstance().adConfigOrNull?.showMessageForTester == true

    private fun updateDebugOverlay(state: NativeAdDisplayState) {
        if (!isTesterOverlayEnabled()) return
        val content = container ?: return
        val loaded = state as? NativeAdDisplayState.Success
        addDebugOverlayToView(
            container = content,
            context = activity,
            info = AdDebugInfo(
                adType = "NativeBanner",
                state = when (state) {
                    NativeAdDisplayState.Idle -> "Idle"
                    NativeAdDisplayState.Loading -> "Loading"
                    is NativeAdDisplayState.Success -> "Loaded"
                    is NativeAdDisplayState.Error -> "Error"
                    is NativeAdDisplayState.Cancelled -> "Cancelled"
                },
                adUnitId = loaded?.ad?.resolvedAdUnitId() ?: config.listId.lastOrNull(),
                fromPreload = loaded?.fromPreload,
                cacheCount = NativeAds.available(tag),
                requestCount = NativeAds.requestCount(tag),
                inFlightRequests = NativeAds.inFlightRequests(tag),
                tag = tag,
                error = holder.lastError.value,
            ),
        )
    }

    /** Re-arms the timed reload; waits for the shown ad's impression before replacing it. */
    private fun scheduleReload() {
        reloadJob?.cancel()
        if (!config.canShowAds || !config.canReloadAds || config.timeReloadMs <= 0L) return
        reloadJob = lifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(config.timeReloadMs)
                if (holder.isCurrentAdImpressed) {
                    holder.reload()
                    return@launch
                }
            }
        }
    }

    private fun setVisible(view: View, visible: Boolean) {
        view.visibility = when {
            visible -> View.VISIBLE
            adVisibility == AdOptionVisibility.INVISIBLE -> View.INVISIBLE
            else -> View.GONE
        }
    }
}
