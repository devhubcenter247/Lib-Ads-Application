package com.lib.ads.gma.ads.helper.reward

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.manager.RewardAdManager
import com.lib.ads.gma.ads.manager.FullScreenAdLruCache
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.helper.reward.preload.RewardAdPreload
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApRewardAd
import com.lib.ads.gma.ads.model.wrapper.ApRewardItem
import com.lib.ads.gma.ads.model.wrapper.RewardAdListener
import com.lib.ads.gma.ads.util.AdsDebugLogger
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
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

data class RewardedAdConfig(
    val listId: List<String>,
    val loadTimeout: Long = 30_000L,
    val canShowAds: Boolean = true,
    val canReloadAds: Boolean = true,
    val ssvCustomData: String? = null,
    val autoReloadAfterShow: Boolean = true,
    val intervalBetweenAds: Long = 0L,
    /** AdMob Ad Placements id (see `AdsManager.getAdRequest`) — lets you compare show rate/eCPM per UI placement without a separate ad unit per placement. */
    val placementId: Long? = null,
    /** Shared preload bucket; falls back to the last ad unit ID when blank. */
    val preloadTag: String? = null,
    private val weightedListId: List<WeightedAdUnit>? = null,
) {
    constructor(idAds: String, canShowAds: Boolean = true) : this(listOf(idAds), canShowAds = canShowAds)
    val idAds: String get() = listId.lastOrNull().orEmpty()

    fun weightedAdUnits(): List<WeightedAdUnit> =
        weightedListId ?: RewardAdPreload.toWeightedUnits(listId)

    fun withWeightedAdUnits(adUnits: List<WeightedAdUnit>): RewardedAdConfig {
        val normalized = com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore.dedupeMaxWeight(adUnits)
        return copy(listId = normalized.map { it.adUnitId }, weightedListId = normalized)
    }
}

open class RewardedAdHelper(
    context: Context,
    lifecycleOwner: LifecycleOwner? = null,
    val config: RewardedAdConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = CopyOnWriteArrayList<RewardAdListener>()
    private val appContext: Context = context.applicationContext
    private var loadJob: Job? = null
    private var currentAd: ApRewardAd? = null
    private var lastActivity: Activity? = context as? Activity
    private var pendingShowGate: com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle? = null
    private var lastImpressionTime: Long = 0L
    private var activePreloadTag: String? = null

    init {
        lifecycleOwner?.lifecycle?.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = destroy()
        })
    }

    fun registerAdListener(listener: RewardAdListener) = apply { listeners += listener }
    fun unregisterAdListener(listener: RewardAdListener) = apply { listeners -= listener }
    fun unregisterAllAdListeners() = apply { listeners.clear() }
    fun requestAds() { load(lastActivity) }

    fun preload(tag: String? = config.preloadTag) {
        val resolvedTag = resolvePreloadTag(tag) ?: return
        activePreloadTag = resolvedTag
        if (!canRequestFullScreenAds(appContext, config.canShowAds) ||
            RewardAdPreload.hasReadyReward(config.weightedAdUnits()) ||
            FullScreenAdLruCache.containsReward(resolvedTag)
        ) return
        RewardAdPreload.preloadReward(
            adUnits = config.weightedAdUnits(),
            listener = object : RewardAdListener {
                override fun onFailed(error: ApAdError) = AdsDebugLogger.state("Reward preload failed", error)
            },
            placementId = config.placementId,
            configKey = resolvedTag,
        )
    }

    fun preloadAds(tag: String? = config.preloadTag) = preload(tag)

    fun load(activity: Activity? = lastActivity) {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { load(activity) }; return }
        lastActivity = activity ?: lastActivity
        if (config.listId.isEmpty() || !canRequestFullScreenAds(appContext, config.canShowAds)) { emit { it.onNotReady() }; return }
        if (loadJob?.isActive == true || currentAd?.isReady() == true) return
        if (takePreloadedAd()) return
        loadJob = scope.launch {
            var result: ApRewardAd? = null
            var error: ApAdError? = null
            for (id in config.listId) {
                val loadActivity = lastActivity
                if (loadActivity == null) {
                    error = ApAdError("Rewarded ad requires an Activity")
                    break
                }
                val loaded = withTimeoutOrNull(config.loadTimeout.milliseconds) {
                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine<Pair<ApRewardAd?, ApAdError?>> { continuation ->
                            RewardAdManager.loadReward(id, object : RewardAdListener {
                                override fun onLoaded(ad: ApRewardAd) { if (continuation.isActive) continuation.resume(ad to null) }
                                override fun onFailed(error: ApAdError) { if (continuation.isActive) continuation.resume(null to error) }
                            }, placementId = config.placementId)
                        }
                    }
                } ?: (null to ApAdError("Rewarded load timeout"))
                result = loaded.first
                error = loaded.second
                if (result != null) break
            }
            withContext(Dispatchers.Main.immediate) {
                currentAd = result
                result?.let { ad -> emit { it.onLoaded(ad) } }
                    ?: emit { it.onFailed(error ?: ApAdError("Reward failed")) }
            }
        }
    }

    fun forceShow(activity: Activity, callback: RewardAdListener? = null, waitingDialog: android.app.Dialog? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { forceShow(activity, callback, waitingDialog) }; return }
        lastActivity = activity
        val ad = currentAd
        if (ad == null || !ad.isReady()) { callback?.onNotReady(); return }
        if (activity.isFinishing || activity.isDestroyed) {
            callback?.onFailedToShow(ApAdError("Activity can no longer show a rewarded ad"))
            callback?.onNotReady()
            return
        }
        val intervalMs = config.intervalBetweenAds.coerceAtLeast(0L) * 1000L
        if (intervalMs > 0L && System.currentTimeMillis() - lastImpressionTime < intervalMs) {
            callback?.onFailedToShow(ApAdError("Rewarded ad skipped due to interval restriction"))
            callback?.onNotReady()
            return
        }
        RewardAdManager.showReward(activity, ad, null, object : RewardAdListener {
            override fun onDismissed(ad: ApRewardAd) {
                    if (config.autoReloadAfterShow && config.canReloadAds) load(activity)
                    emit { it.onDismissed(ad) }; callback?.onDismissed(ad)
                    super.onDismissed(ad)
            }
            override fun onFailedToShow(error: ApAdError) { emit { it.onFailedToShow(error) }; callback?.onFailedToShow(error) }
            override fun onShown(ad: ApRewardAd) { emit { it.onShown(ad) }; callback?.onShown(ad) }
            override fun onImpression(ad: ApRewardAd) {
                lastImpressionTime = System.currentTimeMillis()
                emit { it.onImpression(ad) }; callback?.onImpression(ad)
            }
            override fun onClicked(ad: ApRewardAd) { emit { it.onClicked(ad) }; callback?.onClicked(ad) }
            override fun onPaid(adValue: AdValue) { emit { it.onPaid(adValue) }; callback?.onPaid(adValue) }
            override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) { emit { it.onRewarded(ad, item) }; callback?.onRewarded(ad, item) }
            override fun onRewardedAdClosed(ad: ApRewardAd, earnedReward: Boolean) {
                emit { it.onRewardedAdClosed(ad, earnedReward) }; callback?.onRewardedAdClosed(ad, earnedReward)
            }
        }, waitingDialog)
        currentAd = null
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
            AdsProvider.runOnMain { waitLoadAndShow(activity, enabled, timeoutMs, showWhenReturnFromBackground, prepareLoadingMs, callback) }
            return
        }
        if (!enabled || !config.canShowAds) {
            callback?.onNotReady()
            return
        }
        lastActivity = activity
        if (!isAdLoaded() && loadJob?.isActive != true) load(activity)
        scope.launch {
            val dialog = withContext(Dispatchers.Main.immediate) { showWaitingAdDialog(activity) }
            val completed = withTimeoutOrNull(timeoutMs.milliseconds) { loadJob?.join(); true } ?: false
            if (!isAdLoaded()) {
                dialog.dismissSafely()
                if (!completed && loadJob?.isActive == true) {
                    loadJob?.cancel()
                    currentAd = null
                }
                callback?.onNotReady()
                return@launch
            }
            if (prepareLoadingMs > 0) delay(prepareLoadingMs.milliseconds)
            pendingShowGate?.cancel()
            pendingShowGate = runWhenAppForeground(
                deferToForeground = showWhenReturnFromBackground,
                onForeground = {
                    pendingShowGate = null
                    forceShow(activity, object : RewardAdListener {
                        override fun onShown(ad: ApRewardAd) {
                            lastImpressionTime = System.currentTimeMillis()
                            callback?.onShown(ad)
                        }
                        override fun onDismissed(ad: ApRewardAd) { dialog.dismissSafely(); callback?.onDismissed(ad) }
                        override fun onFailedToShow(error: ApAdError) { dialog.dismissSafely(); callback?.onFailedToShow(error) }
                        override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) { callback?.onRewarded(ad, item) }
                        override fun onRewardedAdClosed(ad: ApRewardAd, earnedReward: Boolean) {
                            callback?.onRewardedAdClosed(ad, earnedReward)
                        }
                        override fun onImpression(ad: ApRewardAd) { callback?.onImpression(ad) }
                        override fun onClicked(ad: ApRewardAd) { callback?.onClicked(ad) }
                        override fun onPaid(adValue: AdValue) { callback?.onPaid(adValue) }
                        override fun onNotReady() { dialog.dismissSafely(); callback?.onNotReady() }
                    }, dialog)
                },
                onDropped = {
                    pendingShowGate = null
                    dialog.dismissSafely()
                    callback?.onNotReady()
                },
            )
            if (!isAppInForeground()) {
                dialog.dismissSafely()
            }
        }
    }

    fun isAdLoaded(): Boolean = currentAd?.isReady() == true
    fun getLoadedAd(): ApRewardAd? = currentAd

    private fun resolvePreloadTag(tag: String? = config.preloadTag): String? =
        (tag ?: config.preloadTag)?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.listId.asSequence().map(String::trim).filter(String::isNotEmpty).lastOrNull()

    private fun takePreloadedAd(): Boolean {
        val tag = resolvePreloadTag(activePreloadTag) ?: return false
        val cached = RewardAdPreload.pollReward(config.weightedAdUnits())
            ?: FullScreenAdLruCache.pollReward(tag)
            ?: return false
        if (!cached.isReady()) return false
        currentAd = cached
        emit { it.onLoaded(cached) }
        return true
    }

    private fun emit(action: (RewardAdListener) -> Unit) {
        AdsDebugLogger.state("Reward", action)
        listeners.forEach(action)
    }
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { destroy() }; return }
        pendingShowGate?.cancel(); loadJob?.cancel(); currentAd = null; listeners.clear()
    }
}
