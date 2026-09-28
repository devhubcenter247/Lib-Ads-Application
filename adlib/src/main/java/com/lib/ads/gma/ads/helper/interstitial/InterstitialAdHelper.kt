package com.lib.ads.gma.ads.helper.interstitial

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.helper.params.IAdsParam
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    override var listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean = true
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean, canReloadAds: Boolean = true)
        : this(listOf(idAds), canShowAds, canReloadAds)

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

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

open class InterstitialAdHelper(
    context: Context,
    private val lifecycleOwner: LifecycleOwner? = null,
    private val config: InterstitialAdConfig
) : AdsHelper<InterstitialAdConfig, InterstitialAdParam>(context, lifecycleOwner, config) {

    companion object {
        // The ad window needs a brief moment to finish its entrance transition and fully cover
        // the screen; the loading dialog dismissal is delayed by this much to bridge that gap.
        private const val AD_TRANSITION_COVER_DELAY = 300L
    }

    // Some mediation adapters (notably Unity Ads) require an Activity for both load and show.
    // Keep applicationContext only for analytics and as a fallback for AdMob-only integrations.
    private val appContext: Context = context.applicationContext
    private val defaultActivity: Activity? = context as? Activity

    private val ownScope: CoroutineScope? =
        if (lifecycleOwner == null) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) else null
    private val scope: CoroutineScope get() = lifecycleOwner?.lifecycleScope ?: ownScope!!

    private var interstitialAd: InterstitialAd? = null
    private var lastImpressionTime: Long = 0
    private val listAdCallback: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList()
    private val loadWaiters = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var loadingJob: Job? = null
    private var loadingDialog: Dialog? = null

    // Pending deferred show from waitLoadAndShow (ad ready while the app is backgrounded).
    // Cancelled on teardown so a destroyed helper still completes the flow (onNextAction).
    private var pendingShowGate: ForegroundGateHandle? = null

    @Deprecated("Navigating before an interstitial is shown can lose impressions and lets the next screen render over the still-visible ad. No longer read; onNextAction() always fires from onAdDismissedFullScreenContent.")
    var openActivityAfterShowInterAds: Boolean = false
    var disableAdResumeWhenClickAds: Boolean = false

    init {
        // Lifecycle automation only when an owner is supplied; otherwise the caller drives
        // request / show / destroy imperatively.
        if (lifecycleOwner != null) {
            lifecycleEventState.onEach { event ->
                when (event) {
                    Lifecycle.Event.ON_DESTROY -> {
                        cancel()
                        dismissLoadingDialog()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (canRequestAds() && canReloadAd() && interstitialAd == null && isActiveState()) {
                            requestAds(InterstitialAdParam.Request)
                        }
                    }
                    else -> Unit
                }
            }.launchIn(lifecycleOwner.lifecycleScope)
        }

    }

    /**
     * Release a helper created **without** a lifecycle owner: cancels any in-flight load and the
     * private scope, drops the loaded ad and dismisses the loading dialog. No-op teardown
     * otherwise happens automatically via the lifecycle owner.
     */
    fun destroy() {
        cancel()
        dismissLoadingDialog()
        ownScope?.cancel()
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
                    loadInterstitialAd()
                }
                is InterstitialAdParam.Ready -> {
                    flagActive.compareAndSet(false, true)
                    interstitialAd = param.interstitialAd
                    notifyLoadWaiters(true)
                }
                is InterstitialAdParam.Show -> {
                    showAd()
                }
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        loadingJob?.cancel()
        loadingJob = null
        interstitialAd = null
        // Abort a pending deferred show so its flow completes (onNextAction) and its
        // ProcessLifecycleOwner observer is removed instead of stranded.
        pendingShowGate?.cancel()
        pendingShowGate = null
        notifyLoadWaiters(false)
    }

    private suspend fun loadInterstitialAd() {
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
                    interstitialAd = result
                    invokeAdListener { it.onAdLoaded() }
                    invokeAdListener { it.onInterstitialLoad(ApInterstitialAd(result)) }
                    notifyLoadWaiters(true)
                } else {
                    invokeAdListener { it.onAdFailedToLoad(null) }
                    notifyLoadWaiters(false)
                }
            } catch (e: CancellationException) {
                logZ("Loading canceled: ${e.message}")
                throw e
            } catch (e: Exception) {
                logZ("Loading error: ${e.message}")
                invokeAdListener { it.onAdFailedToLoad(null) }
                notifyLoadWaiters(false)
            }
        }
    }

    private suspend fun loadSingleAd(adId: String): InterstitialAd? {
        return withTimeoutOrNull(config.loadTimeout.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val loadContext = defaultActivity ?: appContext

                InterstitialAd.load(
                    loadContext,
                    adId,
                    AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            logZ("onAdLoaded: $adId")
                            ad.setImmersiveMode(true)

                            ad.setOnPaidEventListener { adValue ->
                                logZ("OnPaidEvent: ${adValue.valueMicros}")
                                AdsManager.logPaidEvent(appContext, adValue, ad.adUnitId, ad.responseInfo, AdType.INTERSTITIAL)
                            }

                            if (continuation.isActive) {
                                continuation.resume(ad)
                            }
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            logZ("onAdFailedToLoad: ${error.message}")
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

    private suspend fun loadFromList(adIds: List<String>): InterstitialAd? {
        for ((index, adId) in adIds.withIndex()) {
            logZ("Trying ad ID at index $index: $adId")
            val result = loadSingleAd(adId)
            if (result != null) {
                return result
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
        activity: Activity? = null,
        oneShotCallback: AdsCallback? = null,
        waitingDialog: Dialog? = null,
    ) {
        val ad = interstitialAd
        val showActivity = activity ?: defaultActivity
        if (ad == null || showActivity == null) {
            if (showActivity == null) logZ("showAd: no Activity to show on")
            else logZ("showAd: No ad available")
            waitingDialog.dismissSafely()
            AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        if (showActivity.isFinishing || showActivity.isDestroyed) {
            logZ("showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        val intervalMs = config.intervalBetweenAds * 1000L
        val timeSinceLastImpression = System.currentTimeMillis() - lastImpressionTime
        if (intervalMs > 0 && timeSinceLastImpression < intervalMs) {
            logZ("showAd: Skipping due to interval restriction")
            waitingDialog.dismissSafely()
            AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        if (waitingDialog == null) showLoadingDialog(showActivity)

        val adUnitId = ad.adUnitId
        val appCtx = appContext

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                logZ("onAdShowedFullScreenContent")
                AdsManager.setFullScreenAdShowing(true)
                SharePreferenceUtils.setLastImpressionInterstitialTime(appCtx)
                lastImpressionTime = System.currentTimeMillis()
                scope.launch {
                    delay(AD_TRANSITION_COVER_DELAY.milliseconds)
                    waitingDialog.dismissSafely()
                    dismissLoadingDialog()
                }
            }

            override fun onAdDismissedFullScreenContent() {
                logZ("onAdDismissedFullScreenContent")
                waitingDialog.dismissSafely()
                AdsManager.setFullScreenAdShowing(false)
                dismissLoadingDialog()
                interstitialAd = null

                invokeAdListener { it.onNextAction() }
                invokeAdListener { it.onAdClosed() }
                unregisterOneShot(oneShotCallback)

                if (config.autoReloadAfterShow && canReloadAd()) {
                    scope.launch {
                        loadInterstitialAd()
                    }
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                logZ("onAdFailedToShowFullScreenContent: ${adError.message}")
                waitingDialog.dismissSafely()
                AdsManager.setFullScreenAdShowing(false)
                dismissLoadingDialog()
                interstitialAd = null

                invokeAdListener { it.onAdFailedToShow(ApAdError(adError)) }
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(oneShotCallback)
            }

            override fun onAdClicked() {
                logZ("onAdClicked")
                AdsManager.handleAdClick(appCtx, adUnitId)
                invokeAdListener { it.onAdClicked() }
            }

            override fun onAdImpression() {
                logZ("onAdImpression")
                AdsManager.handleAdImpression()
                invokeAdListener { it.onAdImpression() }
            }
        }

        delay(800)
        if (showActivity.isFinishing || showActivity.isDestroyed || !isAppInForeground()) {
            logZ("showAd: Activity is no longer able to show an interstitial")
            waitingDialog.dismissSafely()
            AdsManager.setFullScreenAdShowing(false)
            dismissLoadingDialog()
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        try {
            ad.show(showActivity)
        } catch (e: Exception) {
            logZ("showAd: failed to show interstitial ad: ${e.message}")
            waitingDialog.dismissSafely()
            AdsManager.setFullScreenAdShowing(false)
            dismissLoadingDialog()
            interstitialAd = null
            invokeAdListener { it.onAdFailedToShow(ApAdError(e.message ?: "Failed to show ad")) }
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
        }
    }

    fun forceShow(callback: AdsCallback? = null, activity: Activity? = null) {
        callback?.let { registerAdListener(it) }

        scope.launch {
            if (!isAdLoaded() && loadingJob?.isActive == true) {
                awaitLoadResult(config.loadTimeout)
            }
            if (isAdLoaded()) {
                showAd(activity, callback)
            } else {
                logZ("forceShow: Ad not ready")
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(callback)
            }
        }
    }

    /**
     * Load (if needed) and show the interstitial, blocking the user with a non-cancelable loading
     * dialog until the ad is ready or [timeoutMs] elapses.
     *
     * If the user backgrounds the app while loading and [showWhenReturnFromBackground] is true,
     * the ad is shown the moment they return to the foreground (never shown — and failed — in the
     * background); otherwise it is skipped. [AdsCallback.onNextAction] is invoked exactly once to
     * continue the flow: after the ad closes, on timeout, or when no ad is available.
     */
    fun waitLoadAndShow(
        activity: Activity,
        enabled: Boolean = true,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        callback: AdsCallback? = null,
    ) {
        callback?.let { registerAdListener(it) }
        scope.launch {
            // enabled gates this specific placement, independent of the global
            // AdsManager.canRequestAds switch — false skips straight to onNextAction, same
            // contract as "no ad available".
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

            val dialog = showWaitingAdDialog(activity)

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
        return interstitialAd != null
    }

    fun getLoadedAd(): InterstitialAd? = interstitialAd

    private fun showLoadingDialog(activity: Activity) {
        try {
            dismissLoadingDialog()
            loadingDialog = PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
            invokeAdListener { it.onInterstitialShow() }
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

    fun registerAdListener(callback: AdsCallback) {
        listAdCallback.add(callback)
    }

    fun unregisterAdListener(callback: AdsCallback) {
        listAdCallback.remove(callback)
    }

    fun unregisterAllAdListeners() {
        listAdCallback.clear()
    }

    private fun invokeAdListener(action: (AdsCallback) -> Unit) {
        listAdCallback.forEach(action)
    }

    private fun unregisterOneShot(callback: AdsCallback?) {
        callback?.let { unregisterAdListener(it) }
    }
}
