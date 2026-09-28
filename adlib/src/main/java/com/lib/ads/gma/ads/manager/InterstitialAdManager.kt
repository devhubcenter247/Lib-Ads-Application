package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.LoadAdError
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppLogger
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import com.lib.ads.gma.ads.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

object InterstitialAdManager {
    private const val TAG = "InterstitialAdManager"
    private const val INTERS_ADS = 3
    private val AD_TRANSITION_COVER_DELAY = 300.milliseconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null
    private var timeDelayJob: Job? = null
    private var pendingShowJob: Job? = null

    private var dialog: PrepareLoadingAdsDialog? = null
    private var dialogOwner: WeakReference<Activity>? = null
    private var dialogIsSplash = false
    private var lifecycleApplication: Application? = null
    private var isTimeout = false
    private var _isShowLoadingSplash = false
    var isTimeDelay = false
    var mInterstitialSplash: InterstitialAd? = null

    val isShowLoadingSplash: Boolean get() = _isShowLoadingSplash

    private val dialogLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (dialogOwner?.get() === activity) {
                pendingShowJob?.cancel()
                pendingShowJob = null
                safeDismissDialog(activity)
            }
        }
    }

    fun interstitialSplashLoaded(): Boolean = mInterstitialSplash != null

    fun getInterstitialAds(context: Context, id: String, adCallback: AdsCallback?) {
        AdsManager.checkTestId(context, INTERS_ADS, id)
        if (!AdsManager.canRequestAds(context)) {
            adCallback?.onInterstitialLoad(null)
            return
        }
        val appCtx = context.applicationContext
        AdLoadStats.recordRequested(AdType.INTERSTITIAL, adUnitId = id)

        InterstitialAd.load(context, id, AdsManager.getAdRequest(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                Log.i(TAG, "InterstitialAds onAdLoaded")
                AdLoadStats.recordLoaded(AdType.INTERSTITIAL, adUnitId = id)
                interstitialAd.setImmersiveMode(true)
                adCallback?.onInterstitialLoad(ApInterstitialAd(interstitialAd))

                interstitialAd.setOnPaidEventListener { adValue ->
                    Log.d(TAG, "OnPaidEvent getInterstitialAds: ${adValue.valueMicros}")
                    AdsManager.logPaidEvent(appCtx, adValue, interstitialAd.adUnitId, interstitialAd.responseInfo, AdType.INTERSTITIAL)
                }
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                Log.i(TAG, loadAdError.message)
                AdLoadStats.recordFailed(AdType.INTERSTITIAL, adUnitId = id, reason = loadAdError.code.toString())
                adCallback?.onAdFailedToLoad(ApAdError(loadAdError))
            }
        })
    }

    fun getInterstitialAdsList(context: Context, listId: List<String>?, adListener: AdsCallback) {
        if (listId.isNullOrEmpty()) {
            adListener.onAdFailedToLoad(ApAdError("list id is null or empty"))
            return
        }
        val index = intArrayOf(0)
        val adCallback = object : AdsCallback() {
            override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
                super.onInterstitialLoad(interstitialAd)
                AppLogger.d(TAG, "loadInterstitialList onInterstitialLoad:")
                adListener.onInterstitialLoad(interstitialAd)
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                AppLogger.e(TAG, "loadInterstitialList onAdFailedToLoad: ${adError?.message}")
                if (index[0] < listId.size - 1) {
                    index[0] = index[0] + 1
                    AppLogger.d(TAG, "loadInterstitialList: ${index[0]} id: ${listId[index[0]]}")
                    getInterstitialAds(context, listId[index[0]], this)
                } else {
                    adListener.onAdFailedToLoad(adError)
                }
            }
        }

        AppLogger.d(TAG, "loadInterstitialList: ${index[0]} id: ${listId[index[0]]}")
        getInterstitialAds(context, listId[index[0]], adCallback)
    }

    fun loadSplashInterstitialAds(
        context: Context,
        id: String,
        enabled: Boolean = true,
        timeOut: Long,
        timeDelay: Long,
        adListener: AdsCallback,
    ) {
        isTimeDelay = false
        isTimeout = false
        Log.i(TAG, "loadSplashInterstitialAds start time loading: ${TimeUtils.formatCurrentTime()}")

        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch — false skips straight to onNextAction, same
        // contract as "no ad available".
        if (!enabled || !AdsManager.canRequestAds(context)) {
            adListener.onNextAction()
            return
        }

        timeDelayJob?.cancel()
        timeDelayJob = scope.launch {
            delay(timeDelay.milliseconds)
            if (mInterstitialSplash != null) {
                Log.i(TAG, "loadSplashInterstitialAds: onAdSplashReady delay")
                adListener.onAdSplashReady()
                return@launch
            }
            Log.i(TAG, "loadSplashInterstitialAds: delay validate")
            isTimeDelay = true
        }

        if (timeOut > 0) {
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(timeOut.milliseconds)
                Log.e(TAG, "loadSplashInterstitialAds: on timeout")
                isTimeout = true
                if (mInterstitialSplash != null) {
                    Log.i(TAG, "loadSplashInterstitialAds: onAdSplashReady timeout")
                    adListener.onAdSplashReady()
                    return@launch
                }
                adListener.onNextAction()
            }
        }

        getInterstitialAds(context, id, object : AdsCallback() {
            override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
                super.onInterstitialLoad(interstitialAd)
                Log.e(TAG, "loadSplashInterstitialAds end time loading success: ${TimeUtils.formatCurrentTime()} isTimeout:$isTimeout")
                if (isTimeout) return
                val rawAd = interstitialAd?.interstitialAd
                if (rawAd != null) {
                    timeoutJob?.cancel()
                    mInterstitialSplash = rawAd
                    Log.e(TAG, "onInterstitialLoad: isTimeDelay $isTimeDelay")
                    if (isTimeDelay) {
                        adListener.onAdSplashReady()
                    }
                }
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                Log.e(TAG, "loadSplashInterstitialAds end time loading error: ${TimeUtils.formatCurrentTime()} isTimeout:$isTimeout")
                if (isTimeout) return
                timeoutJob?.cancel()
                timeDelayJob?.cancel()
                Log.e(TAG, "loadSplashInterstitialAds: load fail ${adError?.message}")
                adListener.onAdFailedToLoad(adError)
                adListener.onNextAction()
            }
        })
    }

    fun loadSplashListAds(
        context: Context,
        listId: List<String>,
        enabled: Boolean = true,
        timeOut: Long,
        timeDelay: Long,
        adCallback: AdsCallback,
    ) {
        isTimeDelay = false
        isTimeout = false
        Log.i(TAG, "loadSplashListAds start time loading: ${TimeUtils.formatCurrentTime()}")

        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch — false skips straight to onNextAction, same
        // contract as "no ad available".
        if (!enabled || !AdsManager.canRequestAds(context)) {
            adCallback.onNextAction()
            return
        }

        scope.launch {
            delay(timeDelay.milliseconds)
            if (mInterstitialSplash != null) {
                adCallback.onAdSplashReady()
                return@launch
            }
            Log.i(TAG, "loadSplashListAds: delay validate")
            isTimeDelay = true
        }

        if (timeOut > 0) {
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(timeOut.milliseconds)
                Log.e(TAG, "loadSplashListAds: on timeout")
                isTimeout = true
                if (mInterstitialSplash != null) {
                    adCallback.onAdSplashReady()
                    return@launch
                }
                adCallback.onNextAction()
            }
        }
        loadNextAdSplashList(context, listId, 0, adCallback)
    }

    private fun loadNextAdSplashList(
        context: Context,
        listId: List<String>,
        index: Int,
        adCallback: AdsCallback
    ) {
        if (index >= listId.size) {
            Log.e(TAG, "loadNextAdSplashList: All ad IDs failed.")
            timeoutJob?.cancel()
            adCallback.onAdFailedToLoad(ApAdError("All ad IDs failed."))
            adCallback.onNextAction()
            return
        }

        Log.i(TAG, "loadNextAdSplashList: Trying ad ID at index $index")
        getInterstitialAds(context, listId[index], object : AdsCallback() {
            override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
                super.onInterstitialLoad(interstitialAd)
                Log.e(TAG, "Ad loaded successfully at index $index: ${TimeUtils.formatCurrentTime()} Timeout: $isTimeout")
                if (isTimeout) return
                val rawAd = interstitialAd?.interstitialAd
                if (rawAd != null) {
                    mInterstitialSplash = rawAd
                    if (isTimeDelay) {
                        adCallback.onAdSplashReady()
                    }
                }
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                Log.e(TAG, "Ad failed to load at index $index: ${TimeUtils.formatCurrentTime()} Timeout: $isTimeout")
                if (isTimeout) return
                loadNextAdSplashList(context, listId, index + 1, adCallback)
            }
        })
    }

    fun onShowSplash(activity: Activity, enabled: Boolean = true, adListener: AdsCallback) {
        val ad = mInterstitialSplash
        Log.d(TAG, "onShowSplash:")

        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch — false skips straight to onNextAction, same
        // contract as "no ad available".
        if (!enabled || ad == null || !activity.canHostAdWindow() || !isAppInForeground()) {
            adListener.onNextAction()
            return
        }
        val adUnitId = ad.adUnitId
        val appCtx = activity.applicationContext
        val activityRef = WeakReference(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Splash:onAdShowedFullScreenContent")
                AdsManager.setFullScreenAdShowing(true)
                scheduleDialogDismissAfterShow(activityRef)
            }

            override fun onAdDismissedFullScreenContent() {
                adListener.onNextAction()
                super.onAdDismissedFullScreenContent()
                Log.d(TAG, "Splash:onAdDismissedFullScreenContent")
                AdsManager.setFullScreenAdShowing(false)
                safeDismissDialog(activityRef)
                mInterstitialSplash = null

                adListener.onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "Splash onAdFailedToShowFullScreenContent: ${adError.message}")
                AdsManager.setFullScreenAdShowing(false)
                safeDismissDialog(activityRef)
                mInterstitialSplash = null
                adListener.onAdFailedToShow(ApAdError(adError))
                adListener.onNextAction()
            }

            override fun onAdClicked() {
                super.onAdClicked()
                adListener.onAdClicked()
                AdsManager.handleAdClick(appCtx, adUnitId)
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsManager.handleAdImpression()
                adListener.onAdImpression()
            }
        }

        if (!showLoadingDialogSafely(activity, isSplash = true)) {
            adListener.onNextAction()
            return
        }
        pendingShowJob?.cancel()
        pendingShowJob = scope.launch {
            delay(800.milliseconds)
            pendingShowJob = null
            val hostActivity = activityRef.get()
            if (hostActivity == null) {
                adListener.onNextAction()
                return@launch
            }
            if (!hostActivity.canHostAdWindow() || !isAppInForeground()) {
                safeDismissDialog(hostActivity)
                adListener.onNextAction()
                return@launch
            }
            try {
                ad.show(hostActivity)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to show splash interstitial", error)
                safeDismissDialog(hostActivity)
                mInterstitialSplash = null
                adListener.onAdFailedToShow(ApAdError(error.message ?: "Unable to show splash interstitial"))
                adListener.onNextAction()
            }
        }
    }

    fun onCheckShowSplashWhenFail(activity: Activity, callback: AdsCallback, timeDelay: Int) {
        scope.launch (Dispatchers.Main){
            delay(timeDelay.toLong().milliseconds)
            if (interstitialSplashLoaded() && !isShowLoadingSplash) {
                Log.i(TAG, "show ad splash when show fail in background")
                onShowSplash(activity = activity, adListener = callback)
            }
        }
    }

    /**
     * Consolidated show method. When checkInterval=true, interval checking is enforced.
     * When checkInterval=false, the ad is shown without interval gating.
     */
    fun showInterstitial(
        activity: Activity,
        mInterstitialAd: ApInterstitialAd?,
        callback: AdsCallback,
        checkInterval: Boolean = false
    ) {
        val config = AdSdkInitializer.adConfig
        if (checkInterval && config != null) {
            if (System.currentTimeMillis() - SharePreferenceUtils.getLastImpressionInterstitialTime(activity)
                < config.intervalInterstitialAd * 1000L
            ) {
                Log.i(TAG, "showInterstitial: ignore by interval impression interstitial time")
                callback.onNextAction()
                return
            }
        }

        if (mInterstitialAd == null || mInterstitialAd.isNotReady()) {
            Log.e(TAG, "showInterstitial: ApInterstitialAd is not ready")
            if (checkInterval) {
                callback.onNextAction()
            } else {
                callback.onAdFailedToShow(ApAdError("ApInterstitialAd is not ready"))
            }
            return
        }

        val adCallback = object : AdsCallback() {
            override fun onAdClosed() {
                super.onAdClosed()
                Log.d(TAG, "onAdClosed:")
                callback.onAdClosed()
                mInterstitialAd.interstitialAd = null
            }

            override fun onNextAction() {
                super.onNextAction()
                Log.d(TAG, "onNextAction:")
                callback.onNextAction()
            }

            override fun onAdFailedToShow(adError: ApAdError?) {
                super.onAdFailedToShow(adError)
                Log.d(TAG, "onAdFailedToShow:")
                callback.onAdFailedToShow(adError)
                mInterstitialAd.interstitialAd = null
            }

            override fun onAdClicked() {
                super.onAdClicked()
                callback.onAdClicked()
            }

            override fun onInterstitialShow() {
                super.onInterstitialShow()
                callback.onInterstitialShow()
            }

            override fun onAdImpression() {
                super.onAdImpression()
                callback.onAdImpression()
            }
        }
        showInterstitialAdInternal(activity, mInterstitialAd.interstitialAd, adCallback)
    }

    fun forceShowInterstitial(
        activity: Activity,
        mInterstitialAd: ApInterstitialAd?,
        callback: AdsCallback
    ) {
        showInterstitial(activity, mInterstitialAd, callback, checkInterval = true)
    }

    fun showInterstitialAdByTimes(
        activity: Activity,
        mInterstitialAd: ApInterstitialAd,
        callback: AdsCallback
    ) {
        showInterstitial(activity, mInterstitialAd, callback, checkInterval = false)
    }

    private fun showInterstitialAdInternal(
        activity: Activity,
        mInterstitialAd: InterstitialAd?,
        callback: AdsCallback?
    ) {
        if (AdsManager.isPurchased()) {
            callback?.onNextAction()
            return
        }
        if (mInterstitialAd == null) {
            callback?.onNextAction()
            return
        }
        val appCtx = activity.applicationContext
        val activityRef = WeakReference(activity)

        mInterstitialAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                super.onAdDismissedFullScreenContent()
                AdsManager.setFullScreenAdShowing(false)
                callback?.onNextAction()
                callback?.onAdClosed()
                safeDismissDialog(activityRef)
                Log.e(TAG, "onAdDismissedFullScreenContent")
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                super.onAdFailedToShowFullScreenContent(adError)
                Log.e(TAG, "onAdFailedToShowFullScreenContent: ${adError.message}")
                AdsManager.setFullScreenAdShowing(false)
                callback?.onAdFailedToShow(ApAdError(adError))
                callback?.onNextAction()
                safeDismissDialog(activityRef)
            }

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                Log.e(TAG, "onAdShowedFullScreenContent")
                SharePreferenceUtils.setLastImpressionInterstitialTime(appCtx)
                AdsManager.setFullScreenAdShowing(true)
                scheduleDialogDismissAfterShow(activityRef)
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsManager.handleAdClick(appCtx, mInterstitialAd.adUnitId)
                callback?.onAdClicked()
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsManager.handleAdImpression()
                callback?.onAdImpression()
            }
        }
        showInterstitialAdWithDialog(activity, mInterstitialAd, callback)
    }

    private fun showInterstitialAdWithDialog(
        activity: Activity,
        mInterstitialAd: InterstitialAd?,
        callback: AdsCallback?
    ) {
        if (mInterstitialAd != null) {
            if (!showLoadingDialogSafely(activity, isSplash = false)) {
                callback?.onNextAction()
                return
            }
            callback?.onInterstitialShow()

            val activityRef = WeakReference(activity)
            pendingShowJob?.cancel()
            pendingShowJob = scope.launch {
                delay(800)
                pendingShowJob = null
                val hostActivity = activityRef.get()
                if (hostActivity == null) {
                    callback?.onNextAction()
                    return@launch
                }
                if (!hostActivity.canHostAdWindow() || !isAppInForeground()) {
                    safeDismissDialog(hostActivity)
                    callback?.onNextAction()
                    return@launch
                }
                try {
                    mInterstitialAd.show(hostActivity)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to show interstitial", error)
                    safeDismissDialog(hostActivity)
                    callback?.onAdFailedToShow(ApAdError(error.message ?: "Unable to show interstitial"))
                    callback?.onNextAction()
                }
            }
        } else {
            safeDismissDialog()
            callback?.onNextAction()
        }
    }

    private fun showLoadingDialogSafely(activity: Activity, isSplash: Boolean): Boolean {
        if (!activity.canHostAdWindow() || !isAppInForeground()) return false

        ensureDialogLifecycleTracking(activity)
        safeDismissDialog()
        val loadingDialog = PrepareLoadingAdsDialog(activity).apply {
            setCancelable(false)
        }
        dialog = loadingDialog
        dialogOwner = WeakReference(activity)
        dialogIsSplash = isSplash
        if (isSplash) _isShowLoadingSplash = true

        try {
            loadingDialog.show()
            return true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to show loading dialog", error)
            safeDismissDialog(activity)
            return false
        }
    }

    private fun safeDismissDialog(activity: Activity? = null) {
        val owner = dialogOwner?.get()
        if (activity != null && owner !== activity) return

        val loadingDialog = dialog
        if (dialogIsSplash) _isShowLoadingSplash = false
        dialog = null
        dialogOwner = null
        dialogIsSplash = false
        if (loadingDialog?.isShowing == true) {
            try {
                loadingDialog.dismiss()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to dismiss loading dialog", error)
            }
        }
    }

    private fun safeDismissDialog(activityRef: WeakReference<Activity>) {
        activityRef.get()?.let { safeDismissDialog(it) }
    }

    /**
     * The ad's own window needs a brief moment to finish its entrance transition and fully
     * cover the screen. Dismissing the loading dialog immediately on `onAdShowedFullScreenContent`
     * exposes a flash of the underlying activity content during that gap, so the dismissal is
     * delayed slightly to bridge it.
     */
    private fun scheduleDialogDismissAfterShow(activityRef: WeakReference<Activity>) {
        scope.launch {
            delay(AD_TRANSITION_COVER_DELAY)
            safeDismissDialog(activityRef)
        }
    }

    private fun ensureDialogLifecycleTracking(activity: Activity) {
        val application = activity.application
        if (lifecycleApplication === application) return

        lifecycleApplication?.unregisterActivityLifecycleCallbacks(dialogLifecycleCallbacks)
        application.registerActivityLifecycleCallbacks(dialogLifecycleCallbacks)
        lifecycleApplication = application
    }

    private fun Activity.canHostAdWindow(): Boolean = !isFinishing && !isDestroyed
}
