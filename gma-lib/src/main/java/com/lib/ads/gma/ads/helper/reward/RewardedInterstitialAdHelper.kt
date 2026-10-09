package com.lib.ads.gma.ads.helper.reward

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.manager.RewardAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApRewardAd
import com.lib.ads.gma.ads.model.wrapper.ApRewardItem
import com.lib.ads.gma.ads.model.wrapper.RewardAdListener
import com.lib.ads.gma.ads.util.AdsDebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** Lifecycle-aware wrapper for the Next-Gen rewarded interstitial format. */
open class RewardedInterstitialAdHelper(
    context: Context,
    lifecycleOwner: LifecycleOwner? = null,
    val config: RewardedAdConfig,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = CopyOnWriteArrayList<RewardAdListener>()
    private var currentAd: ApRewardAd? = null
    private var loadJob: Job? = null
    private var pendingShowGate: com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle? = null
    private var preloadId: String? = config.preloadTag

    init {
        lifecycleOwner?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = destroy()
        })
    }

    fun registerAdListener(listener: RewardAdListener) = apply { listeners += listener }
    fun unregisterAdListener(listener: RewardAdListener) = apply { listeners -= listener }
    fun unregisterAllAdListeners() = apply { listeners.clear() }
    fun requestAds() = load()

    /** Starts the official Next-Gen preloader. One preloader id uses the first waterfall id. */
    fun preload(tag: String? = config.preloadTag) {
        val resolved = resolvePreloadId(tag) ?: return
        preloadId = resolved
        if (!canRequestFullScreenAds(appContext, config.canShowAds)) return
        RewardAdManager.preloadRewardInterstitialAd(resolved, config.listId.first(), config.placementId)
    }

    fun preloadAds(tag: String? = config.preloadTag) = preload(tag)

    fun load() {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { load() }; return }
        if (loadJob?.isActive == true || currentAd?.isReady() == true) return
        if (config.listId.isEmpty() || !canRequestFullScreenAds(appContext, config.canShowAds)) {
            emit { it.onNotReady() }
            return
        }
        pollPreloaded()?.let {
            currentAd = it
            emit { listener -> listener.onLoaded(it) }
            return
        }
        loadJob = scope.launch {
            var loaded: ApRewardAd? = null
            var error: ApAdError? = null
            for (id in config.listId) {
                val result = withTimeoutOrNull(config.loadTimeout.coerceAtLeast(1L).milliseconds) {
                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine<Pair<ApRewardAd?, ApAdError?>> { continuation ->
                            RewardAdManager.loadRewardInterstitialAd(
                                id,
                                object : RewardAdListener {
                                    override fun onLoaded(ad: ApRewardAd) {
                                        if (continuation.isActive) continuation.resume(ad to null)
                                    }
                                    override fun onFailed(failure: ApAdError) {
                                        if (continuation.isActive) continuation.resume(null to failure)
                                    }
                                },
                                config.placementId,
                                config.ssvCustomData,
                            )
                        }
                    }
                } ?: (null to ApAdError("Rewarded interstitial load timeout"))
                loaded = result.first
                error = result.second
                if (loaded != null) break
            }
            currentAd = loaded
            if (loaded != null) emit { it.onLoaded(loaded!!) }
            else emit { it.onFailed(error ?: ApAdError("Rewarded interstitial load failed")) }
        }
    }

    fun forceShow(activity: Activity, callback: RewardAdListener? = null, waitingDialog: Dialog? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AdsProvider.MAIN.post { forceShow(activity, callback, waitingDialog) }; return
        }
        val ad = currentAd
        if (ad == null || !ad.isRewardInterstitial() || !ad.isReady()) {
            emit { it.onNotReady() }; callback?.onNotReady(); return
        }
        currentAd = null
        RewardAdManager.showRewardedInterstitialAd(activity, ad, forwardingListener(callback), waitingDialog)
    }

    fun waitLoadAndShow(
        activity: Activity,
        enabled: Boolean = true,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        prepareLoadingMs: Long = 0L,
        callback: RewardAdListener? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AdsProvider.MAIN.post { waitLoadAndShow(activity, enabled, timeoutMs, showWhenReturnFromBackground, prepareLoadingMs, callback) }
            return
        }
        if (!enabled || !config.canShowAds) { emit { it.onNotReady() }; callback?.onNotReady(); return }
        if (!isAdLoaded() && loadJob?.isActive != true) load()
        scope.launch {
            val dialog = showWaitingAdDialog(activity)
            val completed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L).milliseconds) { loadJob?.join(); true } ?: false
            if (!isAdLoaded()) {
                dialog.dismissSafely()
                if (!completed) loadJob?.cancel()
                emit { it.onNotReady() }; callback?.onNotReady(); return@launch
            }
            if (prepareLoadingMs > 0) delay(prepareLoadingMs)
            pendingShowGate?.cancel()
            pendingShowGate = runWhenAppForeground(
                deferToForeground = showWhenReturnFromBackground,
                onForeground = {
                    pendingShowGate = null
                    forceShow(activity, callback, dialog)
                },
                onDropped = {
                    pendingShowGate = null; dialog.dismissSafely()
                    emit { it.onNotReady() }; callback?.onNotReady()
                },
            )
            if (!isAppInForeground()) dialog.dismissSafely()
        }
    }

    fun isAdLoaded() = currentAd?.isReady() == true
    fun getLoadedAd() = currentAd

    private fun pollPreloaded(): ApRewardAd? {
        val id = resolvePreloadId(preloadId) ?: return null
        return RewardAdManager.getRewardInterstitialAdPreload(id)?.takeIf { it.isReady() }?.also { ad ->
            config.ssvCustomData?.takeIf { it.isNotEmpty() }?.let { customData ->
                ad.rewardInterstitial?.setServerSideVerificationOptions(ServerSideVerificationOptions("", customData))
            }
        }
    }

    private fun resolvePreloadId(tag: String?) = (tag ?: config.preloadTag)?.trim()?.takeIf { it.isNotEmpty() }
        ?: config.listId.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun forwardingListener(callback: RewardAdListener?) = object : RewardAdListener {
        override fun onShown(ad: ApRewardAd) { emit { it.onShown(ad) }; callback?.onShown(ad) }
        override fun onImpression(ad: ApRewardAd) { emit { it.onImpression(ad) }; callback?.onImpression(ad) }
        override fun onClicked(ad: ApRewardAd) { emit { it.onClicked(ad) }; callback?.onClicked(ad) }
        override fun onPaid(value: AdValue) { emit { it.onPaid(value) }; callback?.onPaid(value) }
        override fun onMetadataChanged() { emit { it.onMetadataChanged() }; callback?.onMetadataChanged() }
        override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) { emit { it.onRewarded(ad, item) }; callback?.onRewarded(ad, item) }
        override fun onDismissed(ad: ApRewardAd) {
            emit { it.onDismissed(ad) }; callback?.onDismissed(ad)
            if (config.autoReloadAfterShow && config.canReloadAds) load()
        }
        override fun onRewardedAdClosed(ad: ApRewardAd, earnedReward: Boolean) {
            emit { it.onRewardedAdClosed(ad, earnedReward) }; callback?.onRewardedAdClosed(ad, earnedReward)
        }
        override fun onFailedToShow(error: ApAdError) { emit { it.onFailedToShow(error) }; callback?.onFailedToShow(error) }
    }

    private fun emit(action: (RewardAdListener) -> Unit) {
        AdsDebugLogger.state("RewardInterstitial", action)
        listeners.forEach(action)
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { destroy() }; return }
        pendingShowGate?.cancel(); loadJob?.cancel(); currentAd = null; listeners.clear()
    }
}
