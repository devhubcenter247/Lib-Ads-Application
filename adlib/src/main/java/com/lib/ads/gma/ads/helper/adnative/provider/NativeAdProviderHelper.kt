package com.lib.ads.gma.ads.helper.adnative.provider

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.facebook.shimmer.ShimmerFrameLayout
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.debug.AdDebugInfo
import com.lib.ads.gma.ads.debug.addDebugOverlayToView
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.adnative.AdNativeState
import com.lib.ads.gma.ads.helper.adnative.NativeAdLog
import com.lib.ads.gma.ads.helper.adnative.NativeAdParam
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.callback.NativeAdCallback
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.manager.NativeAdManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * XML lifecycle-aware helper for native ads, driven by a single [NativeAdSpec].
 *
 * Preload is automatic: when [NativeAdSpec.usePreloadBuffer] is true the helper
 * registers the spec with [NativeAds], pops a buffered ad on request (falling back to
 * a cold load); consuming a buffered ad does not trigger another preload.
 */
@OptIn(FlowPreview::class)
class NativeAdProviderHelper(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val spec: NativeAdSpec
) : AdsHelper<NativeAdSpec, NativeAdParam>(activity, lifecycleOwner, spec) {

    companion object {
        const val TAG = "NativeAdProviderHelper"
    }

    private val nativeAdCallback = NativeAdCallback()
    private val adNativeState: MutableStateFlow<AdNativeState> =
        MutableStateFlow(if (canRequestAds()) AdNativeState.None else AdNativeState.Fail())
    private val resumeCount = AtomicInteger(0)
    private var shimmerLayoutView: ShimmerFrameLayout? = null
    private var nativeContentView: FrameLayout? = null
    private val lifecycleNativeCallback = nativeAdCallback.invokeListenerAdCallback()
    private var onCustomShowView: ((ApNativeAd) -> Unit)? = null

    var adVisibility: AdOptionVisibility = AdOptionVisibility.GONE
    var nativeAd: ApNativeAd? = null
        private set

    init {
        if (spec.usePreloadBuffer) {
            NativeAds.register(spec)
            // Tie the preload buffer to this owner's lifecycle: warming stops when the
            // last owner of this tag is destroyed (release happens in the observer below).
            NativeAds.acquire(spec.tag)
            // Kick off preloading so the first request usually hits a warm buffer.
            NativeAds.preload(activity, spec.tag)

            // ON_DESTROY must be observed directly: the lifecycleScope used for the flow
            // below is cancelled at ON_DESTROY, so a flow-based handler would miss it and
            // the background warm job would keep requesting ads after the screen
            // is gone (wasted requests / lower show rate).
            lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        NativeAds.release(spec.tag)
                        source.lifecycle.removeObserver(this)
                    }
                }
            })
        }

        lifecycleEventState.onEach {
            when (it) {
                Lifecycle.Event.ON_START -> NativeAds.registerCallback(spec.tag, lifecycleNativeCallback)
                Lifecycle.Event.ON_STOP -> NativeAds.unregisterCallback(spec.tag, lifecycleNativeCallback)
                Lifecycle.Event.ON_CREATE -> if (!canRequestAds()) {
                    nativeContentView?.checkAdVisibility(false)
                    shimmerLayoutView?.checkAdVisibility(false)
                }
                Lifecycle.Event.ON_RESUME -> if (!canShowAds() && isActiveState()) cancel()
                else -> Unit
            }
        }.launchIn(lifecycleOwner.lifecycleScope)

        lifecycleEventState.debounce(300).onEach { event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeCount.incrementAndGet()
                logZ("Resume repeat ${resumeCount.get()} times")
            }
            if (event == Lifecycle.Event.ON_RESUME && resumeCount.get() > 1 &&
                nativeAd != null && canRequestAds() && canReloadAd() && isActiveState()
            ) {
                requestAds(NativeAdParam.Request.ResumeRequest)
            }
        }.launchIn(lifecycleOwner.lifecycleScope)

        adNativeState
            .onEach { logZ("adNativeState(${it::class.java.simpleName})") }
            .launchIn(lifecycleOwner.lifecycleScope)

        adNativeState.onEach { handleShowAds(it) }.launchIn(lifecycleOwner.lifecycleScope)
    }

    fun setShimmerLayoutView(shimmerLayoutView: ShimmerFrameLayout) = apply {
        runCatching {
            this.shimmerLayoutView = shimmerLayoutView
            if (lifecycleOwner.lifecycle.currentState in Lifecycle.State.CREATED..Lifecycle.State.RESUMED && !canRequestAds()) {
                shimmerLayoutView.checkAdVisibility(false)
            }
        }
    }

    fun setNativeContentView(nativeContentView: FrameLayout) = apply {
        runCatching {
            this.nativeContentView = nativeContentView
            if (lifecycleOwner.lifecycle.currentState in Lifecycle.State.CREATED..Lifecycle.State.RESUMED && !canRequestAds()) {
                nativeContentView.checkAdVisibility(false)
            }
        }
    }

    fun setCustomContentView(onCustom: (ApNativeAd) -> Unit) {
        this.onCustomShowView = onCustom
    }

    fun getAdNativeState(): StateFlow<AdNativeState> = adNativeState.asStateFlow()

    fun getPreloadBufferState() = NativeAds.stateFlow(spec.tag)

    fun getNativeAdSpec(): NativeAdSpec = spec

    fun isPreloadAvailable(): Boolean = NativeAds.isAvailable(spec.tag)

    fun getPreloadAvailableCount(): Int = NativeAds.available(spec.tag)

    private fun handleShowAds(adsParam: AdNativeState) {
        nativeContentView?.checkAdVisibility(adsParam !is AdNativeState.Cancel && canShowAds())
        shimmerLayoutView?.checkAdVisibility(adsParam is AdNativeState.Loading && nativeAd == null)

        if (adsParam !is AdNativeState.Loaded) return

        val custom = onCustomShowView
        when {
            // Compose / custom-rendering path: no XML content view or layout required.
            custom != null -> custom.invoke(adsParam.adNative)

            // Classic XML inflation path: needs a content view + shimmer + a layout id.
            nativeContentView != null && shimmerLayoutView != null -> {
                require(spec.hasLayoutId) {
                    "NativeAdProviderHelper requires a layout ID for XML inflation. " +
                        "Set defaultLayoutId in NativeAdSpec, or use setCustomContentView()."
                }
                NativeAdManager.populateNativeAdView(activity, adsParam.adNative, nativeContentView, shimmerLayoutView)
            }

            // Nothing to render into yet — keep the ad in [nativeAd] for a later view binding.
            else -> {
                NativeAdLog.d("request", spec.tag, "loaded but no content view / custom renderer set yet")
                return
            }
        }

        if (AdsManager.showMessageForTester) {
            nativeContentView?.let { contentView ->
                activity.runOnUiThread {
                    addDebugOverlayToView(
                        contentView,
                        activity,
                        AdDebugInfo(
                            adType = "Native",
                            state = "Loaded",
                            adUnitId = spec.idAds,
                            fromPreload = NativeAds.isAvailable(spec.tag),
                            cacheCount = NativeAds.available(spec.tag),
                            tag = spec.tag
                        )
                    )
                }
            }
        }
    }

    private fun createOrGetAdPreload() {
        if (!canRequestAds()) {
            NativeAdLog.d("request", spec.tag, "createOrGetAdPreload skipped — canRequestAds=false (XML)")
            return
        }
        if (spec.usePreloadBuffer) {
            val preloaded = NativeAds.getDetailed(spec.tag)
            if (preloaded != null) {
                val remaining = NativeAds.available(spec.tag)
                NativeAdLog.d("request", spec.tag, "ready — fill from buffer immediately (no shimmer), queue remaining=$remaining", unit = preloaded.adUnitId)
                logZ("Got ad from preload buffer [${spec.tag}], queue=$remaining")
                // Wire per-ad relay so impression/click are forwarded to listeners.
                preloaded.eventRelay.addListener(lifecycleNativeCallback)
                preloaded.eventRelay.addListener(getDefaultCallback())

                val apNativeAd = ApNativeAd().apply {
                    admobNativeAd = preloaded.nativeAd
                    layoutCustomNative = spec.getLayoutIdByMediationNativeAd(preloaded.nativeAd)
                }
                // Ready ad → fill straight to Loaded, never flash the shimmer/Loading state.
                // The preload buffer is fill-once; consuming an ad does not request a replacement.
                setAndUpdateNativeLoaded(apNativeAd)
                return
            }
        }
        createNativeAds()
    }

    private fun createNativeAds() {
        if (!canRequestAds()) return
        // Cold loading is layout-free: a layout id is only needed at *show* time for the
        // XML populate path (see [handleShowAds]). This lets Compose (custom view) and XML
        // share the exact same load/preload pipeline via the shared NativeAdLoader.
        adNativeState.update { AdNativeState.Loading }
        lifecycleOwner.lifecycleScope.launch {
            val ids = spec.getAllAdUnitIds()
            NativeAdLog.d("request", spec.tag, "buffer empty (queue=${NativeAds.available(spec.tag)}) — cold load (XML helper) units=$ids, layout=${if (spec.hasLayoutId) spec.defaultLayoutId else "none(custom)"}")
            when {
                spec.timeoutsMs.isNotEmpty() && ids.size > 1 -> {
                    NativeAdManager.loadNativeListTimeOut(
                        context = activity,
                        listId = ids,
                        timeOutPerId = spec.timeoutsMs,
                        layoutCustomNative = spec.defaultLayoutId,
                        adCallback = nativeAdCallback.invokeListenerAdCallback(getDefaultCallback())
                    )
                    logZ("createNativeAds List Timeout")
                }
                ids.size > 1 -> {
                    NativeAdManager.loadNativeList(
                        context = activity,
                        listId = ids,
                        layoutCustomNative = spec.defaultLayoutId,
                        adCallback = nativeAdCallback.invokeListenerAdCallback(getDefaultCallback())
                    )
                    logZ("createNativeAds List")
                }
                else -> {
                    NativeAdManager.loadNativeAd(
                        context = activity,
                        id = spec.idAds,
                        layoutCustomNative = spec.defaultLayoutId,
                        callback = nativeAdCallback.invokeListenerAdCallback(getDefaultCallback())
                    )
                    logZ("createNativeAds Single")
                }
            }
        }
    }

    private fun setAndUpdateNativeLoaded(nativeAd: ApNativeAd) {
        nativeAd.layoutCustomNative = spec.getLayoutIdByMediationNativeAd(nativeAd.admobNativeAd)
        this.nativeAd = nativeAd
        // Set synchronously so a ready ad is shown without a Loading/shimmer frame.
        adNativeState.value = AdNativeState.Loaded(nativeAd)
    }

    private fun getDefaultCallback(): AdsCallback = object : AdsCallback() {
        override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
            super.onNativeAdLoaded(nativeAd)
            if (isActiveState()) {
                setAndUpdateNativeLoaded(nativeAd)
                logZ("onNativeAdLoaded")
            } else logInterruptExecute("onNativeAdLoaded")
        }

        override fun onAdFailedToLoad(adError: ApAdError?) {
            super.onAdFailedToLoad(adError)
            if (AdsManager.showMessageForTester) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Load native fail [${spec.tag}]: ${spec.idAds}", Toast.LENGTH_LONG).show()
                }
            }
            Log.v(TAG, "onAdFailedToLoad [${spec.tag}]: ${spec.idAds}")
            if (isActiveState()) {
                if (nativeAd == null) {
                    lifecycleOwner.lifecycleScope.launch { adNativeState.emit(AdNativeState.Fail(adError)) }
                }
                logZ("onAdFailedToLoad")
            } else logInterruptExecute("onAdFailedToLoad")
        }
    }

    override fun requestAds(param: NativeAdParam) {
        lifecycleOwner.lifecycleScope.launch {
            if (canRequestAds()) {
                NativeAdLog.d("request", spec.tag, "requestAds($param) (XML)")
                logZ("requestAds($param)")
                when (param) {
                    is NativeAdParam.Request -> {
                        flagActive.compareAndSet(false, true)
                        when (param) {
                            is NativeAdParam.Request.CreateRequest -> createOrGetAdPreload()
                            is NativeAdParam.Request.ResumeRequest -> createOrGetAdPreload()
                        }
                    }
                    is NativeAdParam.Ready -> {
                        flagActive.compareAndSet(false, true)
                        adNativeState.update { AdNativeState.Loading }
                        setAndUpdateNativeLoaded(param.nativeAd)
                    }
                }
            } else if (!isOnline() && nativeAd == null) {
                cancel()
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        lifecycleOwner.lifecycleScope.launch { adNativeState.emit(AdNativeState.Cancel) }
    }

    fun registerAdListener(adCallback: AdsCallback) = nativeAdCallback.registerAdListener(adCallback)
    fun unregisterAdListener(adCallback: AdsCallback) = nativeAdCallback.unregisterAdListener(adCallback)
    fun unregisterAllAdListener() = nativeAdCallback.unregisterAllAdListener()

    private fun View.checkAdVisibility(isVisible: Boolean) {
        visibility = if (isVisible) View.VISIBLE
        else when (adVisibility) {
            AdOptionVisibility.GONE -> View.GONE
            AdOptionVisibility.INVISIBLE -> View.INVISIBLE
        }
    }
}
