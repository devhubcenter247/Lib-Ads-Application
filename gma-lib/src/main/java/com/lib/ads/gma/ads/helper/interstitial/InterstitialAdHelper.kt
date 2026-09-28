package com.lib.ads.gma.ads.helper.interstitial

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.helper.params.IAdsParam
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.manager.FullScreenAdLruCache
import com.lib.ads.gma.ads.manager.InterstitialAdManager
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

// ──────────────────────────────────────────────
// Param
// ──────────────────────────────────────────────

sealed class InterstitialAdParam : IAdsParam {
    data object Request : InterstitialAdParam() {
        fun create(): Request = this
    }

    data class Ready(val interstitialAd: InterstitialAd) : InterstitialAdParam()

    data object Show : InterstitialAdParam() {
        fun create(): Show = this
    }
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

open class InterstitialAdConfig(
    var listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean = true,
    override val placementId: Long? = null,
    var preloadTag: String? = null,
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean, canReloadAds: Boolean = true)
        : this(listOf(idAds), canShowAds, canReloadAds)

    override val idAds: String
        get() = listId.lastOrNull().orEmpty()

    var intervalBetweenAds: Long = 0L
    var autoReloadAfterShow: Boolean = true
    var loadTimeout: Long = 30_000L

    fun setListId(list: List<String>) = apply {
        this.listId = list
    }

    fun setIntervalBetweenAds(intervalSeconds: Long) = apply {
        this.intervalBetweenAds = intervalSeconds
    }

    fun setAutoReloadAfterShow(autoReload: Boolean) = apply {
        this.autoReloadAfterShow = autoReload
    }

    fun setLoadTimeout(timeoutMs: Long) = apply {
        this.loadTimeout = timeoutMs
    }

    fun setPreloadTag(tag: String?) = apply {
        this.preloadTag = tag?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        fun simple(
            adUnitId: String,
            autoReloadAfterShow: Boolean = true,
            intervalBetweenAds: Long = 0L,
            loadTimeoutMs: Long = 30_000L,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ): InterstitialAdConfig =
            InterstitialAdConfig(adUnitId, canShowAds, canReloadAds).apply {
                this.autoReloadAfterShow = autoReloadAfterShow
                this.intervalBetweenAds = intervalBetweenAds
                this.loadTimeout = loadTimeoutMs
            }

        fun waterfall(
            adUnitIds: List<String>,
            autoReloadAfterShow: Boolean = true,
            intervalBetweenAds: Long = 0L,
            loadTimeoutMs: Long = 30_000L,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ): InterstitialAdConfig =
            InterstitialAdConfig(adUnitIds, canShowAds, canReloadAds).apply {
                this.autoReloadAfterShow = autoReloadAfterShow
                this.intervalBetweenAds = intervalBetweenAds
                this.loadTimeout = loadTimeoutMs
            }
    }
}
open class InterstitialAdHelper(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    override val config: InterstitialAdConfig
) : AdsHelper<InterstitialAdConfig, InterstitialAdParam>(context, lifecycleOwner, config) {

    companion object {
        const val AD_TRANSITION_COVER_DELAY = 300L
    }
    private val appContext: Context = context.applicationContext
    private val defaultActivity: Activity? = context as? Activity

    private val scope: CoroutineScope get() = lifecycleOwner.lifecycleScope
    private data class LoadedInterstitial(val adUnitId: String, val ad: InterstitialAd)

    private var interstitialAdLocal: InterstitialAd? = null
    private var loadedAdUnitId: String? = null
    private var lastImpressionTime: Long = 0
    private val listAdCallback: CopyOnWriteArrayList<InterstitialAdListener> = CopyOnWriteArrayList()
    private val loadWaiters = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var loadingJob: Job? = null
    private var loadingDialog: Dialog? = null
    private var pendingShowGate: ForegroundGateHandle? = null
    private var activePreloadTag: String? = null

    init {
        lifecycleEventState.onEach { event ->
            when (event) {
                Lifecycle.Event.ON_DESTROY -> {
                    cancel()
                    dismissLoadingDialog()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (canRequestAds() && canReloadAd() && interstitialAdLocal == null && isActiveState()) {
                        requestAds(InterstitialAdParam.Request)
                    }
                }
                else -> Unit
            }
        }.launchIn(lifecycleOwner.lifecycleScope)

    }

    override fun requestAds(param: InterstitialAdParam) {
        logZ("requestAds: ${param::class.simpleName}")

        if (!canRequestAds()) {
            logZ("Cannot request ads - disabled or no network")
            return
        }

        scope.launch {
            when (param) {
                is InterstitialAdParam.Request -> {
                    flagActive.compareAndSet(false, true)
                    if (!takePreloadedAd()) loadInterstitialAd()
                }
                is InterstitialAdParam.Ready -> {
                    flagActive.compareAndSet(false, true)
                    interstitialAdLocal = param.interstitialAd
                    notifyLoadWaiters(true)
                }
                is InterstitialAdParam.Show -> {
                    val ad = interstitialAdLocal
                    val activity = defaultActivity
                    if (ad != null && activity != null) {
                        showAd(activity = activity, interstitialAd = ad)
                    } else {
                        invokeAdListener { it.onNextAction() }
                    }
                }
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        loadingJob?.cancel()
        loadingJob = null
        interstitialAdLocal = null
        loadedAdUnitId = null
        pendingShowGate?.cancel()
        pendingShowGate = null
        notifyLoadWaiters(false)
    }

    private fun loadInterstitialAd() {
        if (loadingJob?.isActive == true) {
            logZ("Already loading, skipping duplicate request")
            return
        }

        loadingJob = scope.launch {
            try {
                val result = loadFromList(config.listId)

                if (!isActiveState()) {
                    logInterruptExecute("loadInterstitialAd")
                    return@launch
                }

                if (result != null) {
                    interstitialAdLocal = result.ad
                    loadedAdUnitId = result.adUnitId
                    invokeAdListener { it.onLoaded(ApInterstitialAd(result.ad)) }
                    notifyLoadWaiters(true)
                } else {
                    invokeAdListener { it.onFailed(ApAdError("Failed to load ad")) }
                    notifyLoadWaiters(false)
                }
            } catch (e: CancellationException) {
                logZ("Loading canceled: ${e.message}")
                throw e
            } catch (e: Exception) {
                logZ("Loading error: ${e.message}")
                invokeAdListener { it.onFailed(ApAdError(e.message ?: "Failed to load ad")) }
                notifyLoadWaiters(false)
            }
        }
    }

    /**
     * Preloads an interstitial into an application-wide bucket. The request is not tied to this
     * helper's lifecycle, so it can be started on one screen and consumed on another screen.
     *
     * The same [tag] must be supplied to the helper that will show the ad (usually through
     * [InterstitialAdConfig.setPreloadTag]). A cached ad is consumed only once.
     */
    fun preload(tag: String? = config.preloadTag) {
        val normalizedTag = resolvePreloadTag(tag)
        if (normalizedTag == null) {
            logZ("preload: no ad unit ID available to use as fallback tag")
            return
        }
        activePreloadTag = normalizedTag
        if (!canRequestAds()) {
            logZ("preload: cannot request ads")
            return
        }
        if (FullScreenAdLruCache.containsInterstitial(normalizedTag)) {
            logZ("preload: tag=$normalizedTag already has a cached ad")
            return
        }

        InterstitialAdManager.loadInterstitial(
            tag = normalizedTag,
            ids = config.listId,
            listener = object : InterstitialAdListener {
                override fun onLoaded(ad: ApInterstitialAd) {
                    logZ("preload: ad cached with tag=$normalizedTag")
                }

                override fun onFailed(error: ApAdError) {
                    logZ("preload failed for tag=$normalizedTag: ${error.message}")
                }
            },
            placementId = config.placementId,
        )
    }

    /** Alias for callers that prefer an explicit method name. */
    fun preloadAds(tag: String? = config.preloadTag) = preload(tag)

    private fun takePreloadedAd(): Boolean {
        val tag = resolvePreloadTag(activePreloadTag) ?: return false
        val cached = FullScreenAdLruCache.pollInterstitial(tag)?.interstitialAd ?: return false
        interstitialAdLocal = cached
        loadedAdUnitId = null
        notifyLoadWaiters(true)
        invokeAdListener { it.onLoaded(ApInterstitialAd(cached)) }
        logZ("Using preloaded interstitial with tag=$tag")
        return true
    }

    /** Uses the explicit placement tag, or the last configured ad unit ID as a stable fallback. */
    private fun resolvePreloadTag(tag: String? = config.preloadTag): String? =
        (tag ?: config.preloadTag)?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.listId.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .lastOrNull()

    private suspend fun loadSingleAd(adId: String): InterstitialAd? {
        return withTimeoutOrNull(config.loadTimeout.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                InterstitialAd.load(
                    AdsManager.getAdRequest(adId, config.placementId),
                    object : AdLoadCallback<InterstitialAd> {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            logZ("onAdLoaded: $adId")
                            ad.setImmersiveMode(true)

                            if (continuation.isActive) {
                                continuation.resume(ad)
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            logZ("onAdFailedToLoad: ${adError.message}")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )

                continuation.invokeOnCancellation {
                    logZ("Ad loading canceled for: $adId")
                }
            }
        }
    }

    private suspend fun loadFromList(adIds: List<String>): LoadedInterstitial? {
        for ((index, adId) in adIds.withIndex()) {
            logZ("Trying ad ID at index $index: $adId")
            val result = loadSingleAd(adId)
            if (result != null) {
                return LoadedInterstitial(adId, result)
            }
            logZ("Failed at index $index, trying next...")
        }
        return null
    }

    private fun notifyLoadWaiters(success: Boolean) {
        if (loadWaiters.isEmpty()) return
        val waiters = loadWaiters.toList()
        loadWaiters.clear()
        waiters.forEach { it(success) }
    }

    private suspend fun awaitLoadResult(timeoutMs: Long): Boolean {
        if (isAdLoaded()) return true
        return withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val waiter: (Boolean) -> Unit = { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
                loadWaiters += waiter
                continuation.invokeOnCancellation { loadWaiters.remove(waiter) }
            }
        } ?: false
    }

    private suspend fun showAd(
        activity: Activity,
        interstitialAd: InterstitialAd,
        oneShotCallback: InterstitialAdListener? = null,
        waitingDialog: Dialog? = null,
    ) {
        val adUnitId = loadedAdUnitId
        if (activity == null) {
            logZ("showAd: no Activity to show on")
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            logZ("showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        val intervalMs = config.intervalBetweenAds * 1000L
        val timeSinceLastImpression = System.currentTimeMillis() - lastImpressionTime
        if (intervalMs > 0 && timeSinceLastImpression < intervalMs) {
            logZ("showAd: Skipping due to interval restriction")
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        if (waitingDialog == null) showLoadingDialog(activity)

        val appCtx = appContext

        interstitialAd.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
               // scope.launch() {
                    logZ("onAdShowedFullScreenContent")
                    Ads.getInstance().setFullScreenAdShowing(true)
                    lastImpressionTime = System.currentTimeMillis()
                   // delay(AD_TRANSITION_COVER_DELAY.milliseconds)
                    waitingDialog.dismissSafely()
                    dismissLoadingDialog()
               // }
            }

            override fun onAdDismissedFullScreenContent() {
                scope.launch {
                    logZ("onAdDismissedFullScreenContent")
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    interstitialAdLocal = null
                    loadedAdUnitId = null

                    invokeAdListener { it.onNextAction() }
                    invokeAdListener { it.onDismissed(ApInterstitialAd(interstitialAd)) }
                    unregisterOneShot(oneShotCallback)

                    if (config.autoReloadAfterShow && canReloadAd()) {
                        loadInterstitialAd()
                    }
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                scope.launch {
                    logZ("onAdFailedToShowFullScreenContent: ${fullScreenContentError.message}")
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    interstitialAdLocal = null
                    loadedAdUnitId = null
                    invokeAdListener { it.onFailedToShow(ApAdError(fullScreenContentError)) }
                    invokeAdListener { it.onNextAction() }
                    unregisterOneShot(oneShotCallback)
                }
            }

            override fun onAdClicked() {
                scope.launch {
                    logZ("onAdClicked")
                    AdsLogEventManager.logClickAdsEvent(appCtx, adUnitId)
                    invokeAdListener { it.onClicked(ApInterstitialAd(interstitialAd)) }
                }
            }

            override fun onAdImpression() {
                scope.launch {
                    logZ("onAdImpression")
                    AdsLogEventManager.onTrackImpression(appCtx)
                    invokeAdListener { it.onImpression(ApInterstitialAd(interstitialAd)) }
                }
            }

            override fun onAdPaid(value: AdValue) {
                scope.launch {
                    logZ("onAdPaid: ${value.valueMicros}")
                    AdsLogEventManager.logPaidAdImpression(appCtx, value, interstitialAd.getResponseInfo(), AdType.INTERSTITIAL)
                    invokeAdListener { it.onPaid(value) }
                }
            }

            override fun onAppEvent(name: String, data: String?) {
                scope.launch {
                    invokeAdListener { it.onAppEvent(name, data) }
                }
            }
        }

        delay(800.milliseconds)
        if (activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            logZ("showAd: Activity is no longer able to show an interstitial")
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        try {
            invokeAdListener { it.onNextAction() }
            interstitialAd.show(activity)
        } catch (e: Exception) {
            logZ("showAd: failed to show interstitial ad: ${e.message}")
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            interstitialAdLocal = null
            loadedAdUnitId = null
            invokeAdListener { it.onFailedToShow(ApAdError(e.message ?: "Failed to show ad")) }
            unregisterOneShot(oneShotCallback)
        }
    }

    fun forceShow( activity: Activity, interstitialAd: InterstitialAd,callback: InterstitialAdListener? = null,) {
        callback?.let { registerAdListener(it) }

        scope.launch {
            if (!isAdLoaded() && loadingJob?.isActive == true) {
                awaitLoadResult(config.loadTimeout)
            }
            if (isAdLoaded()) {
                showAd(activity = activity, interstitialAd = interstitialAd,callback)
            } else {
                logZ("forceShow: Ad not ready")
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(callback)
            }
        }
    }
    fun waitLoadAndShow(
        activity: Activity,
        enabled: Boolean = true,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        callback: InterstitialAdListener? = null,
    ) {
        callback?.let { registerAdListener(it) }
        scope.launch {
            if (!enabled) {
                invokeAdListener { it.onNextAction() }
                callback?.let { unregisterAdListener(it) }
                return@launch
            }
            if (!isAdLoaded() && !canRequestAds()) {
                invokeAdListener { it.onNextAction() }
                callback?.let { unregisterAdListener(it) }
                return@launch
            }

            // Show the cover before starting the waterfall. The load job is intentionally kept
            // alive after timeout so a late ad is still cached in `interstitialAdLocal`.
            val dialog = showWaitingAdDialog(activity)

            takePreloadedAd()

            if (!isAdLoaded() && loadingJob?.isActive != true) {
                requestAds(InterstitialAdParam.Request)
            }
            if (!isAdLoaded()) {
                awaitLoadResult(timeoutMs)
            }

            if (!isAdLoaded()) {
                dialog.dismissSafely()
                invokeAdListener { it.onNextAction() }
                callback?.let { unregisterAdListener(it) }
                return@launch
            }

            val keepDialogUntilShow = isAppInForeground()
            if (!keepDialogUntilShow) {
                dialog.dismissSafely()
            }

            pendingShowGate = runWhenAppForeground(
                deferToForeground = showWhenReturnFromBackground,
                onForeground = { wasDeferred ->
                    scope.launch {
                        if (wasDeferred) dialog.dismissSafely()
                        showAd(
                            activity = activity,
                            interstitialAd = interstitialAdLocal ?: return@launch,
                            oneShotCallback = callback,
                            waitingDialog = if (keepDialogUntilShow && !wasDeferred) dialog else null,
                        )
                    }
                },
                onDropped = {
                    dialog.dismissSafely()
                    invokeAdListener { it.onNextAction() }
                    unregisterOneShot(callback)
                }
            )
        }
    }

    fun isAdLoaded(): Boolean {
        return interstitialAdLocal != null
    }

    fun getLoadedAd(): InterstitialAd? = interstitialAdLocal

    private fun showLoadingDialog(activity: Activity) {
        try {
            dismissLoadingDialog()
            loadingDialog = PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
        } catch (e: Exception) {
            logZ("Error showing loading dialog: ${e.message}")
        }
    }

    private fun dismissLoadingDialog() {
        try {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
            loadingDialog = null
        } catch (e: Exception) {
            logZ("Error dismissing loading dialog: ${e.message}")
        }
    }

    fun registerAdListener(callback: InterstitialAdListener) {
        listAdCallback.add(callback)
    }

    fun unregisterAdListener(callback: InterstitialAdListener) {
        listAdCallback.remove(callback)
    }

    fun unregisterAllAdListeners() {
        listAdCallback.clear()
    }

    private fun invokeAdListener(action: (InterstitialAdListener) -> Unit) {
        listAdCallback.forEach(action)
    }

    private fun unregisterOneShot(callback: InterstitialAdListener?) {
        callback?.let { unregisterAdListener(it) }
    }
}
