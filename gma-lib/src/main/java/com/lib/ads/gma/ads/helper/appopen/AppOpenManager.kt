package com.lib.ads.gma.ads.helper.appopen

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Window
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.ResumeLoadingDialog
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.listener.AdResumePreShowListener
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.util.AppLogger
import java.lang.ref.WeakReference

class AppOpenManager private constructor() : Application.ActivityLifecycleCallbacks,
    DefaultLifecycleObserver {

    private var appOpenAdEventCallback: AppOpenAdEventCallback? = null
    private var appResumeAdId: String? = null
    private var appResumeAdIdList: List<String>? = null
    private var placementId: Long? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var myApplication: Application? = null
    private var isInitialized: Boolean = false
    var isAppResumeEnabled: Boolean = true
        private set
    var isInterstitialShowing: Boolean = false
    private var enableScreenContentCallback: Boolean = false
    private var disableAdResumeByClickAction: Boolean = false
    private val disabledAppOpenList: MutableList<Class<*>> = ArrayList()

    /** Ids for which [AppOpenAdPreloader.start] has already been called, with their start time. */
    private val preloadStartedAt: MutableMap<String, Long> = linkedMapOf()

    /** A matched app-open ad handed over by another flow (e.g. a splash ad that arrived late). */
    private var spareAd: AppOpenAd? = null
    private var spareAdLoadedAt: Long = 0L
    private var hasBeenInBackground: Boolean = false
    private var dialog: Dialog? = null
    private var isEnableList: Boolean = false
    private var adResumePreShowListener: AdResumePreShowListener? = null
    private var eligibilityGate: (() -> Boolean)? = null

    companion object {
        private const val TAG = "AppOpenManager"
        private const val AD_TRANSITION_COVER_DELAY_MS = 250L

        /** Only one resume ad can be shown per foreground, so never buffer more than one. */
        private const val RESUME_PRELOAD_BUFFER_SIZE = 1

        /** Next waterfall id is preloaded only when the started ones still have no fill after this. */
        private const val FALLBACK_PRELOAD_AFTER_MS = 30_000L

        /** AdMob app-open ads expire 4 hours after load. */
        private const val SPARE_AD_MAX_AGE_MS = 4 * 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: AppOpenManager? = null

        @JvmStatic
        var isShowingAd: Boolean = false
            internal set

        @JvmStatic
        fun getInstance(): AppOpenManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppOpenManager().also { INSTANCE = it }
            }
    }

    /**
     * Registers this singleton for activity/process lifecycle callbacks the first time it's
     * called; a later call only updates the resume ad id(s). `Application.registerActivityLifecycleCallbacks`
     * has no dedup — calling [init] again without this guard would register the same callback
     * instance twice and fire every lifecycle event (and resume-ad load/show attempt) twice.
     *
     * [maxAdAgeHours] is kept for source/binary compatibility but is no longer read: ad freshness
     * (the AdMob 4-hour policy window) is now owned by [AppOpenAdPreloader] internally, the same
     * way it already is for interstitial/rewarded preloading elsewhere in this library.
     */
    fun init(application: Application, appOpenAdId: String, maxAdAgeHours: Int = 4, placementId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { init(application, appOpenAdId, maxAdAgeHours, placementId) }; return }
        val alreadyRegistered = isInitialized
        isInitialized = true
        disableAdResumeByClickAction = false
        myApplication = application
        if (!alreadyRegistered) {
            myApplication!!.registerActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this as LifecycleObserver)
        }
        appResumeAdId = appOpenAdId
        this.placementId = placementId
        isEnableList = false
    }

    fun init(application: Application, appOpenAdIdList: List<String>, maxAdAgeHours: Int = 4, placementId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { init(application, appOpenAdIdList, maxAdAgeHours, placementId) }; return }
        val alreadyRegistered = isInitialized
        isInitialized = true
        disableAdResumeByClickAction = false
        myApplication = application
        if (!alreadyRegistered) {
            myApplication!!.registerActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this as LifecycleObserver)
        }
        this.appResumeAdIdList = appOpenAdIdList
        this.placementId = placementId
        isEnableList = true
    }

    fun isInitialized(): Boolean = isInitialized

    fun setInitialized(initialized: Boolean) {
        isInitialized = initialized
    }

    fun setEnableScreenContentCallback(enableScreenContentCallback: Boolean) {
        this.enableScreenContentCallback = enableScreenContentCallback
    }

    fun disableAdResumeByClickAction() {
        disableAdResumeByClickAction = true
    }

    fun disableAppResumeWithActivity(activityClass: Class<*>) {
        Log.d(TAG, "disableAppResumeWithActivity: ${activityClass.name}")
        disabledAppOpenList.add(activityClass)
    }

    fun enableAppResumeWithActivity(activityClass: Class<*>) {
        Log.d(TAG, "enableAppResumeWithActivity: ${activityClass.name}")
        disabledAppOpenList.remove(activityClass)
    }

    fun disableAppResume() {
        isAppResumeEnabled = false
    }

    fun enableAppResume() {
        isAppResumeEnabled = true
    }

    fun setAppResumeAdId(appResumeAdId: String) {
        this.appResumeAdId = appResumeAdId
    }

    fun getAppResumeAdId(): String? = appResumeAdId

    fun setAppResumeAdIdList(appResumeAdIdList: List<String>) {
        this.appResumeAdIdList = appResumeAdIdList
    }

    fun getAppResumeAdIdList(): List<String>? = appResumeAdIdList

    fun setFullScreenContentCallback(callback: AppOpenAdEventCallback) {
        appOpenAdEventCallback = callback
    }

    fun removeFullScreenContentCallback() {
        appOpenAdEventCallback = null
    }

    fun isAdAvailable(): Boolean {
        val available = hasFreshSpareAd() ||
            resumeAdIds().any { it in preloadStartedAt && AppOpenAdPreloader.isAdAvailable(it) }
        AppLogger.d(TAG, "isAdAvailable => $available")
        return available
    }

    /** Compatibility overload; splash availability is now owned by AppOpenAdHelper. */
    @Deprecated("Splash app-open is handled by AppOpenAdHelper/AppOpenAdManager")
    fun isAdAvailable(isSplash: Boolean): Boolean = if (isSplash) false else isAdAvailable()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // no-op
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        Log.d(TAG, "onActivityStarted: $activity")
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        Log.d(TAG, "onActivityResumed: $activity")
    }

    override fun onActivityStopped(activity: Activity) {
        // no-op
    }

    override fun onActivityPaused(activity: Activity) {
        // no-op
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
        // no-op
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
            Log.d(TAG, "onActivityDestroyed: cleared ref for ${activity.javaClass.simpleName}")
        }
    }

    fun showAdIfAvailable() {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { showAdIfAvailable() }; return }
        val currentActivity = getCurrentActivity()
        if (currentActivity == null || AppPurchase.getInstance().isPurchased()) {
            if (appOpenAdEventCallback != null && enableScreenContentCallback) {
                appOpenAdEventCallback!!.onAdDismissedFullScreenContent()
            }
            return
        }
        if (currentActivity.isFinishing || currentActivity.isDestroyed) {
            Log.d(TAG, "showAdIfAvailable: Activity can no longer show an ad")
            return
        }
        Log.d(TAG, "showAdIfAvailable: ${ProcessLifecycleOwner.get().lifecycle.currentState}")
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.d(TAG, "showAdIfAvailable: return")
            if (appOpenAdEventCallback != null && enableScreenContentCallback) {
                appOpenAdEventCallback!!.onAdDismissedFullScreenContent()
            }
            return
        }
        if (isShowingAd || eligibilityGate?.invoke() == false) return
        if (isAdAvailable()) showResumeAds() else startFallbackPreload()
    }

    fun setAdResumePreShowListener(listener: AdResumePreShowListener) {
        adResumePreShowListener = listener
    }

    /**
     * Registers a hook the app can use to opt out of showing the resume ad for a given user —
     * typically to skip app-open ads during a new user's first N sessions (Google recommends not
     * interrupting a first-time user with a full-screen ad before they've seen the app's value;
     * showing too early risks D1 retention). `gma-lib` deliberately does not track "is this a new
     * user" itself — the app owns that definition (session count, install date, ...) and reports
     * it back via [gate]. While the gate is closed no resume ad is preloaded either, so a user
     * who can't see the ad doesn't burn matched requests.
     * Pass `null` (the default) to always allow showing, restoring prior behavior.
     */
    fun setEligibilityGate(gate: (() -> Boolean)?) {
        eligibilityGate = gate
    }

    internal fun hideDialogLoading() {
        val d = dialog ?: return
        dialog = null
        Handler(Looper.getMainLooper()).post {
            try {
                if (!d.isShowing) return@post
                val window: Window? = d.window
                if (window != null) {
                    val decorView = window.decorView
                    if (!decorView.isAttachedToWindow) return@post
                    val ctx = d.context
                    if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return@post
                    d.dismiss()
                }
            } catch (_: IllegalArgumentException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    internal fun showDialogLoading() {
        val currentActivity = getCurrentActivity()
        try {
            if (dialog == null) {
                dialog = currentActivity?.let { ResumeLoadingDialog(it as Context) }
            }
            dialog?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (!hasBeenInBackground) {
            Log.d(TAG, "onStart: initial app start; skip app-open resume")
            return
        }
        hasBeenInBackground = false
        val currentActivity = getCurrentActivity()
        if (!isInitialized) {
            Log.d(TAG, "onResume: app not initialized")
            return
        }
        if (currentActivity == null) {
            Log.d(TAG, "onResume: currentActivity is null")
            return
        }
        if (!isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled")
            return
        }
        if (isInterstitialShowing) {
            Log.d(TAG, "onResume: interstitial is showing")
            return
        }
        if (disableAdResumeByClickAction) {
            Log.d(TAG, "onResume:ad resume disable ad by action")
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
            adResumePreShowListener!!.onPreShowAd()
        } else {
            showAdIfAvailable()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        hasBeenInBackground = true
        Log.d(TAG, "onStop: app stop")
        // Resume ads can only be shown after the app has been in the background, so preloading
        // starts here rather than at app launch: a user who never leaves the app never loads one.
        loadAppOpenResume()
    }

    private fun resumeAdIds(): List<String> =
        (if (isEnableList) appResumeAdIdList.orEmpty() else listOfNotNull(appResumeAdId))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun canPreloadResumeAd(): Boolean {
        val app = myApplication ?: return false
        return isAppResumeEnabled && eligibilityGate?.invoke() != false && canRequestFullScreenAds(app, true)
    }

    /**
     * Starts [AppOpenAdPreloader] for the first resume id only, with a one-ad buffer. The preloader
     * refills itself after every poll and when the ad expires; fallback ids are added lazily by
     * [startFallbackPreload] so a waterfall doesn't keep one buffered ad per id.
     */
    private fun loadAppOpenResume() {
        AdsProvider.getInstance().runWhenReady {
            if (AppPurchase.getInstance().isPurchased()) {
                stopResumePreloads()
                return@runWhenReady
            }
            if (!canPreloadResumeAd()) return@runWhenReady
            resumeAdIds().firstOrNull()?.let(::startPreload)
        }
    }

    /** Called when a resume had nothing to show: adds the next id once the started ones had time to fill. */
    private fun startFallbackPreload() {
        if (!canPreloadResumeAd()) return
        val now = SystemClock.elapsedRealtime()
        val ids = resumeAdIds()
        val started = ids.filter { it in preloadStartedAt }
        if (started.isEmpty()) {
            ids.firstOrNull()?.let(::startPreload)
            return
        }
        val latestStart = started.maxOf { preloadStartedAt.getValue(it) }
        if (now - latestStart < FALLBACK_PRELOAD_AFTER_MS) return
        ids.firstOrNull { it !in preloadStartedAt }?.let(::startPreload)
    }

    private fun startPreload(id: String) {
        if (id in preloadStartedAt) return
        preloadStartedAt[id] = SystemClock.elapsedRealtime()
        AppOpenAdPreloader.start(
            id,
            PreloadConfiguration(AdsManager.getAdRequest(id, placementId), RESUME_PRELOAD_BUFFER_SIZE),
        )
    }

    private fun stopResumePreloads() {
        preloadStartedAt.keys.forEach(AppOpenAdPreloader::destroy)
        preloadStartedAt.clear()
        spareAd?.destroy()
        spareAd = null
    }

    /**
     * Keeps an app-open ad that was loaded for another flow but never shown (a splash ad that
     * arrived after the splash timed out) so the next resume shows it instead of wasting the fill.
     */
    internal fun offerResumeAd(ad: AppOpenAd) {
        if (Looper.myLooper() != Looper.getMainLooper()) { AdsProvider.MAIN.post { offerResumeAd(ad) }; return }
        if (!isInitialized || AppPurchase.getInstance().isPurchased()) {
            ad.destroy()
            return
        }
        spareAd?.destroy()
        spareAd = ad
        spareAdLoadedAt = SystemClock.elapsedRealtime()
    }

    private fun hasFreshSpareAd(): Boolean {
        val ad = spareAd ?: return false
        if (SystemClock.elapsedRealtime() - spareAdLoadedAt < SPARE_AD_MAX_AGE_MS) return true
        ad.destroy()
        spareAd = null
        return false
    }

    /** Consumes the spare ad first, then the first started id that has a ready ad, in waterfall order. */
    private fun pollReadyAd(): AppOpenAd? {
        if (hasFreshSpareAd()) return spareAd.also { spareAd = null }
        return resumeAdIds()
            .filter { it in preloadStartedAt }
            .firstNotNullOfOrNull(AppOpenAdPreloader::pollAd)
    }

    private fun showResumeAds() {
        val activity = getCurrentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "showResumeAds: Activity can no longer show an ad")
            return
        }
        if (AppPurchase.getInstance().isPurchased()) return
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Checked before pollReadyAd(): polling consumes an ad from AppOpenAdPreloader's pool, so
        // bailing out first avoids draining a buffered ad for a show that was never going to happen.
        val ad = pollReadyAd() ?: return
        try { hideDialogLoading(); showDialogLoading() } catch (e: Exception) { e.printStackTrace() }
        val adUnitId = ad.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                isShowingAd = false; hideDialogLoading()
                AdsProvider.getInstance().setFullScreenAdShowing(false)
                loadAppOpenResume()
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                isShowingAd = false; hideDialogLoading()
                AdsProvider.getInstance().setFullScreenAdShowing(false)
                loadAppOpenResume()
            }
            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
                Handler(Looper.getMainLooper()).postDelayed({ hideDialogLoading() }, AD_TRANSITION_COVER_DELAY_MS)
                AdsProvider.getInstance().setFullScreenAdShowing(true)
            }
            override fun onAdClicked() { AdsLogEventManager.logClickAdsEvent(activity, adUnitId) }
            override fun onAdImpression() { AdsLogEventManager.onTrackImpression(activity) }
            override fun onAdPaid(value: AdValue) {
                AdsLogEventManager.logPaidAdImpression(activity, value, ad.getResponseInfo(), AdType.APP_OPEN)
            }
        }
        ad.show(activity)
    }

    internal fun getCurrentActivity(): Activity? =
        try {
            currentActivityRef?.get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
}
