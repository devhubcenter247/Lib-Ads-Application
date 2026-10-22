package com.lib.ads.gma.ads.helper.interstitial

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.event.AdHealthEvents
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.AdsHelper
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.helper.interstitial.preload.InterstitialAdPreload
import com.lib.ads.gma.ads.helper.interstitial.preload.InterstitialPreloadStrategy
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
    private var weightedListId: List<WeightedAdUnit>? = null

    fun setListId(list: List<String>) = apply {
        this.listId = list
        this.weightedListId = null
    }

    fun setWeightedListId(list: List<WeightedAdUnit>) = apply {
        val normalized = FullScreenAdStore.dedupeMaxWeight(list)
        this.weightedListId = normalized
        this.listId = normalized.map { it.adUnitId }
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

    fun weightedAdUnits(): List<WeightedAdUnit> =
        weightedListId ?: InterstitialAdPreload.toWeightedUnits(listId)

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

        fun weightedWaterfall(
            adUnits: List<WeightedAdUnit>,
            autoReloadAfterShow: Boolean = true,
            intervalBetweenAds: Long = 0L,
            loadTimeoutMs: Long = 30_000L,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ): InterstitialAdConfig =
            InterstitialAdConfig(adUnits.map { it.adUnitId }, canShowAds, canReloadAds).apply {
                setWeightedListId(adUnits)
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

        /**
         * Longest a show call keeps the user behind the loading dialog by default. The load itself
         * keeps going (per-id [InterstitialAdConfig.loadTimeout]) and a late fill stays cached.
         */
        const val DEFAULT_BLOCKING_WAIT_MS = 8_000L
    }

    private fun blockingWaitMs(): Long = minOf(config.loadTimeout, DEFAULT_BLOCKING_WAIT_MS)
    private val appContext: Context = context.applicationContext
    private val defaultActivity: Activity? = context as? Activity

    private val scope: CoroutineScope get() = lifecycleOwner.lifecycleScope
    private data class LoadedInterstitial(val adUnitId: String, val ad: InterstitialAd)

    // Every read drops an ad older than the store TTL: interstitials expire an hour after loading
    // and would only fail to show, while a missing ad makes the next show / resume load a new one.
    private var interstitialAdLocal: InterstitialAd? = null
        get() {
            val loadedAt = localAdLoadedAtMs
            val ad = field
            if (ad != null && ad !== showingAd && loadedAt != null &&
                System.currentTimeMillis() - loadedAt >= FullScreenAdStore.DEFAULT_TTL_MS
            ) {
                field = null
                localAdLoadedAtMs = null
                runCatching { ad.destroy() }
            }
            return field
        }
    /** Load time of an ad this helper owns (loaded here or taken from the store); null for an app-supplied one. */
    private var localAdLoadedAtMs: Long? = null
    /** The ad handed to show(); it must not be offered back to the store. */
    private var showingAd: InterstitialAd? = null
    private var loadedAdUnitId: String? = null
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
                    // The app still holds this ad: never offer it to the store, it could be shown twice.
                    localAdLoadedAtMs = null
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
        // A loaded ad this screen never showed goes back to the shared store instead of being lost.
        val ownedLoadedAt = localAdLoadedAtMs
        val unshown = interstitialAdLocal?.takeIf { it !== showingAd }
        if (unshown != null && ownedLoadedAt != null && !AppPurchase.getInstance().isPurchased()) {
            val weight = config.weightedAdUnits().firstOrNull { it.adUnitId == unshown.adUnitId }?.weight ?: 0f
            InterstitialAdPreload.offer(unshown.adUnitId, weight, ApInterstitialAd(unshown), ownedLoadedAt)
        }
        localAdLoadedAtMs = null
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
                    localAdLoadedAtMs = System.currentTimeMillis()
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
        if (InterstitialAdPreload.hasReadyPreload(config.weightedAdUnits()) ||
            FullScreenAdLruCache.containsInterstitial(normalizedTag)
        ) {
            logZ("preload: tag=$normalizedTag already has a cached ad")
            return
        }

        InterstitialAdPreload.preload(
            adUnits = config.weightedAdUnits(),
            strategy = InterstitialPreloadStrategy.WATERFALL,
            listener = object : InterstitialAdListener {
                override fun onLoaded(ad: ApInterstitialAd) {
                    logZ("preload: ad cached with tag=$normalizedTag")
                }

                override fun onFailed(error: ApAdError) {
                    logZ("preload failed for tag=$normalizedTag: ${error.message}")
                }
            },
            placementId = config.placementId,
            configKey = normalizedTag,
        )
    }

    /** Alias for callers that prefer an explicit method name. */
    fun preloadAds(tag: String? = config.preloadTag) = preload(tag)

    private fun takePreloadedAd(): Boolean {
        val tag = resolvePreloadTag(activePreloadTag) ?: return false
        val preloaded = InterstitialAdPreload.pollInterstitial(config.weightedAdUnits())
        val cachedWrapper = preloaded ?: FullScreenAdLruCache.pollInterstitial(tag)
        val cached = cachedWrapper?.interstitialAd ?: return false
        interstitialAdLocal = cached
        localAdLoadedAtMs = cachedWrapper.loadedAtMs
        loadedAdUnitId = cached.adUnitId
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
                val startedAt = AdHealthEvents.now()
                InterstitialAd.load(
                    AdsManager.getAdRequest(adId, config.placementId),
                    object : AdLoadCallback<InterstitialAd> {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            AdHealthEvents.loadFinished(AdType.INTERSTITIAL, adId, startedAt, null)
                            logZ("onAdLoaded: $adId")
                            ad.setImmersiveMode(true)
                            // A fill after the timeout (or racing it) is never shown: release it.
                            if (continuation.isActive) {
                                continuation.resume(ad) { _, late, _ -> runCatching { late.destroy() } }
                            } else {
                                runCatching { ad.destroy() }
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            AdHealthEvents.loadFinished(AdType.INTERSTITIAL, adId, startedAt, adError)
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
        // Calls the SDK directly: it must not run before MobileAds.initialize (consent-first apps).
        if (!AdsProvider.getInstance().awaitReadySuspend()) {
            logZ("loadFromList: Mobile Ads SDK not initialized")
            return null
        }
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
        if (activity.isFinishing || activity.isDestroyed) {
            logZ("showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        if (InterstitialFrequencyCap.isCoolingDown(config.intervalBetweenAds * 1000L)) {
            logZ("showAd: Skipping due to interval restriction")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }

        if (waitingDialog == null) showLoadingDialog(activity)

        val appCtx = appContext

        interstitialAd.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                InterstitialFrequencyCap.markShown()
                // SDK background thread: dialog fields are main-thread state.
                AdsProvider.runOnMain {
                    logZ("onAdShowedFullScreenContent")
                    AdsProvider.getInstance().setFullScreenAdShowing(true)
                    waitingDialog.dismissSafely()
                    dismissLoadingDialog()
                }
            }

            // App-wide state is reset on main, not in the screen's lifecycleScope: the caller usually
            // finishes the screen in onNextAction (before show()), which cancels that scope, and the
            // full-screen flag then stayed true and blocked app-open resume for the whole session.
            override fun onAdDismissedFullScreenContent() {
                AdsProvider.runOnMain {
                    waitingDialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    interstitialAdLocal = null
                    showingAd = null
                    loadedAdUnitId = null
                }
                scope.launch {
                    logZ("onAdDismissedFullScreenContent")
                    // onNextAction already fired right before show(); firing it again here navigated twice.
                    invokeAdListener { it.onDismissed(ApInterstitialAd(interstitialAd)) }
                    unregisterOneShot(oneShotCallback)

                    if (config.autoReloadAfterShow && canReloadAd()) {
                        loadInterstitialAd()
                    }
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                AdHealthEvents.showFailed(AdType.INTERSTITIAL, adUnitId.orEmpty(), fullScreenContentError.message)
                AdsProvider.runOnMain {
                    waitingDialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    interstitialAdLocal = null
                    showingAd = null
                    loadedAdUnitId = null
                }
                scope.launch {
                    logZ("onAdFailedToShowFullScreenContent: ${fullScreenContentError.message}")
                    // Only reachable after show(), i.e. after onNextAction already fired.
                    invokeAdListener { it.onFailedToShow(ApAdError(fullScreenContentError)) }
                    unregisterOneShot(oneShotCallback)
                }
            }

            // Tracking runs on the SDK thread (rule 1) and must not depend on the screen still being
            // alive: inside lifecycleScope the revenue of an ad shown over a finished screen was lost.
            override fun onAdClicked() {
                logZ("onAdClicked")
                AdsLogEventManager.logClickAdsEvent(appCtx, adUnitId)
                scope.launch {
                    invokeAdListener { it.onClicked(ApInterstitialAd(interstitialAd)) }
                }
            }

            override fun onAdImpression() {
                logZ("onAdImpression")
                AdsLogEventManager.onTrackImpression(appCtx)
                scope.launch {
                    invokeAdListener { it.onImpression(ApInterstitialAd(interstitialAd)) }
                }
            }

            override fun onAdPaid(value: AdValue) {
                logZ("onAdPaid: ${value.valueMicros}")
                AdsLogEventManager.logPaidAdImpression(appCtx, value, interstitialAd, AdType.INTERSTITIAL)
                scope.launch {
                    invokeAdListener { it.onPaid(value) }
                }
            }

            override fun onAppEvent(name: String, data: String?) {
                scope.launch {
                    invokeAdListener { it.onAppEvent(name, data) }
                }
            }
        }

        delay(AdsProvider.getInstance().interstitialShowDelayMs().milliseconds)
        if (activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            // The ad stays in interstitialAdLocal: shown on a later attempt or offered back on destroy.
            logZ("showAd: Activity is no longer able to show an interstitial")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            invokeAdListener { it.onNextAction() }
            unregisterOneShot(oneShotCallback)
            return
        }
        try {
            invokeAdListener { it.onNextAction() }
            showingAd = interstitialAd
            interstitialAd.show(activity)
        } catch (e: Exception) {
            logZ("showAd: failed to show interstitial ad: ${e.message}")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            interstitialAdLocal = null
            showingAd = null
            loadedAdUnitId = null
            invokeAdListener { it.onFailedToShow(ApAdError(e.message ?: "Failed to show ad")) }
            unregisterOneShot(oneShotCallback)
        }
    }

    fun forceShow( activity: Activity, interstitialAd: InterstitialAd,callback: InterstitialAdListener? = null,) {
        callback?.let { registerAdListener(it) }

        scope.launch {
            if (!isAdLoaded() && loadingJob?.isActive == true) {
                awaitLoadResult(blockingWaitMs())
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
        timeoutMs: Long = blockingWaitMs(),
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

    // The caller may show the ad itself, so it is no longer offered to the store on destroy.
    fun getLoadedAd(): InterstitialAd? = interstitialAdLocal.also { localAdLoadedAtMs = null }

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
