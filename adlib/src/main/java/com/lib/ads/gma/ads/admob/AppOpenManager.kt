package com.lib.ads.gma.ads.admob

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.dialog.ResumeLoadingDialog
import com.lib.ads.gma.ads.event.LogEventManager
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.listener.AdResumePreShowListener
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager for App Open/Resume ads with lifecycle-aware coroutines.
 */
class AppOpenManager private constructor() : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null
    private var timeDelayJob: Job? = null
    private var splashDialogJob: Job? = null

    private var appResumeAd: AppOpenAd? = null
    private var splashAd: AppOpenAd? = null
    private var fullScreenContentCallback: FullScreenContentCallback? = null
    private var isTimeout = false
    private var isTimeDelay = false
    private var appResumeAdId: String? = null
    private var appResumeAdIdList: List<String>? = null
    private var splashAdId: String? = null
    private var splashAdIdList: List<String>? = null

    private var currentActivityRef: WeakReference<Activity>? = null
    private var myApplication: Application? = null

    private var appResumeLoadTime: Long = 0

    private var _isInitialized = false
    private var _isAppResumeEnabled = true
    private var _isInterstitialShowing = false
    private var enableScreenContentCallback = false
    private var disableAdResumeByClickAction = false
    private val disabledAppOpenList: MutableSet<Class<*>> = mutableSetOf()
    private var isLoadingAppResume = false
    private var dialog: Dialog? = null
    private var dialogSplash: Dialog? = null
    private var isEnableList = false
    private var adResumePreShowListener: AdResumePreShowListener? = null

    var isInitialized: Boolean
        get() = _isInitialized
        set(value) { _isInitialized = value }

    var isInterstitialShowing: Boolean
        get() = _isInterstitialShowing
        set(value) { _isInterstitialShowing = value }

    fun init(application: Application, appOpenAdId: String) {
        disableAdResumeByClickAction = false
        this.myApplication = application
        registerLifecycleCallbacksOnce(application)
        this.appResumeAdId = appOpenAdId
        this.appResumeAdIdList = null
        this.isEnableList = false
    }

    fun init(application: Application, appOpenAdIdList: List<String>) {
        disableAdResumeByClickAction = false
        this.myApplication = application
        registerLifecycleCallbacksOnce(application)
        this.appResumeAdIdList = appOpenAdIdList
        this.appResumeAdId = null
        this.isEnableList = true
    }

    private fun registerLifecycleCallbacksOnce(application: Application) {
        if (_isInitialized) return
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        _isInitialized = true
    }

    fun setEnableScreenContentCallback(enableScreenContentCallback: Boolean) {
        this.enableScreenContentCallback = enableScreenContentCallback
    }

    fun disableAdResumeByClickAction() {
        disableAdResumeByClickAction = true
    }

    fun isShowingAd(): Boolean = isShowingAd

    fun disableAppResumeWithActivity(activityClass: Class<*>) {
        Log.d(TAG, "disableAppResumeWithActivity: ${activityClass.name}")
        disabledAppOpenList.add(activityClass)
    }

    fun enableAppResumeWithActivity(activityClass: Class<*>) {
        Log.d(TAG, "enableAppResumeWithActivity: ${activityClass.name}")
        disabledAppOpenList.remove(activityClass)
    }

    fun disableAppResume() {
        _isAppResumeEnabled = false
    }

    fun enableAppResume() {
        _isAppResumeEnabled = true
    }

    fun setAppResumeAdId(appResumeAdId: String) {
        this.appResumeAdId = appResumeAdId
    }

    fun getAppResumeAdId(): String? = appResumeAdId

    fun setAppResumeAdIdList(appResumeAdIdList: List<String>) {
        this.appResumeAdIdList = appResumeAdIdList
    }

    fun getAppResumeAdIdList(): List<String>? = appResumeAdIdList

    fun setSplashAdId(id: String) {
        this.splashAdId = id
    }

    fun setSplashAdIdList(ids: List<String>) {
        this.splashAdIdList = ids
    }

    fun setFullScreenContentCallback(callback: FullScreenContentCallback?) {
        this.fullScreenContentCallback = callback
    }

    fun removeFullScreenContentCallback() {
        this.fullScreenContentCallback = null
    }

    fun loadAppOpenResume() {
        if (isEnableList) {
            loadAppOpenResumeList()
            return
        }

        Log.d(TAG, "loadAppOpenResume:")
        if (isAdAvailable(false)) {
            return
        }
        if (isShowingAd) {
            Log.d(TAG, "isShowingAd:")
            return
        }
        if (isLoadingAppResume) {
            Log.d(TAG, "isLoadingAppResume:")
            return
        }
        val app = myApplication ?: return
        val adId = appResumeAdId ?: return
        isLoadingAppResume = true

        AdLoadStats.recordRequested(AdType.APP_OPEN, adUnitId = adId)
        AppOpenAd.load(app, adId, getAdRequest(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                AdLoadStats.recordFailed(AdType.APP_OPEN, adUnitId = adId, reason = loadAdError.code.toString())
                isLoadingAppResume = false
                Log.e(TAG, "loadAppOpenResume onAdFailedToLoad: ${loadAdError.message}")
            }

            override fun onAdLoaded(appOpenAd: AppOpenAd) {
                super.onAdLoaded(appOpenAd)
                AdLoadStats.recordLoaded(AdType.APP_OPEN, adUnitId = adId)
                isLoadingAppResume = false
                Log.d(TAG, "loadAppOpenResume onAdLoaded:")
                appOpenAd.setOnPaidEventListener { adValue ->
                    LogEventManager.logPaidAdImpression(
                        app.applicationContext,
                        adValue,
                        appOpenAd.adUnitId,
                        appOpenAd.responseInfo,
                        AdType.APP_OPEN
                    )
                }
                appResumeAd = appOpenAd
                appResumeLoadTime = Date().time
            }
        })
    }

    private fun loadAppOpenResumeList() {
        Log.d(TAG, "loadAppOpenResumeList:")
        if (isAdAvailable(false)) {
            return
        }
        if (isShowingAd) {
            Log.d(TAG, "isShowingAd:")
            return
        }
        if (isLoadingAppResume) {
            Log.d(TAG, "isLoadingAppResume:")
            return
        }

        val app = myApplication ?: return
        val adIdList = appResumeAdIdList ?: return
        if (adIdList.isEmpty()) return

        isLoadingAppResume = true
        val index = intArrayOf(0)

        AdLoadStats.recordRequested(AdType.APP_OPEN, adUnitId = adIdList[index[0]])
        val callback = object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                AdLoadStats.recordFailed(AdType.APP_OPEN, adUnitId = adIdList[index[0]], reason = loadAdError.code.toString())
                AppLogger.e(TAG, "loadAppOpenResumeList onAdFailedToLoad: ${loadAdError.message}")
                if (index[0] < adIdList.size - 1) {
                    index[0] = index[0] + 1
                    AppLogger.d(TAG, "loadAppOpenResumeList: ${index[0]} id: ${adIdList[index[0]]}")
                    AppOpenAd.load(app, adIdList[index[0]], getAdRequest(), this)
                } else {
                    isLoadingAppResume = false
                }
            }

            override fun onAdLoaded(appOpenAd: AppOpenAd) {
                super.onAdLoaded(appOpenAd)
                AdLoadStats.recordLoaded(AdType.APP_OPEN, adUnitId = appOpenAd.adUnitId)
                isLoadingAppResume = false
                AppLogger.d(TAG, "loadAppOpenResumeList: ${index[0]} onAdLoaded:")
                appOpenAd.setOnPaidEventListener { adValue ->
                    LogEventManager.logPaidAdImpression(
                        app.applicationContext,
                        adValue,
                        appOpenAd.adUnitId,
                        appOpenAd.responseInfo,
                        AdType.APP_OPEN
                    )
                }
                appResumeAd = appOpenAd
                appResumeLoadTime = Date().time
            }
        }
        AppLogger.d(TAG, "loadAppOpenResumeList: ${index[0]} id: ${adIdList[index[0]]}")
        AppOpenAd.load(app, adIdList[index[0]], getAdRequest(), callback)
    }

    private fun getAdRequest(): AdRequest = AdRequest.Builder().build()

    private fun wasLoadTimeLessThanNHoursAgo(loadTime: Long): Boolean {
        val dateDifference = Date().time - loadTime
        val numMilliSecondsPerHour = 3600000L
        return dateDifference < (numMilliSecondsPerHour * 4)
    }

    fun isAdAvailable(isSplash: Boolean): Boolean {
        val loadTime = if (isSplash) 0 else appResumeLoadTime
        val isFresh = wasLoadTimeLessThanNHoursAgo(loadTime)
        val hasAd = if (isSplash) splashAd != null else appResumeAd != null
        val available = hasAd && isFresh

        AppLogger.d(TAG, "isAdAvailable [hasAd=$hasAd, isFresh=$isFresh] => $available")
        return available
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (!isShowingAd) {
            currentActivityRef = WeakReference(activity)
        }
        Log.d(TAG, "onActivityStarted: $activity")
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isShowingAd && activity.javaClass.name != AdActivity::class.java.name) {
            currentActivityRef = WeakReference(activity)
        }
        Log.d(TAG, "onActivityResumed: $activity")
        val activityCanUseAppOpen = disabledAppOpenList.none { it.name == activity.javaClass.name }
        if (_isAppResumeEnabled && activityCanUseAppOpen && activity.javaClass.name != AdActivity::class.java.name) {
            loadAppOpenResume()
        }
    }

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        try {
            if (currentActivityRef?.get() === activity) {
                currentActivityRef?.clear()
                currentActivityRef = null
                Log.d(TAG, "onActivityDestroyed: clear $activity")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showAdIfAvailable() {
        val currentActivity = getCurrentActivity()
        if (currentActivity == null || AppPurchase.getInstance().isPurchased(currentActivity)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
            }
            return
        }

        Log.d(TAG, "showAdIfAvailable: ${ProcessLifecycleOwner.get().lifecycle.currentState}")
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.d(TAG, "showAdIfAvailable: return")
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
            }
            return
        }
        if (!isShowingAd && isAdAvailable(false)) {
            showResumeAds()
        }
    }

    private fun showResumeAds() {
        val currentActivity = getCurrentActivity()
        if (appResumeAd == null || currentActivity == null || AppPurchase.getInstance().isPurchased(currentActivity)) {
            return
        }
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try {
                hideDialogLoading()
                showDialogLoading()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            appResumeAd?.let { ad ->
                val adUnitId = ad.adUnitId
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        appResumeAd = null
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback?.onAdDismissedFullScreenContent()
                        }
                        isShowingAd = false
                        hideDialogLoading()
                        loadAppOpenResume()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Log.e(TAG, "onAdFailedToShowFullScreenContent: ${adError.message}")
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback?.onAdFailedToShowFullScreenContent(adError)
                        }

                        if (!currentActivity.isDestroyed) {
                            Log.d(TAG, "dismiss dialog loading ad open:")
                            hideDialogLoading()
                        }
                        appResumeAd = null
                        isShowingAd = false
                        loadAppOpenResume()
                    }

                    override fun onAdShowedFullScreenContent() {
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback?.onAdShowedFullScreenContent()
                        }
                        isShowingAd = true
                        appResumeAd = null
                        Log.d(TAG, "onAdShowedFullScreenContent:")
                    }

                    override fun onAdClicked() {
                        super.onAdClicked()
                        LogEventManager.logClickAdsEvent(currentActivity, adUnitId)
                        fullScreenContentCallback?.onAdClicked()
                    }

                    override fun onAdImpression() {
                        super.onAdImpression()
                        LogEventManager.onTrackImpression()
                        fullScreenContentCallback?.onAdImpression()
                    }
                }
                isShowingAd = true
                ad.show(currentActivity)
            }
        }
    }

    fun loadOpenAppAdSplash(
        activity: Activity,
        timeDelay: Long,
        timeOut: Long,
        adCallback: AdsCallback
    ) {
        isTimeout = false
        if (AppPurchase.getInstance().isPurchased(activity)) {
            adCallback.onNextAction()
            return
        }
        val startLoadAd = System.currentTimeMillis()

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeOut)
            isTimeout = true
            Log.d(TAG, "getAdSplash time out")
            adCallback.onNextAction()
            isShowingAd = false
        }

        val request = getAdRequest()
        val adId = splashAdId ?: run {
            adCallback.onNextAction()
            return
        }

        AdLoadStats.recordRequested(AdType.APP_OPEN, adUnitId = adId)
        AppOpenAd.load(
            activity,
            adId,
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    AdLoadStats.recordFailed(AdType.APP_OPEN, adUnitId = adId, reason = loadAdError.code.toString())
                    if (isTimeout) return
                    Log.d(TAG, "onAdFailedToLoad: splash: ${loadAdError.message}")
                    adCallback.onNextAction()
                    adCallback.onAdFailedToLoad(ApAdError(loadAdError))
                    timeoutJob?.cancel()
                }

                override fun onAdLoaded(appOpenAd: AppOpenAd) {
                    super.onAdLoaded(appOpenAd)
                    AdLoadStats.recordLoaded(AdType.APP_OPEN, adUnitId = appOpenAd.adUnitId)
                    if (isTimeout) return
                    Log.d(TAG, "onAdLoaded: splash")
                    timeoutJob?.cancel()
                    splashAd = appOpenAd
                    splashAd?.setOnPaidEventListener { adValue ->
                        LogEventManager.logPaidAdImpression(
                            activity,
                            adValue,
                            splashAd?.adUnitId ?: "",
                            splashAd?.responseInfo,
                            AdType.APP_OPEN
                        )
                    }
                    val delayTimeLeft = System.currentTimeMillis() - startLoadAd
                    scope.launch {
                        delay(if (delayTimeLeft >= timeDelay) 0 else (timeDelay - delayTimeLeft))
                        adCallback.onAdSplashReady()
                    }
                }
            }
        )
    }

    fun loadOpenAppAdSplashList(
        activity: Activity,
        timeDelay: Long,
        timeOut: Long,
        adCallback: AdsCallback
    ) {
        val adIdList = splashAdIdList
        if (adIdList.isNullOrEmpty()) {
            Log.d(TAG, "loadOpenAppAdSplashList: input list is null or empty")
            adCallback.onNextAction()
            return
        }

        if (AppPurchase.getInstance().isPurchased(activity)) {
            Log.d(TAG, "loadOpenAppAdSplashList: has been purchased")
            adCallback.onNextAction()
            return
        }
        Log.d(TAG, "loadOpenAppAdSplashList: list_size = ${adIdList.size}")

        timeDelayJob?.cancel()
        timeDelayJob = scope.launch {
            delay(timeDelay)
            if (splashAd != null) {
                adCallback.onAdSplashReady()
                return@launch
            }
            isTimeDelay = true
            Log.i(TAG, "loadOpenAppAdSplashList: time delay has been reached")
        }

        isTimeout = false
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeOut)
            adCallback.onNextAction()
            isShowingAd = false
            isTimeout = true
            Log.i(TAG, "loadOpenAppAdSplashList: time out has been reached")
        }

        loadNextOpenSplash(activity, adIdList, 0, adCallback)
    }

    private fun loadNextOpenSplash(activity: Activity, listId: List<String>, index: Int, adCallback: AdsCallback) {
        val request = getAdRequest()
        if (index >= listId.size) {
            Log.e(TAG, "loadOpenAppAdSplashList: All ad IDs failed, list_size: ${listId.size}")
            timeoutJob?.cancel()
            adCallback.onNextAction()
            return
        }

        AdLoadStats.recordRequested(AdType.APP_OPEN, adUnitId = listId[index])
        AppOpenAd.load(
            activity,
            listId[index],
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    AdLoadStats.recordFailed(AdType.APP_OPEN, adUnitId = listId[index], reason = loadAdError.code.toString())
                    Log.d(TAG, "loadOpenAppAdSplashList onAdFailedToLoad: index: $index, loadAdError: ${loadAdError.message}")
                    if (isTimeout) return
                    loadNextOpenSplash(activity, listId, index + 1, adCallback)
                }

                override fun onAdLoaded(appOpenAd: AppOpenAd) {
                    super.onAdLoaded(appOpenAd)
                    AdLoadStats.recordLoaded(AdType.APP_OPEN, adUnitId = appOpenAd.adUnitId)
                    Log.d(TAG, "loadOpenAppAdSplashList onAdLoaded, index: $index")
                    if (isTimeout) return
                    timeoutJob?.cancel()
                    splashAd = appOpenAd
                    splashAd?.setOnPaidEventListener { adValue ->
                        LogEventManager.logPaidAdImpression(
                            activity,
                            adValue,
                            splashAd?.adUnitId ?: "",
                            splashAd?.responseInfo,
                            AdType.APP_OPEN
                        )
                    }
                    if (isTimeDelay) {
                        adCallback.onAdSplashReady()
                    }
                }
            }
        )
    }

    /**
     * One-call splash app-open: set the id(s), load, and show as soon as it's ready —
     * then invoke [onNext] exactly once (on close, fail, timeout, or purchase).
     *
     * Replaces the manual `setSplashAdId(...)` + `loadOpenAppAdSplash(...)` with a nested
     * `onAdSplashReady { showAppOpenSplash(...) }` + duplicated `onNextAction` boilerplate.
     *
     * ```kotlin
     * AppOpenManager.getInstance().loadAndShowSplash(
     *     activity = this,
     *     adUnitId = BuildConfig.ad_appopen_splash,   // or adUnitIds = listOf(...)
     *     onNext = { startMain() },
     * )
     * ```
     *
     * @param adCallback optional listener for impression/click/closed events.
     */
    fun loadAndShowSplash(
        activity: Activity,
        adUnitId: String? = null,
        adUnitIds: List<String>? = null,
        timeDelayMs: Long = 3_000,
        timeOutMs: Long = 30_000,
        adCallback: AdsCallback? = null,
        onNext: () -> Unit,
    ) {
        adUnitIds?.takeIf { it.isNotEmpty() }?.let { setSplashAdIdList(it) }
            ?: adUnitId?.let { setSplashAdId(it) }

        val done = AtomicBoolean(false)
        val proceed = { if (done.compareAndSet(false, true)) onNext() }

        val loadCallback = object : AdsCallback() {
            override fun onAdSplashReady() {
                showAppOpenSplash(activity, object : AdsCallback() {
                    override fun onNextAction() { proceed() }
                    override fun onAdClosed() { adCallback?.onAdClosed() }
                    override fun onAdImpression() { adCallback?.onAdImpression() }
                    override fun onAdClicked() { adCallback?.onAdClicked() }
                    override fun onAdFailedToShow(adError: ApAdError?) { adCallback?.onAdFailedToShow(adError) }
                })
            }

            override fun onNextAction() { proceed() }   // timeout / fail / purchased
            override fun onAdFailedToLoad(adError: ApAdError?) { adCallback?.onAdFailedToLoad(adError) }
        }

        if (adUnitIds != null && adUnitIds.isNotEmpty()) {
            loadOpenAppAdSplashList(activity, timeDelayMs, timeOutMs, loadCallback)
        } else {
            loadOpenAppAdSplash(activity, timeDelayMs, timeOutMs, loadCallback)
        }
    }

    fun showAppOpenSplash(activity: Activity, adCallback: AdsCallback) {
        val appOpenAd = splashAd
        if (appOpenAd == null) {
            Log.d(TAG, "showAppOpenSplash: App Open Splash wasn't ready yet")
            adCallback.onNextAction()
            return
        }

        val adUnitId = appOpenAd.adUnitId
        showDialogLoadingSplash(activity)

        splashDialogJob?.cancel()
        splashDialogJob = scope.launch {
            delay(800)
            appOpenAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    adCallback.onAdClosed()
                    splashAd = null
                    isShowingAd = false
                    adCallback.onNextAction()
                    hideDialogLoadingSplash()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e(TAG, "onAdFailedToShowFullScreenContent splash: ${adError.message}")
                    adCallback.onAdFailedToShow(ApAdError(adError))
                    isShowingAd = false
                    adCallback.onNextAction()
                    hideDialogLoadingSplash()
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "onAdShowedFullScreenContent splash:")
                    isShowingAd = true
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    LogEventManager.logClickAdsEvent(activity, adUnitId)
                    adCallback.onAdClicked()
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    LogEventManager.onTrackImpression()
                    adCallback.onAdImpression()
                }
            }
            appOpenAd.show(activity)
        }
    }

    fun onCheckShowAppOpenSplashWhenFail(activity: Activity, callback: AdsCallback, timeDelay: Int) {
        scope.launch {
            delay(timeDelay.toLong())
            if (splashAd != null && !isShowingAd()) {
                showAppOpenSplash(activity, object : AdsCallback() {
                    override fun onNextAction() {
                        super.onNextAction()
                        callback.onNextAction()
                        splashAd = null
                    }

                    override fun onAdClosed() {
                        super.onAdClosed()
                        callback.onAdClosed()
                        splashAd = null
                    }

                    override fun onAdFailedToShow(adError: ApAdError?) {
                        super.onAdFailedToShow(adError)
                        callback.onAdFailedToShow(adError)
                        splashAd = null
                    }

                    override fun onAdImpression() {
                        super.onAdImpression()
                        callback.onAdImpression()
                        splashAd = null
                    }

                    override fun onAdClicked() {
                        super.onAdClicked()
                        callback.onAdClicked()
                    }
                })
            }
        }
    }

    fun setAdResumePreShowListener(listener: AdResumePreShowListener?) {
        this.adResumePreShowListener = listener
    }

    private fun hideDialogLoading() {
        if (dialog != null && dialog?.isShowing == true) {
            try {
                dialog?.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            dialog = null
        }
    }

    private fun showDialogLoading() {
        try {
            val currentActivity = getCurrentActivity()
            if (dialog == null && currentActivity != null) {
                dialog = ResumeLoadingDialog(currentActivity)
            }
            dialog?.show()
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    private fun showDialogLoadingSplash(activity: Activity) {
        try {
            if (dialogSplash == null) {
                dialogSplash = PrepareLoadingAdsDialog(activity)
            }
            dialogSplash?.setCancelable(false)
            dialogSplash?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideDialogLoadingSplash() {
        scope.launch {
            delay(300)
            if (dialogSplash != null && dialogSplash?.isShowing == true) {
                try {
                    dialogSplash?.dismiss()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                dialogSplash = null
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        val currentActivity = getCurrentActivity()

        if (!_isInitialized) {
            Log.d(TAG, "onResume: app not initialized")
            return
        }
        if (currentActivity == null) {
            Log.d(TAG, "onResume: currentActivity is null")
            return
        }
        if (!_isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled")
            return
        }

        if (_isInterstitialShowing) {
            Log.d(TAG, "onResume: interstitial is showing")
            return
        }

        if (disableAdResumeByClickAction) {
            Log.d(TAG, "onResume: ad resume disable ad by action")
            disableAdResumeByClickAction = false
            return
        }

        for (activity in disabledAppOpenList) {
            if (activity.name == currentActivity.javaClass.name) {
                Log.d(TAG, "onResume: activity is disabled")
                return
            }
        }

        Log.d(TAG, "onResume: show resume ads :${currentActivity.javaClass.simpleName}")
        if (adResumePreShowListener != null) {
            adResumePreShowListener?.onPreShowAd()
        } else {
            showAdIfAvailable()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "onStop: app stop")
    }

    private fun getCurrentActivity(): Activity? {
        return try {
            currentActivityRef?.get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val TAG = "AppOpenManager"

        @Volatile
        private var INSTANCE: AppOpenManager? = null

        private var isShowingAd = false

        
        fun getInstance(): AppOpenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppOpenManager().also { INSTANCE = it }
            }
        }
    }
}
