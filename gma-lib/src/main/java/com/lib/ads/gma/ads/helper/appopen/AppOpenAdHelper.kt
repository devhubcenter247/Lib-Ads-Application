package com.lib.ads.gma.ads.helper.appopen

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.appopen.preload.AppOpenAdPreload
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.AppOpenAdListener
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.manager.FullScreenAdLruCache
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class AppOpenAdConfig(
    val tagConfig: String?,
    val listId: List<String>,
    val canShowAds: Boolean = true,
    val loadTimeout: Long = 30_000L,
    val splashMinDelay: Long = 3_000L,
    val showDelay: Long = 800L,
    val maxAdAgeHours: Int = 4,
    /** AdMob Ad Placements id (see `AdsManager.getAdRequest`) — tags requests with a UI-location identifier. */
    val placementId: Long? = null,
) {
    constructor(tagConfig: String?,idAds: String, canShowAds: Boolean = true) : this(
        tagConfig = tagConfig,
        listOf(idAds),
        canShowAds = canShowAds
    )

    val idAds: String get() = listId.lastOrNull().orEmpty()
    fun weightedAdUnits() = AppOpenAdPreload.toWeightedUnits(listId)
}

sealed class AppOpenPreloadState(){
    data object Idle: AppOpenPreloadState()
    data class Loaded(val ad: AppOpenAd): AppOpenPreloadState()
    data object Cancel : AppOpenPreloadState()
    data class Error(val adError: LoadAdError): AppOpenPreloadState()
}

class AppOpenAdHelper(
    val config: AppOpenAdConfig,
) {
    private companion object {
        const val AD_TRANSITION_COVER_DELAY_MS = 250L
        const val MILLIS_PER_HOUR = 3_600_000L
        const val TAG = "AppOpenAdHelper"
    }

    private val _adPreloadState = MutableStateFlow<AppOpenPreloadState>(AppOpenPreloadState.Idle)
    val adPreloadState: StateFlow<AppOpenPreloadState> get() = _adPreloadState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var preloadDeferred: Deferred<AppOpenPreloadState>? = null
    private var pendingShowGate: ForegroundGateHandle? = null
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdLoadTime: Long = 0L
    private val listeners = CopyOnWriteArrayList<AppOpenAdListener>()

    fun registerAdListener(listener: AppOpenAdListener) = apply { listeners += listener }

    fun unregisterAdListener(listener: AppOpenAdListener) = apply { listeners -= listener }

    fun unregisterAllAdListeners() = apply { listeners.clear() }

    /** Callback-named aliases kept for API symmetry with older integrations. */
    fun registerAdCallback(callback: AppOpenAdListener) = registerAdListener(callback)

    fun unRegisterAdCallback(callback: AppOpenAdListener) = unregisterAdListener(callback)

    fun unRegisterAllAdCallbacks() = unregisterAllAdListeners()

    fun destroy() {
        preloadDeferred?.cancel()
        appOpenAd?.destroy()
        pendingShowGate?.cancel()
        job?.cancel()
        listeners.clear()
    }

    fun cancelPreload() {
        preloadDeferred?.cancel()
        preloadDeferred = null
        appOpenAd?.destroy()
        appOpenAd = null
        _adPreloadState.value = AppOpenPreloadState.Idle
    }

    /** Preloads the configured ad unit list. A single-ID config still loads only one ad. */
    fun preloadAd() = preloadAdWaterfall()

    /**
     * Compatibility alias for [preloadAd]. The configured IDs are tried in order, stopping at the
     * first one that loads.
     */
    fun preloadAdWaterfall() = preloadAdToStore()

    fun preloadAdToStore() {
        val preloadTag = resolvePreloadTag() ?: return
        AppOpenAdPreload.preload(
            adUnits = config.weightedAdUnits(),
            placementId = config.placementId,
            configKey = preloadTag,
        )
    }

    private fun startPreload(preload: suspend () -> AppOpenPreloadState) {
        val preloadTag = resolvePreloadTag() ?: return
        if (AppOpenAdPreload.hasReady(config.weightedAdUnits()) ||
            FullScreenAdLruCache.containsAppOpenPreload(preloadTag)
        ) return
        preloadDeferred?.cancel()
        appOpenAd?.destroy()
        appOpenAd = null
        _adPreloadState.value = AppOpenPreloadState.Idle
        preloadDeferred = scope.async {
            val statePreload = preload()
            _adPreloadState.value = statePreload
            appOpenAd = (statePreload as? AppOpenPreloadState.Loaded)?.ad
            appOpenAdLoadTime = System.currentTimeMillis()
            appOpenAd?.let {
                FullScreenAdLruCache.putAppOpenPreload(preloadTag, it)
                // The cache owns the ad now. Do not let destroy() on this helper destroy it.
                appOpenAd = null
            }
            statePreload
        }
    }

    private fun isPreloadedAdFresh(): Boolean {
        val age = System.currentTimeMillis() - appOpenAdLoadTime
        return age < config.maxAdAgeHours.coerceAtLeast(0) * MILLIS_PER_HOUR
    }

    /**
     * Tries [ids] in order under one overall [timeout], stopping at the first id that loads.
     * Returns the last failure state (or [AppOpenPreloadState.Cancel] on timeout) if none load.
     */
    private suspend fun loadWaterfall(
        ids: List<String>,
        timeout: Duration = config.loadTimeout.milliseconds,
    ): AppOpenPreloadState =
        withTimeoutOrNull(timeout) {
            var lastState: AppOpenPreloadState = AppOpenPreloadState.Cancel
            for (id in ids) {
                val state = loadSingleAppOpenAd(id)
                if (state is AppOpenPreloadState.Loaded) return@withTimeoutOrNull state
                lastState = state
            }
            lastState
        } ?: AppOpenPreloadState.Cancel

    fun loadResumeAd() = AppOpenManager.getInstance().let {
        it.setAppResumeAdIdList(config.listId)
        it.setInitialized(true)
    }
    fun waitLoadAndShow(
        activity: Activity,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        callback: AppOpenAdListener? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AdsProvider.runOnMain {
                waitLoadAndShow(
                    activity,
                    timeoutMs,
                    showWhenReturnFromBackground,
                    callback
                )
            }
            return
        }
        dispatch(callback) { it.onNextAction() }
        if (activity.isFinishing || activity.isDestroyed) {
            dispatch(callback) { it.onFailedToShow(ApAdError("Activity can no longer show an app-open ad")) }
            return
        }
        pendingShowGate?.cancel()
        job?.cancel()

        if (!canRequestFullScreenAds(activity, config.canShowAds)) {
            dispatch(callback) { it.onFailed(ApAdError("App-open disabled")) }
            return
        }

        job = scope.launch {
            val preloaded = takePreloadedAd(timeoutMs.coerceAtLeast(1L).milliseconds)
            if (preloaded != null) {
                AppLogger.w(TAG, "waitLoadAndShow: using preloaded ad")
                dispatch(callback) { it.onLoaded() }
                scheduleShow(activity, preloaded, dialog = null, showWhenReturnFromBackground, callback)
                return@launch
            }

            val dialog = withContext(Dispatchers.Main.immediate) {
                showWaitingAdDialog(activity)
            }

            val state = loadWaterfall(config.listId, timeoutMs.coerceAtLeast(1L).milliseconds)
            val ad = (state as? AppOpenPreloadState.Loaded)?.ad

            if (ad == null) {
                withContext(Dispatchers.Main.immediate) {
                    finishLoadFailure(dialog, callback, "App-open load failed or timeout")
                }
                return@launch
            }

            dispatch(callback) { it.onLoaded() }
            scheduleShow(activity, ad, dialog, showWhenReturnFromBackground, callback)
        }
    }

    /**
     * Grabs and clears the preloaded ad, if one is ready and still fresh per
     * [AppOpenAdConfig.maxAdAgeHours]. If a preload (e.g. from [preloadAdWaterfall]) is still in
     * flight, awaits it (bounded by [timeout]) instead of canceling and duplicating the load; if
     * it doesn't finish in time it's left running in the background for next time. A stale ad is
     * discarded (not returned) so callers fall back to a fresh load. Must be called on the main
     * thread.
     */
    private suspend fun takePreloadedAd(timeout: Duration): AppOpenAd? {
        val pending = preloadDeferred
        if (pending != null && pending.isActive) {
            AppLogger.w(TAG, "waitLoadAndShow: awaiting in-flight preload")
            withTimeoutOrNull(timeout) { runCatching { pending.await() } }
            if (pending.isActive) return null
        }
        val ad = appOpenAd
        appOpenAd = null
        preloadDeferred = null
        _adPreloadState.value = AppOpenPreloadState.Idle
        if (ad == null) {
            val tag = resolvePreloadTag() ?: return null
            return AppOpenAdPreload.pollAppOpen(config.weightedAdUnits())
                ?: FullScreenAdLruCache.pollFreshAppOpenPreload(
                tag,
                config.maxAdAgeHours.coerceAtLeast(0).toLong() * MILLIS_PER_HOUR,
            )
        }
        if (!isPreloadedAdFresh()) {
            AppLogger.w(TAG, "takePreloadedAd: discarding stale preloaded ad")
            ad.destroy()
            return null
        }
        return ad
    }

    private fun resolvePreloadTag(): String? =
        config.tagConfig?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.listId.asSequence().map(String::trim).filter(String::isNotEmpty).lastOrNull()

    private fun scheduleShow(
        activity: Activity,
        ad: AppOpenAd,
        dialog: Dialog?,
        showWhenReturnFromBackground: Boolean,
        callback: AppOpenAdListener?,
    ) {
        pendingShowGate = runWhenAppForeground(
            deferToForeground = showWhenReturnFromBackground,
            onForeground = { wasDeferred ->
                pendingShowGate = null
                scope.launch {
                    if (wasDeferred) {
                        dialog.dismissSafely()
                    } else if (config.showDelay > 0) {
                        delay(config.showDelay.milliseconds)
                    }
                    showAppOpenAd(activity, ad, dialog, callback)
                }
            },
            onDropped = {
                pendingShowGate = null
                dialog.dismissSafely()
                failShow(callback, "App-open show canceled")
            },
        )
    }

    private suspend fun loadSingleAppOpenAd(id: String): AppOpenPreloadState =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                AppOpenAd.load(
                    AdsManager.getAdRequest(id, config.placementId),
                    object : AdLoadCallback<AppOpenAd> {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            AppLogger.w(TAG, "onAdLoaded: $id")
                            ad.setImmersiveMode(true)
                            if (continuation.isActive) {
                                continuation.resume(AppOpenPreloadState.Loaded(ad))
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            AppLogger.w(TAG, "onAdFailedToLoad: ${adError.message}")
                            if (continuation.isActive) {
                                continuation.resume(AppOpenPreloadState.Error(adError))
                            }
                        }
                    },
                )
                continuation.invokeOnCancellation {
                    AppLogger.w(TAG, "Ad loading canceled for: $id")
                }
            }
        }

    private fun showAppOpenAd(
        activity: Activity,
        ad: AppOpenAd,
        dialog: Dialog?,
        callback: AppOpenAdListener?,
    ) {
        if (activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            dialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            failShow(callback, "Activity can no longer show an app-open ad")
            return
        }
        val adUnitId = ad.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                scope.launch {
                    AdsProvider.getInstance().setFullScreenAdShowing(true)
                    dispatch(callback) { it.onShown() }
                    delay(AD_TRANSITION_COVER_DELAY_MS.milliseconds)
                    dialog.dismissSafely()
                }
            }

            override fun onAdDismissedFullScreenContent() {
                scope.launch {
                    dialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    dispatch(callback) { it.onDismissed() }
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                scope.launch {
                    dialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    failShow(callback, error.message)
                    Ads.getInstance().setFullScreenAdShowing(false)
                    failShow(callback, fullScreenContentError.message)
                }
            }

            override fun onAdClicked() {
                scope.launch {
                    AdsLogEventManager.logClickAdsEvent(activity, adUnitId)
                    dispatch(callback) { it.onClicked() }
                }
            }

            override fun onAdImpression() {
                scope.launch {
                    AdsLogEventManager.onTrackImpression(activity)
                    dispatch(callback) { it.onImpression() }
                }
            }

            override fun onAdPaid(adValue: AdValue) {
                scope.launch {
                    AdsLogEventManager.logPaidAdImpression(
                        activity,
                        adValue,
                        ad.getResponseInfo(),
                        AdType.APP_OPEN
                    )
                    dispatch(callback) { it.onPaid(adValue) }
                }
            }
        }

        runCatching { ad.show(activity) }
            .onFailure { error ->
                dialog.dismissSafely()
                AdsProvider.getInstance().setFullScreenAdShowing(false)
                failShow(callback, error.message ?: "App-open show failed")
            }
    }

    private fun finishLoadFailure(
        dialog: Dialog?,
        callback: AppOpenAdListener?,
        message: String,
    ) {
        dialog.dismissSafely()
        val error = ApAdError(message)
        dispatch(callback) { it.onFailed(error) }
    }

    private fun failShow(callback: AppOpenAdListener?, message: String?) {
        val error = ApAdError(message ?: "App-open show failed")
        dispatch(callback) { it.onFailedToShow(error) }
    }

    private fun dispatch(callback: AppOpenAdListener?, action: (AppOpenAdListener) -> Unit) {
        callback?.let(action)
        listeners.forEach(action)
    }

    fun waitLoadAndShow(activity: Activity, callback: AppOpenAdListener? = null) =
        waitLoadAndShow(activity, config.loadTimeout, true, callback)
}
