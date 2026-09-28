package com.lib.ads.gma.ads.helper.banner

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.contains
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.adlib.R
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.debug.AdDebugInfo
import com.lib.ads.gma.ads.debug.addDebugOverlayToView
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.manager.BannerAdManager
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.params.IAdsParam
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.lib.ads.gma.ads.helper.BannerCollapseGravity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────

sealed class AdBannerState {
    object None : AdBannerState()
    object Loading : AdBannerState()
    object Cancel : AdBannerState()
    data class Loaded(val adBanner: AdManagerAdView) : AdBannerState()
    data class Fail(val error: ApAdError? = null) : AdBannerState()
}

// ──────────────────────────────────────────────
// Param
// ──────────────────────────────────────────────

sealed class BannerAdParam : IAdsParam {
    data class Ready(val bannerAds: AdManagerAdView) : BannerAdParam()

    object Request : BannerAdParam() {
        fun create(): Request = this
    }

    data class Clickable(val minimumTimeKeepAdsDisplay: Long) : BannerAdParam()
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

open class BannerAdConfig(
    override var listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean,
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean, canReloadAds: Boolean)
        : this(listOf(idAds), canShowAds, canReloadAds)

    var collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None
        private set

    var usingInlineBanner: Boolean = false
    var maxHeight: Int = 50
    var timeReloadMs: Long = 0L
        private set

    var preloadTag: String? = null
        private set

    fun setPreloadTag(tag: String?) = apply {
        this.preloadTag = tag?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setUsingInlineBanner(usingInline: Boolean) = apply {
        this.usingInlineBanner = usingInline
    }

    fun setMaxHeight(maxHeightDp: Int) = apply {
        this.maxHeight = maxHeightDp
    }

    fun setTimeReloadMs(timeReloadMs: Long) = apply {
        this.timeReloadMs = timeReloadMs.coerceAtLeast(0L)
    }

    fun setListId(list: List<String>) = apply {
        this.listId = list
    }

    fun asInlineBanner(maxHeightDp: Int = maxHeight) = apply {
        this.usingInlineBanner = true
        this.maxHeight = maxHeightDp
    }

    fun setCollapsibleGravity(gravity: BannerCollapseGravity) = apply {
        this.collapsibleGravity = gravity
    }

    fun setCollapsibleGravity(gravity: String?) = apply {
        this.collapsibleGravity = BannerCollapseGravity.fromGravity(gravity)
    }

    var containerWidthPx: Int = 0
        private set

    fun setContainerWidthPx(widthPx: Int) = apply {
        this.containerWidthPx = widthPx
    }

    companion object {
        /** Standard / adaptive banner from a single ad unit. */
        fun simple(
            adUnitId: String,
            useInline: Boolean = false,
            maxHeightDp: Int = 50,
            collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
            timeReloadMs: Long = 0L,
        ): BannerAdConfig = BannerAdConfig(adUnitId, canShowAds, canReloadAds).apply {
            usingInlineBanner = useInline
            maxHeight = maxHeightDp
            setTimeReloadMs(timeReloadMs)
            setCollapsibleGravity(collapsibleGravity)
        }

        fun simple(
            adUnitId: String,
            useInline: Boolean = false,
            maxHeightDp: Int = 50,
            collapsibleGravity: String?,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
            timeReloadMs: Long = 0L,
        ): BannerAdConfig = simple(
            adUnitId = adUnitId,
            useInline = useInline,
            maxHeightDp = maxHeightDp,
            collapsibleGravity = BannerCollapseGravity.fromGravity(collapsibleGravity),
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
            timeReloadMs = timeReloadMs,
        )

        /** Waterfall of ad units, tried in order. */
        fun waterfall(
            adUnitIds: List<String>,
            useInline: Boolean = false,
            maxHeightDp: Int = 50,
            collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
            timeReloadMs: Long = 0L,
        ): BannerAdConfig = BannerAdConfig(adUnitIds, canShowAds, canReloadAds).apply {
            usingInlineBanner = useInline
            maxHeight = maxHeightDp
            setTimeReloadMs(timeReloadMs)
            setCollapsibleGravity(collapsibleGravity)
        }

        fun waterfall(
            adUnitIds: List<String>,
            useInline: Boolean = false,
            maxHeightDp: Int = 50,
            collapsibleGravity: String?,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
            timeReloadMs: Long = 0L,
        ): BannerAdConfig = waterfall(
            adUnitIds = adUnitIds,
            useInline = useInline,
            maxHeightDp = maxHeightDp,
            collapsibleGravity = BannerCollapseGravity.fromGravity(collapsibleGravity),
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
            timeReloadMs = timeReloadMs,
        )

        /** Collapsible banner anchored to [gravity] ("top" or "bottom"). */
        fun collapsible(
            adUnitId: String,
            collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.Bottom,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
            timeReloadMs: Long = 0L,
        ): BannerAdConfig = simple(
            adUnitId,
            collapsibleGravity = collapsibleGravity,
            timeReloadMs = timeReloadMs,
            canShowAds = canShowAds,
            canReloadAds = canReloadAds
        )
    }
}

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

@OptIn(kotlinx.coroutines.FlowPreview::class)
open class BannerAdHelper(
    private val activity: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val config: BannerAdConfig,
) : AdsHelper<BannerAdConfig, BannerAdParam>(activity, lifecycleOwner, config) {

    protected val adBannerState: MutableStateFlow<AdBannerState> =
        MutableStateFlow(if (canRequestAds()) AdBannerState.None else AdBannerState.Fail())

    protected var timeShowAdImpression: Long = 0
    private val listAdCallback: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList()
    private var shimmerLayoutView: ShimmerFrameLayout? = null
    private var bannerContentView: FrameLayout? = null
    private var isBannerRequestScheduled = false

    var bannerAdView: AdManagerAdView? = null
    var isEnableListBanner: Boolean = false
        private set

    init {
        registerAdListener(getDefaultCallback())
        lifecycleEventState.onEach {
            if (it == Lifecycle.Event.ON_CREATE) {
                if (!canRequestAds()) {
                    bannerContentView?.isVisible = false
                    shimmerLayoutView?.isVisible = false
                }
            }

            if (it == Lifecycle.Event.ON_RESUME) {
                if (!canShowAds() && isActiveState()) {
                    cancel()
                }
                bannerAdView?.resume()
            }

            if (it == Lifecycle.Event.ON_PAUSE) {
                bannerAdView?.pause()
            }

            if (it == Lifecycle.Event.ON_STOP) {
                kotlin.runCatching {
                    val bannerAdView = bannerAdView
                    if (bannerAdView != null) {
                        val parent = bannerAdView.parent as? ViewGroup
                        parent?.removeView(bannerAdView)
                    }
                }
            }
            if (it == Lifecycle.Event.ON_START) {
                val bannerContentView = bannerContentView
                val bannerAdView = bannerAdView
                if (canShowAds() && bannerContentView != null && bannerAdView != null) {
                    showAd(bannerContentView, bannerAdView)
                }
            }
            if (it == Lifecycle.Event.ON_DESTROY) {
                bannerAdView?.destroy()
                bannerAdView = null
            }
        }.launchIn(lifecycleOwner.lifecycleScope)
        //for action resume or init
        adBannerState
            .onEach { logZ("adBannerState(${it::class.java.simpleName})") }
            .launchIn(lifecycleOwner.lifecycleScope)
        adBannerState.onEach { adsParam ->
            handleShowAds(adsParam)
        }.launchIn(lifecycleOwner.lifecycleScope)
    }

    fun getBannerState(): StateFlow<AdBannerState> {
        return adBannerState.asStateFlow()
    }

    fun getBannerAdConfig(): BannerAdConfig {
        return config
    }

    private fun handleShowAds(adsParam: AdBannerState) {
        bannerContentView?.isGone = adsParam is AdBannerState.Cancel || !canShowAds()
        shimmerLayoutView?.isVisible = adsParam is AdBannerState.Loading && bannerAdView == null
        when (adsParam) {
            is AdBannerState.Loaded -> {
                val bannerContentView = bannerContentView
                if (bannerContentView != null) {
                    showAd(bannerContentView, adsParam.adBanner)
                }
            }

            else -> Unit
        }
    }

    private fun showAd(bannerContentView: FrameLayout, adView: AdManagerAdView) {
        if (bannerContentView.contains(adView)) {
            logZ("bannerContentView has contains adView")
            return
        }
        bannerContentView.setBackgroundColor(Color.WHITE)
        val divider = View(bannerContentView.context)
        val view = View(bannerContentView.context)
        divider.setBackgroundColor(0xFFE1E1E1.toInt())
        val oldHeight = adView.height
        bannerContentView.let {
            removeBannerCollapseIfNeed(it)
            val heightDivider =
                it.context.resources.getDimensionPixelOffset(R.dimen._1sdp)
            it.removeAllViews()
            it.addView(view, 0, oldHeight)
            val adViewParent = adView.parent as? ViewGroup
            adViewParent?.removeView(adView)
            it.addView(
                adView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adView.updateLayoutParams<FrameLayout.LayoutParams> {
                this.setMargins(0, heightDivider, 0, 0)
                this.gravity = Gravity.CENTER or Gravity.BOTTOM
            }
            it.addView(
                divider,
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDivider
            )

            if (AdsManager.showMessageForTester) {
                addDebugOverlayToView(
                    it,
                    it.context,
                    AdDebugInfo(
                        adType = "Banner",
                        state = "Loaded",
                        adUnitId = config.idAds
                    )
                )
            }
        }
    }

    override fun requestAds(param: BannerAdParam) {
        logZ("requestAds with param:${param::class.java.simpleName}")
        if (canRequestAds()) {
            lifecycleOwner.lifecycleScope.launch {
                when (param) {
                    is BannerAdParam.Request -> {
                        if (adBannerState.value == AdBannerState.Loading || isBannerRequestScheduled) {
                            logZ("requestAds ignored: banner request already in flight")
                            return@launch
                        }
                        flagActive.compareAndSet(false, true)
                        loadBannerAd()
                    }

                    is BannerAdParam.Ready -> {
                        flagActive.compareAndSet(false, true)
                        bannerAdView = param.bannerAds
                        adBannerState.emit(AdBannerState.Loaded(param.bannerAds))
                    }

                    is BannerAdParam.Clickable -> {
                        if (isActiveState() && canRequestAds() && canReloadAd() && adBannerState.value != AdBannerState.Loading) {
                            if (timeShowAdImpression + param.minimumTimeKeepAdsDisplay < System.currentTimeMillis()) {
                                loadBannerAd()
                            }
                        } else {
                            logInterruptExecute("requestAds Clickable")
                        }
                    }
                }
            }
        } else {
            if (!isOnline() && bannerAdView == null) {
                cancel()
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        bannerAdView?.destroy()
        bannerAdView = null
        isBannerRequestScheduled = false
        lifecycleOwner.lifecycleScope.launch { adBannerState.emit(AdBannerState.Cancel) }
    }

    protected open fun loadBannerAd() {
        if (canRequestAds()) {
            if (adBannerState.value == AdBannerState.Loading || isBannerRequestScheduled) {
                logZ("loadBannerAd ignored: banner request already loading/scheduled")
                return
            }
            val view = bannerContentView
            if (view != null && view.width == 0) {
                isBannerRequestScheduled = true
                view.doOnLayout {
                    isBannerRequestScheduled = false
                    if (isActiveState()) loadBannerAd()
                }
                return
            }
            isBannerRequestScheduled = false

            val preloadedBanner = config.preloadTag?.let(BannerAds::get)
            if (preloadedBanner != null) {
                logZ("loadBannerAd: using preloaded banner tag=${config.preloadTag}")
                // The preload-time listener only forwarded the load result to the preload
                // callback — rebind it now so click/impression/paid-event tracking and this
                // helper's own AdsCallback listeners actually fire for the rest of the ad's life.
                BannerAdManager.rebindListener(preloadedBanner, activity, invokeListenerAdCallback())
                bannerAdView = preloadedBanner
                adBannerState.update { AdBannerState.Loaded(preloadedBanner) }
                invokeAdListener { it.onBannerLoaded(preloadedBanner) }
                return
            }

            val containerWidthPx = view?.width?.takeIf { it > 0 } ?: config.containerWidthPx
            adBannerState.update { AdBannerState.Loading }
            if (isEnableListBanner) {
                logZ("loadBannerAd List")
                BannerAdManager.loadBannerList(
                    context = activity,
                    listId = config.listId,
                    collapsibleGravity = config.collapsibleGravity.gravity,
                    useInlineAdaptive = config.usingInlineBanner,
                    maxHeight = config.maxHeight,
                    containerWidthPx = containerWidthPx,
                    adCallback = invokeListenerAdCallback()
                )
            } else {
                logZ("loadBannerAd")
                BannerAdManager.requestLoadBanner(
                    context = activity,
                    id = config.idAds,
                    collapsibleGravity = getBannerAdConfig().collapsibleGravity.gravity,
                    useInlineAdaptive = config.usingInlineBanner,
                    maxHeight = config.maxHeight,
                    containerWidthPx = containerWidthPx,
                    callback = invokeListenerAdCallback(),
                )
            }
        }
    }

    fun setShimmerLayoutView(shimmerLayoutView: ShimmerFrameLayout) = apply {
        kotlin.runCatching {
            this.shimmerLayoutView = shimmerLayoutView
            if (lifecycleOwner.lifecycle.currentState in Lifecycle.State.CREATED..Lifecycle.State.RESUMED) {
                if (!canRequestAds()) {
                    shimmerLayoutView.isVisible = false
                }
            }
        }
    }

    fun setEnableListBanner(isEnable: Boolean) = apply {
        this.isEnableListBanner = isEnable
    }

    fun setBannerContentView(nativeContentView: FrameLayout) = apply {
        kotlin.runCatching {
            this.bannerContentView = nativeContentView
            this.shimmerLayoutView =
                nativeContentView.findViewById(R.id.shimmer_container_banner)
            if (lifecycleOwner.lifecycle.currentState in Lifecycle.State.CREATED..Lifecycle.State.RESUMED) {
                if (!canRequestAds()) {
                    nativeContentView.isVisible = false
                    shimmerLayoutView?.isVisible = false
                }
                val bannerAd = bannerAdView
                if (canShowAds() && bannerAd != null) {
                    showAd(nativeContentView, bannerAd)
                }
            }
        }
    }

    protected open fun getDefaultCallback(): AdsCallback {
        return object : AdsCallback() {
            override fun onAdImpression() {
                super.onAdImpression()
                timeShowAdImpression = System.currentTimeMillis()
                logZ("timeShowAdImpression:$timeShowAdImpression")
            }

            override fun onBannerLoaded(adView: AdManagerAdView?) {
                super.onBannerLoaded(adView)
                if (isActiveState()) {
                    lifecycleOwner.lifecycleScope.launch {
                        bannerAdView?.takeIf { it !== adView }?.let { oldAdView ->
                            (oldAdView.parent as? ViewGroup)?.removeView(oldAdView)
                            oldAdView.destroy()
                        }
                        bannerAdView = adView
                        if (adView != null) {
                            adBannerState.emit(AdBannerState.Loaded(adView))
                        }
                    }
                    logZ("onBannerLoaded()")
                } else {
                    adView?.destroy()
                    logInterruptExecute("onBannerLoaded")
                }
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                if (isActiveState()) {
                    lifecycleOwner.lifecycleScope.launch {
                        adBannerState.emit(AdBannerState.Fail(adError))
                    }
                    logZ("onAdFailedToLoad()")
                } else {
                    logInterruptExecute("onAdFailedToLoad")
                }
            }
        }
    }

    fun registerAdListener(adCallback: AdsCallback) {
        this.listAdCallback.add(adCallback)
    }

    fun unregisterAdListener(adCallback: AdsCallback) {
        this.listAdCallback.remove(adCallback)
    }

    fun unregisterAllAdListener() {
        this.listAdCallback.clear()
    }

    protected fun invokeListenerAdCallback(): AdsCallback {
        return object : AdsCallback() {
            override fun onNextAction() {
                super.onNextAction()
                invokeAdListener { it.onNextAction() }
            }

            override fun onAdClosed() {
                super.onAdClosed()
                invokeAdListener { it.onAdClosed() }
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                invokeAdListener { it.onAdFailedToLoad(adError) }
            }

            override fun onAdFailedToShow(adError: ApAdError?) {
                super.onAdFailedToShow(adError)
                invokeAdListener { it.onAdFailedToShow(adError) }
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                invokeAdListener { it.onAdLoaded() }
            }

            override fun onAdSplashReady() {
                super.onAdSplashReady()
                invokeAdListener { it.onAdSplashReady() }
            }

            override fun onAdOpened() {
                super.onAdOpened()
                invokeAdListener { it.onAdOpened() }
            }

            override fun onAdClicked() {
                super.onAdClicked()
                invokeAdListener { it.onAdClicked() }
            }

            override fun onAdImpression() {
                super.onAdImpression()
                invokeAdListener { it.onAdImpression() }
            }

            override fun onInterstitialShow() {
                super.onInterstitialShow()
                invokeAdListener { it.onInterstitialShow() }
            }

            override fun onBannerLoaded(adView: AdManagerAdView?) {
                super.onBannerLoaded(adView)
                invokeAdListener { it.onBannerLoaded(adView) }
            }
        }
    }

    private fun invokeAdListener(action: (adCallback: AdsCallback) -> Unit) {
        listAdCallback.forEach(action)
    }

    private fun removeBannerCollapseIfNeed(layout: ViewGroup) {
        if (!config.collapsibleGravity.gravity.isEmpty()) {
            for (i in 0 until layout.childCount) {
                val view: View = layout.getChildAt(i)
                if (view is AdView) {
                    view.destroy()
                    view.visibility = View.GONE
                    layout.removeView(view)
                    return
                }
            }
        }
    }
}
