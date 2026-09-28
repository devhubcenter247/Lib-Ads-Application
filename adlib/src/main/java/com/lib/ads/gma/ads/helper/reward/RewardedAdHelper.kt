package com.lib.ads.gma.ads.helper.reward

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApRewardAd
import com.lib.ads.gma.ads.ads.wrapper.ApRewardItem
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.helper.params.IAdsParam
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdType
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

sealed class RewardedAdParam : IAdsParam {
    data object Request : RewardedAdParam()
    data class Ready(val ad: RewardedAd) : RewardedAdParam()
    data object Show : RewardedAdParam()
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

open class RewardedAdConfig(
    override var listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean = true
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean, canReloadAds: Boolean = true)
        : this(listOf(idAds), canShowAds, canReloadAds)

    var loadTimeout: Long = 30_000L
    var autoReloadAfterShow: Boolean = true
    var intervalBetweenAds: Long = 0L
    var ssvCustomData: String? = null

    fun setListId(list: List<String>) = apply { this.listId = list }

    fun setLoadTimeout(timeoutMs: Long) = apply { this.loadTimeout = timeoutMs }

    fun setAutoReloadAfterShow(autoReload: Boolean) = apply { this.autoReloadAfterShow = autoReload }

    fun setIntervalBetweenAds(intervalSeconds: Long) = apply { this.intervalBetweenAds = intervalSeconds }

    companion object {
        fun simple(
            adUnitId: String,
            autoReloadAfterShow: Boolean = true,
            intervalBetweenAds: Long = 0L,
            loadTimeoutMs: Long = 30_000L,
            ssvCustomData: String? = null,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ): RewardedAdConfig =
            RewardedAdConfig(adUnitId, canShowAds, canReloadAds).apply {
                this.autoReloadAfterShow = autoReloadAfterShow
                this.intervalBetweenAds = intervalBetweenAds
                this.loadTimeout = loadTimeoutMs
                this.ssvCustomData = ssvCustomData
            }

        fun waterfall(
            adUnitIds: List<String>,
            autoReloadAfterShow: Boolean = true,
            intervalBetweenAds: Long = 0L,
            loadTimeoutMs: Long = 30_000L,
            ssvCustomData: String? = null,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ): RewardedAdConfig =
            RewardedAdConfig(adUnitIds, canShowAds, canReloadAds).apply {
                this.autoReloadAfterShow = autoReloadAfterShow
                this.intervalBetweenAds = intervalBetweenAds
                this.loadTimeout = loadTimeoutMs
                this.ssvCustomData = ssvCustomData
            }
    }
}

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

open class RewardedAdHelper(
    context: Context,
    private val lifecycleOwner: LifecycleOwner? = null,
    private val config: RewardedAdConfig
) : AdsHelper<RewardedAdConfig, RewardedAdParam>(context, lifecycleOwner, config) {

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

    private var rewardedAd: RewardedAd? = null
    private var lastImpressionTime: Long = 0
    private val listAdCallback: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList()
    private val loadWaiters = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var loadingJob: Job? = null
    private var loadingDialog: Dialog? = null

    // Pending deferred show from waitLoadAndShow (ad ready while the app is backgrounded).
    // Cancelled on teardown so a destroyed helper still completes the flow (onNextAction).
    private var pendingShowGate: ForegroundGateHandle? = null

    @Deprecated("Navigating before a rewarded ad is dismissed can reveal the next screen under the still-visible ad. No longer read; onNextAction() always fires from onAdDismissedFullScreenContent.")
    var openActivityAfterShowInterAds: Boolean
        get() = AdsManager.openActivityAfterShowInterAds
        set(value) {
            AdsManager.openActivityAfterShowInterAds = value
        }

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
                        if (canRequestAds() && canReloadAd() && rewardedAd == null && isActiveState()) {
                            requestAds(RewardedAdParam.Request)
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

    override fun requestAds(param: RewardedAdParam) {
        logZ("requestAds: ${param::class.simpleName}")

        scope.launch {
            when (param) {
                is RewardedAdParam.Request -> {
                    if (!canRequestAds()) {
                        logZ("Cannot request ads - disabled or no network")
                        invokeAdListener { it.onAdFailedToLoad(null) }
                        return@launch
                    }
                    flagActive.compareAndSet(false, true)
                    loadAd()
                }
                is RewardedAdParam.Ready -> {
                    flagActive.compareAndSet(false, true)
                    rewardedAd = param.ad
                    invokeAdListener { it.onAdLoaded() }
                    invokeAdListener { it.onRewardAdLoaded(ApRewardAd(param.ad)) }
                    notifyLoadWaiters(true)
                }
                is RewardedAdParam.Show -> showAd()
            }
        }
    }

    override fun cancel() {
        logZ("cancel() called")
        flagActive.compareAndSet(true, false)
        loadingJob?.cancel()
        loadingJob = null
        rewardedAd = null
        // Abort a pending deferred show so its flow completes (onNextAction) and its
        // ProcessLifecycleOwner observer is removed instead of stranded.
        pendingShowGate?.cancel()
        pendingShowGate = null
        notifyLoadWaiters(false)
    }

    private suspend fun loadAd() {
        if (loadingJob?.isActive == true) {
            logZ("Already loading, skipping duplicate request")
            return
        }

        loadingJob = scope.launch {
            try {
                val result = loadFromList(config.listId)

                if (!isActiveState()) {
                    logInterruptExecute("loadAd")
                    return@launch
                }

                if (result != null) {
                    rewardedAd = result
                    invokeAdListener { it.onAdLoaded() }
                    invokeAdListener { it.onRewardAdLoaded(ApRewardAd(result)) }
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

    private suspend fun loadSingleAd(adId: String): RewardedAd? {
        return withTimeoutOrNull(config.loadTimeout.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val loadContext = defaultActivity ?: appContext

                RewardedAd.load(
                    loadContext,
                    adId,
                    AdRequest.Builder().build(),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            logZ("onAdLoaded: $adId")
                            ad.setImmersiveMode(true)
                            config.ssvCustomData?.let { customData ->
                                ad.setServerSideVerificationOptions(
                                    ServerSideVerificationOptions.Builder().setCustomData(customData).build()
                                )
                            }
                            ad.setOnPaidEventListener { adValue ->
                                logZ("OnPaidEvent: ${adValue.valueMicros}")
                                AdsManager.logPaidEvent(appContext, adValue, ad.adUnitId, ad.responseInfo, AdType.REWARDED)
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

    private suspend fun loadFromList(adIds: List<String>): RewardedAd? {
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
        clearFullScreenFlagOnSkip: Boolean = false,
        waitingDialog: Dialog? = null,
    ) {
        val ad = rewardedAd
        val showActivity = activity ?: defaultActivity
        if (ad == null || showActivity == null) {
            if (showActivity == null) logZ("showAd: no Activity to show on")
            else logZ("showAd: No ad available")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        if (showActivity.isFinishing || showActivity.isDestroyed) {
            logZ("showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        val intervalMs = config.intervalBetweenAds * 1000L
        if (intervalMs > 0 && System.currentTimeMillis() - lastImpressionTime < intervalMs) {
            logZ("showAd: Skipping due to interval restriction")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        if (waitingDialog == null) showLoadingDialog(showActivity)

        val appCtx = appContext
        val adUnitId = ad.adUnitId
        var earnedReward = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                logZ("onAdShowedFullScreenContent")
                AdsManager.setFullScreenAdShowing(true)
                rewardedAd = null
                // Delay dismissing the loading dialog briefly so it keeps covering the screen
                // through the ad window's entrance transition, avoiding a flash of the activity
                // underneath before the ad fully covers it.
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

                invokeAdListener { it.onRewardedAdClosed(earnedReward) }
                invokeAdListener { it.onNextAction() }
                invokeAdListener { it.onAdClosed() }
                unregisterOneShot(oneShotCallback)

                if (config.autoReloadAfterShow && canReloadAd()) {
                    scope.launch { loadAd() }
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                logZ("onAdFailedToShowFullScreenContent: ${adError.message}")
                waitingDialog.dismissSafely()
                AdsManager.setFullScreenAdShowing(false)
                dismissLoadingDialog()
                rewardedAd = null

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
                lastImpressionTime = System.currentTimeMillis()
                invokeAdListener { it.onAdImpression() }
            }
        }

        delay(800)
        if (showActivity.isFinishing || showActivity.isDestroyed || !isAppInForeground()) {
            logZ("showAd: Activity is no longer able to show a rewarded ad")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            dismissLoadingDialog()
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        try {
            ad.show(showActivity) { rewardItem ->
                earnedReward = true
                invokeAdListener { it.onUserEarnedReward(ApRewardItem(rewardItem)) }
            }
        } catch (e: Exception) {
            logZ("showAd: failed to show rewarded ad: ${e.message}")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            dismissLoadingDialog()
            rewardedAd = null
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
     * Load (if needed) and show the rewarded ad, blocking the user with a non-cancelable loading
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
        prepareLoadingMs: Long = 800L,
        callback: AdsCallback? = null,
    ) {
        callback?.let { registerAdListener(it) }
        scope.launch {
            // enabled gates this specific placement, independent of the global
            // AdsManager.canRequestAds switch — false skips straight to onNextAction, same
            // contract as "no ad available".
            if (!enabled) {
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(callback)
                return@launch
            }
            // Nothing can be done if there is no ad and we cannot request one (purchased /
            // consent / offline) — continue the flow without flashing a dialog.
            if (!isAdLoaded() && !canRequestAds()) {
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(callback)
                return@launch
            }

            val dialog = showWaitingAdDialog(activity)

            if (!isAdLoaded() && loadingJob?.isActive != true) {
                requestAds(RewardedAdParam.Request)
            }
            if (!isAdLoaded()) {
                awaitLoadResult(timeoutMs)
            }

            if (!isAdLoaded()) {
                dialog.dismissSafely()
                invokeAdListener { it.onNextAction() }
                unregisterOneShot(callback)
                return@launch
            }

            val keepDialogUntilShow = isAppInForeground()
            if (!keepDialogUntilShow) {
                // The app is backgrounded; do not keep a dialog attached to a stopped Activity
                // while waiting for the next foreground event.
                dialog.dismissSafely()
            }

            pendingShowGate = runWhenAppForeground(
                deferToForeground = showWhenReturnFromBackground,
                onForeground = { wasDeferred ->
                    scope.launch {
                        if (!wasDeferred && keepDialogUntilShow && prepareLoadingMs > 0) {
                            // Keep the "preparing ad" dialog visible briefly before the ad opens,
                            // so an already-loaded ad doesn't pop instantly with no loading shown.
                            try {
                                delay(prepareLoadingMs)
                            } catch (e: CancellationException) {
                                dialog.dismissSafely()
                                throw e
                            }
                        }
                        if (wasDeferred) dialog.dismissSafely()
                        showAd(
                            activity = activity,
                            oneShotCallback = callback,
                            clearFullScreenFlagOnSkip = wasDeferred,
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

    fun isAdLoaded(): Boolean = rewardedAd != null

    fun getLoadedAd(): RewardedAd? = rewardedAd

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

    private fun unregisterOneShot(callback: AdsCallback?) {
        callback?.let { unregisterAdListener(it) }
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
}
