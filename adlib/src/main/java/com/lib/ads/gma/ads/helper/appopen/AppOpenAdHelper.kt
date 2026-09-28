package com.lib.ads.gma.ads.helper.appopen

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.dialog.ResumeLoadingDialog
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.fullscreen.ForegroundGateHandle
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.fullscreen.runWhenAppForeground
import com.lib.ads.gma.ads.helper.fullscreen.showWaitingAdDialog
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdType
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

open class AppOpenAdConfig(
    override var listId: List<String>,
    override val canShowAds: Boolean
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean) : this(listOf(idAds), canShowAds)

    override val canReloadAds: Boolean = true

    var maxAdAgeHours: Int = 4
    var loadTimeout: Long = 30_000L
    var splashMinDelay: Long = 3_000L
    var showDelay: Long = 800L

    fun setListId(list: List<String>) = apply {
        this.listId = list
    }

    fun setMaxAdAgeHours(hours: Int) = apply {
        this.maxAdAgeHours = hours
    }

    fun setLoadTimeout(timeoutMs: Long) = apply {
        this.loadTimeout = timeoutMs
    }

    fun setSplashMinDelay(delayMs: Long) = apply {
        this.splashMinDelay = delayMs
    }

    companion object {
        fun create(adId: String, canShow: Boolean = true): AppOpenAdConfig {
            return AppOpenAdConfig(adId, canShow)
        }
    }
}

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

class AppOpenAdHelper(
    private val application: Application,
    private val config: AppOpenAdConfig
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppOpenAdHelper"
        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val AD_TRANSITION_COVER_DELAY = 300L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentActivityRef: WeakReference<Activity>? = null

    private var resumeAd: AppOpenAd? = null
    private var resumeAdLoadTime: Long = 0

    private var splashAd: AppOpenAd? = null

    private val isInitialized = AtomicBoolean(false)
    private val isShowingAd = AtomicBoolean(false)
    private val isLoadingResume = AtomicBoolean(false)
    private val isAppResumeEnabled = AtomicBoolean(true)
    private val isInterstitialShowing = AtomicBoolean(false)
    private val disableAdResumeByClickAction = AtomicBoolean(false)

    private val disabledActivities = mutableListOf<Class<*>>()

    private var resumeDialog: Dialog? = null
    private var splashDialog: Dialog? = null

    private var loadingJob: Job? = null

    // Pending deferred splash show from waitLoadAndShow (ad ready while backgrounded).
    private var pendingShowGate: ForegroundGateHandle? = null

    @Deprecated("Navigating before an app-open ad is dismissed can reveal the next screen under the still-visible ad. No longer read; onNextAction() always fires from onAdDismissedFullScreenContent.")
    var openActivityAfterShowInterAds: Boolean
        get() = AdsManager.openActivityAfterShowInterAds
        set(value) {
            AdsManager.openActivityAfterShowInterAds = value
        }

    var fullScreenContentCallback: FullScreenContentCallback? = null
    var enableScreenContentCallback: Boolean = false

    fun initialize() {
        if (isInitialized.getAndSet(true)) {
            log("Already initialized")
            return
        }

        log("Initializing AppOpenAdHelper")
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun destroy() {
        log("Destroying AppOpenAdHelper")
        application.unregisterActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        // Abort a pending deferred show so it completes (onNextAction) and removes its observer.
        pendingShowGate?.cancel()
        pendingShowGate = null
        scope.cancel()
        loadingJob?.cancel()
        resumeAd = null
        splashAd = null
        isInitialized.set(false)
    }

    // region Resume Ad Management

    fun loadResumeAd() {
        // listId is always a waterfall (a single id is a one-element list).
        loadResumeAdFromList()
    }

    private fun loadResumeAdFromList() {
        if (isResumeAdAvailable()) return
        if (isShowingAd.get()) return
        if (isLoadingResume.getAndSet(true)) return

        scope.launch {
            try {
                for ((index, adId) in config.listId.withIndex()) {
                    log("Trying resume ad ID at index $index: $adId")
                    val ad = loadSingleAppOpenAd(adId)
                    if (ad != null) {
                        resumeAd = ad
                        resumeAdLoadTime = System.currentTimeMillis()
                        log("Resume ad loaded at index $index")
                        return@launch
                    }
                }
            } finally {
                isLoadingResume.set(false)
            }
        }
    }

    fun showResumeAdIfAvailable() {
        val currentActivity = getCurrentActivity()

        if (currentActivity == null || AppPurchase.getInstance().isPurchased(currentActivity)) {
            fullScreenContentCallback?.takeIf { enableScreenContentCallback }
                ?.onAdDismissedFullScreenContent()
            return
        }

        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            fullScreenContentCallback?.takeIf { enableScreenContentCallback }
                ?.onAdDismissedFullScreenContent()
            return
        }

        if (!isShowingAd.get() && isResumeAdAvailable()) {
            showResumeAd()
        }
    }

    private fun showResumeAd() {
        val currentActivity = getCurrentActivity() ?: return
        val ad = resumeAd ?: return

        if (AppPurchase.getInstance().isPurchased(currentActivity)) return

        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        scope.launch {
            try {
                hideResumeDialog()
                showResumeDialog()

                val adUnitId = ad.adUnitId

                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        resumeAd = null
                        isShowingAd.set(false)
                        hideResumeDialog()

                        fullScreenContentCallback?.takeIf { enableScreenContentCallback }
                            ?.onAdDismissedFullScreenContent()

                        loadResumeAd()
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        log("Resume ad failed to show: ${error.message}")
                        resumeAd = null
                        isShowingAd.set(false)
                        hideResumeDialog()
                        loadResumeAd()

                        fullScreenContentCallback?.takeIf { enableScreenContentCallback }
                            ?.onAdFailedToShowFullScreenContent(error)
                    }

                    override fun onAdShowedFullScreenContent() {
                        isShowingAd.set(true)
                        resumeAd = null
                        log("Resume ad showed")

                        fullScreenContentCallback?.takeIf { enableScreenContentCallback }
                            ?.onAdShowedFullScreenContent()
                    }

                    override fun onAdClicked() {
                        AdsManager.handleAdClick(currentActivity, adUnitId)
                        fullScreenContentCallback?.onAdClicked()
                    }

                    override fun onAdImpression() {
                        AdsManager.handleAdImpression()
                        fullScreenContentCallback?.onAdImpression()
                    }
                }

                ad.show(currentActivity)

            } catch (e: Exception) {
                log("Error showing resume ad: ${e.message}")
                hideResumeDialog()
            }
        }
    }

    fun isResumeAdAvailable(): Boolean {
        val ad = resumeAd ?: return false
        val age = System.currentTimeMillis() - resumeAdLoadTime
        val maxAge = config.maxAdAgeHours * MILLIS_PER_HOUR
        return age < maxAge
    }

    // endregion

    // region Splash Ad Management

    fun loadSplashAd(
        activity: Activity,
        onAdReady: () -> Unit,
        onNextAction: () -> Unit,
        onFailed: ((String?) -> Unit)? = null
    ) {
        if (AppPurchase.getInstance().isPurchased(activity)) {
            onNextAction()
            return
        }

        val isTimeout = AtomicBoolean(false)
        val isDelayComplete = AtomicBoolean(false)

        loadingJob = scope.launch {
            try {
                val delayJob = launch {
                    delay(config.splashMinDelay)
                    isDelayComplete.set(true)
                    log("Splash minimum delay complete")

                    splashAd?.let {
                        onAdReady()
                    }
                }

                val adIds = config.listId
                var loadedAd: AppOpenAd? = null

                try {
                    withTimeout(config.loadTimeout) {
                        for ((index, adId) in adIds.withIndex()) {
                            if (isTimeout.get()) break
                            log("Trying splash ad ID at index $index: $adId")

                            val ad = loadSingleAppOpenAd(adId)
                            if (ad != null) {
                                loadedAd = ad
                                break
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    log("Splash loading timed out")
                    isTimeout.set(true)
                    onNextAction()
                    return@launch
                }

                if (loadedAd == null) {
                    onFailed?.invoke("Failed to load")
                    onNextAction()
                    return@launch
                }

                splashAd = loadedAd

                if (!isDelayComplete.get()) {
                    delayJob.join()
                }

                onAdReady()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Splash loading error: ${e.message}")
                onFailed?.invoke(e.message)
                onNextAction()
            }
        }
    }

    fun showSplashAd(
        activity: Activity,
        onNextAction: () -> Unit,
        onClosed: (() -> Unit)? = null,
        onFailedToShow: ((ApAdError?) -> Unit)? = null,
        clearFullScreenFlagOnSkip: Boolean = false,
        waitingDialog: Dialog? = null,
    ) {
        val ad = splashAd
        if (ad == null) {
            log("Splash ad not available")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            onNextAction()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            log("Splash activity can no longer show an ad")
            waitingDialog.dismissSafely()
            if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
            onNextAction()
            return
        }

        scope.launch {
            if (waitingDialog == null) showSplashDialog(activity)

            delay(config.showDelay)

            val adUnitId = ad.adUnitId

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    splashAd = null
                    isShowingAd.set(false)
                    waitingDialog.dismissSafely()
                    AdsManager.setFullScreenAdShowing(false)
                    hideSplashDialog()

                    onNextAction()
                    onClosed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    log("Splash ad failed to show: ${error.message}")
                    splashAd = null
                    isShowingAd.set(false)
                    waitingDialog.dismissSafely()
                    AdsManager.setFullScreenAdShowing(false)
                    hideSplashDialog()

                    onFailedToShow?.invoke(ApAdError(error))
                    onNextAction()
                }

                override fun onAdShowedFullScreenContent() {
                    isShowingAd.set(true)
                    AdsManager.setFullScreenAdShowing(true)
                    log("Splash ad showed")
                    // Match interstitial waitLoadAndShow: keep the loading dialog covering the
                    // activity briefly while the app-open ad window finishes its entrance.
                    scope.launch {
                        delay(AD_TRANSITION_COVER_DELAY)
                        waitingDialog.dismissSafely()
                        hideSplashDialog()
                    }
                }

                override fun onAdClicked() {
                    AdsManager.handleAdClick(activity, adUnitId)
                }

                override fun onAdImpression() {
                    AdsManager.handleAdImpression()
                }
            }

            try {
                ad.show(activity)
            } catch (e: Exception) {
                log("Splash ad failed to show: ${e.message}")
                splashAd = null
                isShowingAd.set(false)
                waitingDialog.dismissSafely()
                if (clearFullScreenFlagOnSkip) AdsManager.setFullScreenAdShowing(false)
                hideSplashDialog()
                onFailedToShow?.invoke(ApAdError(e.message ?: "Failed to show"))
                onNextAction()
            }
        }
    }

    /**
     * Load (if needed) and show the splash app-open ad, blocking the user with a non-cancelable
     * loading dialog until the ad is ready or [timeoutMs] elapses.
     *
     * If the user backgrounds the app while loading and [showWhenReturnFromBackground] is true,
     * the ad is shown the moment they return to the foreground (never shown — and failed — in the
     * background); otherwise it is skipped. [onNextAction] is invoked exactly once to continue the
     * flow: after the ad closes, on timeout, or when no ad is available.
     */
    fun waitLoadAndShow(
        activity: Activity,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        onNextAction: () -> Unit,
        onClosed: (() -> Unit)? = null,
        onFailedToShow: ((ApAdError?) -> Unit)? = null,
        onFailedToLoad: ((ApAdError?) -> Unit)? = null,
    ) {
        if (AppPurchase.getInstance().isPurchased(activity)) {
            onNextAction()
            return
        }
        scope.launch {
            val dialog = showWaitingAdDialog(activity)

            if (splashAd == null) {
                val adIds = config.listId
                val loaded = withTimeoutOrNull(timeoutMs) {
                    var result: AppOpenAd? = null
                    for (adId in adIds) {
                        result = loadSingleAppOpenAd(adId)
                        if (result != null) break
                    }
                    result
                }
                if (loaded != null) {
                    splashAd = loaded
                } else {
                    // App-open loads directly (no registered listener), so surface the load
                    // failure to the caller explicitly before falling through to onNextAction.
                    onFailedToLoad?.invoke(ApAdError("Failed to load"))
                }
            }

            if (splashAd == null) {
                dialog.dismissSafely()
                onNextAction()
                return@launch
            }

            val keepDialogUntilShow = isAppInForeground()
            if (!keepDialogUntilShow) {
                dialog.dismissSafely()
            }

            pendingShowGate = runWhenAppForeground(
                deferToForeground = showWhenReturnFromBackground,
                onForeground = { wasDeferred ->
                    if (wasDeferred) dialog.dismissSafely()
                    showSplashAd(
                        activity = activity,
                        onNextAction = onNextAction,
                        onClosed = onClosed,
                        onFailedToShow = onFailedToShow,
                        clearFullScreenFlagOnSkip = wasDeferred,
                        waitingDialog = if (keepDialogUntilShow && !wasDeferred) dialog else null,
                    )
                },
                onDropped = {
                    dialog.dismissSafely()
                    onNextAction()
                }
            )
        }
    }

    /**
     * Callback-style variant matching interstitial / rewarded helpers.
     */
    fun waitLoadAndShow(
        activity: Activity,
        timeoutMs: Long = config.loadTimeout,
        showWhenReturnFromBackground: Boolean = true,
        callback: AdsCallback? = null,
    ) {
        waitLoadAndShow(
            activity = activity,
            timeoutMs = timeoutMs,
            showWhenReturnFromBackground = showWhenReturnFromBackground,
            onNextAction = { callback?.onNextAction() },
            onClosed = { callback?.onAdClosed() },
            onFailedToShow = { callback?.onAdFailedToShow(it) },
            onFailedToLoad = { callback?.onAdFailedToLoad(it) },
        )
    }

    // endregion

    // region Helper Methods

    private suspend fun loadSingleAppOpenAd(adId: String): AppOpenAd? {
        return suspendCancellableCoroutine { continuation ->
            AppOpenAd.load(
                application,
                adId,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        log("App open ad loaded: $adId")

                        ad.setOnPaidEventListener { adValue ->
                            AdsManager.logPaidEvent(application, adValue, ad.adUnitId, ad.responseInfo, AdType.APP_OPEN)
                        }

                        if (continuation.isActive) {
                            continuation.resume(ad)
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        log("App open ad failed to load: ${error.message}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                log("App open ad loading cancelled: $adId")
            }
        }
    }

    // endregion

    // region Activity Lifecycle Callbacks

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        log("Activity started: ${activity::class.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        if (activity::class.java.name != AdActivity::class.java.name) {
            loadResumeAd()
        }
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        currentActivityRef?.clear()
        currentActivityRef = null
    }

    // endregion

    // region Lifecycle Observer

    override fun onStart(owner: LifecycleOwner) {
        val currentActivity = getCurrentActivity()

        if (!isInitialized.get()) {
            log("Not initialized")
            return
        }

        if (currentActivity == null) {
            log("No current activity")
            return
        }

        if (!isAppResumeEnabled.get()) {
            log("App resume disabled")
            return
        }

        if (isInterstitialShowing.get()) {
            log("Interstitial is showing")
            return
        }

        if (AdsManager.isFullScreenAdShowing()) {
            log("Another full-screen ad is showing / pending — skip resume")
            return
        }

        if (disableAdResumeByClickAction.getAndSet(false)) {
            log("Resume disabled by click action")
            return
        }

        for (activityClass in disabledActivities) {
            if (activityClass.name == currentActivity::class.java.name) {
                log("Activity is in disabled list")
                return
            }
        }

        log("Showing resume ad on start: ${currentActivity::class.simpleName}")
        showResumeAdIfAvailable()
    }

    override fun onStop(owner: LifecycleOwner) {
        log("App stopped")
    }

    // endregion

    // region Public Control Methods

    fun enableAppResume() {
        isAppResumeEnabled.set(true)
    }

    fun disableAppResume() {
        isAppResumeEnabled.set(false)
    }

    fun setInterstitialShowing(showing: Boolean) {
        isInterstitialShowing.set(showing)
    }

    fun disableAdResumeByClickAction() {
        disableAdResumeByClickAction.set(true)
    }

    fun disableAppResumeWithActivity(activityClass: Class<*>) {
        if (!disabledActivities.contains(activityClass)) {
            disabledActivities.add(activityClass)
        }
    }

    fun enableAppResumeWithActivity(activityClass: Class<*>) {
        disabledActivities.remove(activityClass)
    }

    fun isShowingAd(): Boolean = isShowingAd.get()

    // endregion

    // region Dialog Management

    private fun showResumeDialog() {
        try {
            hideResumeDialog()
            val activity = getCurrentActivity() ?: return
            resumeDialog = ResumeLoadingDialog(activity).apply { show() }
        } catch (e: Exception) {
            log("Error showing resume dialog: ${e.message}")
        }
    }

    private fun hideResumeDialog() {
        try {
            resumeDialog?.takeIf { it.isShowing }?.dismiss()
            resumeDialog = null
        } catch (e: Exception) {
            log("Error hiding resume dialog: ${e.message}")
        }
    }

    private fun showSplashDialog(activity: Activity) {
        try {
            hideSplashDialog()
            splashDialog = PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
        } catch (e: Exception) {
            log("Error showing splash dialog: ${e.message}")
        }
    }

    private fun hideSplashDialog() {
        scope.launch {
            delay(300)
            try {
                splashDialog?.takeIf { it.isShowing }?.dismiss()
                splashDialog = null
            } catch (e: Exception) {
                log("Error hiding splash dialog: ${e.message}")
            }
        }
    }

    // endregion

    private fun getCurrentActivity(): Activity? {
        return try {
            currentActivityRef?.get()
        } catch (e: Exception) {
            null
        }
    }

    private fun log(message: String) {
        android.util.Log.d(TAG, message)
    }
}
