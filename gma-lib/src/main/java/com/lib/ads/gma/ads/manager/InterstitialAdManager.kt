package com.lib.ads.gma.ads.manager

import android.app.Activity
import com.lib.ads.gma.ads.helper.fullscreen.awaitResumeAdClosed
import com.lib.ads.gma.ads.helper.appopen.AppOpenManager
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.helper.fullscreen.LoadingDialogSlot
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdHealthEvents
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdHelper.Companion.AD_TRANSITION_COVER_DELAY
import com.lib.ads.gma.ads.helper.interstitial.InterstitialFrequencyCap
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.*
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns interstitial ad loading/showing, including the splash-interstitial flow (a splash screen
 * is just an interstitial with extra load-timing rules) — matches adlib's InterstitialAdManager.
 * [AdsProvider] itself only initializes the SDK and holds config; every per-format concern lives here.
 */
object InterstitialAdManager {
    private const val TAG = "InterstitialAdManager"
    private const val SPLASH_TAG = "__splash_interstitial__"
    private var handlerTimeout: Handler? = null
    private var handlerTimeDelay: Handler? = null
    private var rdTimeout: Runnable? = null
    private var rdTimeDelay: Runnable? = null
    private var isTimeout: Boolean = false
    private var isTimeDelay: Boolean = false
    private var interstitialSplash: InterstitialAd? = null
    private var splashGeneration = 0
    private var splashFinished = true

    // Loading-dialog state shared by the splash and regular show flows; released with its Activity.
    private val loadingDialog = LoadingDialogSlot()
    private var isShowLoadingSplash: Boolean = false

    fun loadInterstitialAdRaw(id: String, listener: InterstitialAdListener, placementId: Long? = null) {
        AdsProvider.getInstance().applicationContextOrNull()?.let { context ->
            if (context.resources.getStringArray(com.lib.ads.gma.gma.R.array.list_id_test).contains(id)) {
                AdsManager.showTestIdAlert(context, AdsManager.INTERS_ADS, id)
            }
        }
        if (AppPurchase.getInstance().isPurchased()) {
            listener.onFailed(ApAdError("App is purchased")); return
        }
        // The Next-Gen SDK calls load callbacks on a background thread; listeners (and the splash
        // flow's timer state below) are main-thread only.
        val startedAt = AdHealthEvents.now()
        InterstitialAd.load(AdsManager.getAdRequest(id, placementId), object : AdLoadCallback<InterstitialAd> {
            override fun onAdLoaded(ad: InterstitialAd) {
                AdHealthEvents.loadFinished(AdType.INTERSTITIAL, id, startedAt, null)
                AdsProvider.runOnMain { listener.onLoaded(ApInterstitialAd(ad)) }
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                AdHealthEvents.loadFinished(AdType.INTERSTITIAL, id, startedAt, adError)
                AdsProvider.runOnMain { listener.onFailed(ApAdError(adError)) }
            }
        })
    }

    fun getInterstitialAds(id: String, listener: InterstitialAdListener, placementId: Long? = null) =
        whenAdsReady { loadInterstitialAdRaw(id, listener, placementId) }

    fun getInterstitialAdsList(
        tag: String,
        ids: List<String>?,
        listener: InterstitialAdListener,
        placementId: Long? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AdsProvider.MAIN.post { getInterstitialAdsList(tag, ids, listener, placementId) }; return
        }
        val list = ids.orEmpty()
        if (list.isEmpty()) {
            listener.onFailed(ApAdError("list id is null or empty")); return
        }
        fun load(index: Int) {
            getInterstitialAds(list[index], object : InterstitialAdListener {
                override fun onLoaded(ad: ApInterstitialAd): Unit {
                    FullScreenAdLruCache.put(tag, ad)
                    listener.onLoaded(ad)
                }
                override fun onFailed(error: ApAdError) {
                    if (index + 1 < list.size) load(index + 1) else listener.onFailed(error)
                }
            }, placementId)
        }
        load(0)
    }

    fun forceShowInterstitial(
        activity: Activity,
        ad: ApInterstitialAd,
        listener: InterstitialAdListener
    ) {
        val interstitialAd = ad.interstitialAd
        if (interstitialAd == null || ad.isNotReady()) {
            Log.e(TAG, "forceShowInterstitial: ad is not ready"); listener.onFailedToShow(
                ApAdError(
                    "Interstitial not ready"
                )
            ); return
        } else {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                runCatching {
                    showAd(activity, interstitialAd, listener, loadingDialog.current)
                }
            }
        }
    }

    private fun showLoadingDialog(activity: Activity) = loadingDialog.show(activity)

    private fun dismissLoadingDialog() = loadingDialog.dismiss()

    private suspend fun showAd(
        activity: Activity,
        interstitialAd: InterstitialAd,
        oneShotCallback: InterstitialAdListener? = null,
        waitingDialog: Dialog? = null,
        /** Puts the never-shown ad back where it came from, so it is not lost. */
        onDropped: (() -> Unit)? = null,
    ) {

        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val adUnitId = interstitialAd.adUnitId
        if (activity.isFinishing || activity.isDestroyed) {
            AppLogger.w(TAG, "showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            onDropped?.invoke()
            oneShotCallback?.onNextAction()
            return
        }
        if (waitingDialog == null) showLoadingDialog(activity)
        interstitialAd.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                InterstitialFrequencyCap.markShown()
                scope.launch {
                    AppLogger.w(TAG, "onAdShowedFullScreenContent")
                    AdsProvider.getInstance().setFullScreenAdShowing(true)
                    delay(AD_TRANSITION_COVER_DELAY.milliseconds)
                    waitingDialog.dismissSafely()
                    dismissLoadingDialog()
                }
            }

            override fun onAdDismissedFullScreenContent() {
                scope.launch {
                    AppLogger.w(TAG, "onAdDismissedFullScreenContent")
                    waitingDialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    oneShotCallback?.onDismissed(ApInterstitialAd(interstitialAd))
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                scope.launch {
                    AppLogger.w(
                        TAG,
                        "onAdFailedToShowFullScreenContent: ${fullScreenContentError.message}"
                    )
                    AdHealthEvents.showFailed(AdType.INTERSTITIAL, adUnitId, fullScreenContentError.message)
                    waitingDialog.dismissSafely()
                    AdsProvider.getInstance().setFullScreenAdShowing(false)
                    dismissLoadingDialog()
                    oneShotCallback?.onFailedToShow(ApAdError(fullScreenContentError))
                    oneShotCallback?.onNextAction()
                }
            }

            override fun onAdClicked() {
                scope.launch {
                    AppLogger.w(TAG, "onAdClicked")
                    AdsLogEventManager.logClickAdsEvent(activity, adUnitId)
                    oneShotCallback?.onClicked(ApInterstitialAd(interstitialAd))
                }
            }

            override fun onAdImpression() {
                scope.launch {
                    AppLogger.w(TAG, "onAdImpression")
                    AdsLogEventManager.onTrackImpression(activity)
                    oneShotCallback?.onImpression(ApInterstitialAd(interstitialAd))
                }
            }

            override fun onAdPaid(value: AdValue) {
                scope.launch {
                    AppLogger.w(TAG, "onAdPaid: ${value.valueMicros}")
                    AdsLogEventManager.logPaidAdImpression(
                        activity,
                        value,
                        interstitialAd,
                        AdType.INTERSTITIAL
                    )
                    oneShotCallback?.onPaid(value)
                }
            }

            override fun onAppEvent(name: String, data: String?) {
                scope.launch {
                    oneShotCallback?.onAppEvent(name, data)
                }
            }
        }

        delay(AdsProvider.getInstance().interstitialShowDelayMs().milliseconds)
        // An app-open resume ad on screen: showing now would stack two full-screen ads, continuing at
        // once would bury it under the next screen. Wait for it to close, then skip this ad.
        val resumeAdOnScreen = AppOpenManager.isShowingAd
        if (resumeAdOnScreen) awaitResumeAdClosed()
        if (resumeAdOnScreen || activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            AppLogger.w(TAG, "showAd: Activity is no longer able to show an interstitial (resumeAdOnScreen=$resumeAdOnScreen)")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            onDropped?.invoke()
            oneShotCallback?.onNextAction()
            return
        }
        try {
            oneShotCallback?.onNextAction()
            interstitialAd.show(activity)
        } catch (e: Exception) {
            AppLogger.w(TAG, "showAd: failed to show interstitial ad: ${e.message}")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
            oneShotCallback?.onFailedToShow(ApAdError(e.message ?: "Failed to show ad"))
        }
    }


    fun loadInterstitial(tag: String, ids: List<String>, listener: InterstitialAdListener, placementId: Long? = null) =
        getInterstitialAdsList(tag, ids, listener, placementId)

    fun showInterstitial(activity: Activity, tag: String, listener: InterstitialAdListener) {
        val ad = FullScreenAdLruCache.pollInterstitial(tag)
        val interstitialAd = ad?.interstitialAd
        if (interstitialAd == null) { listener.onNextAction(); return }
        // The same wrapper goes back, so the cache's TTL still counts from the original load.
        showInterstitialAdInternal(activity, interstitialAd, null, listener) { FullScreenAdLruCache.put(tag, ad) }
    }


    private fun showInterstitialAdInternal(
        activity: Activity,
        ad: InterstitialAd,
        waitingDialog: Dialog?,
        listener: InterstitialAdListener,
        onDropped: (() -> Unit)? = null,
    ) = CoroutineScope(Dispatchers.Main.immediate).launch {
        // showAd() puts up the loading dialog itself when no waitingDialog is given; creating one
        // here too showed two dialogs in a row.
        if (activity.isFinishing || activity.isDestroyed) {
            dismissShowDialog(activity, waitingDialog)
            onDropped?.invoke()
            listener.onFailedToShow(ApAdError("Activity can no longer show an interstitial"))
            return@launch
        }
        showAd(activity, ad, listener, waitingDialog, onDropped)
    }

    private fun dismissShowDialog(activity: Activity, waitingDialog: Dialog?) {
        if (waitingDialog != null) waitingDialog.dismissSafely() else safeDismissDialog(activity)
    }

    fun interstitialSplashLoaded(): Boolean = interstitialSplash != null

    private fun clearSplashTimeout() {
        rdTimeout?.let { handlerTimeout?.removeCallbacks(it) }
        rdTimeout = null
        handlerTimeout = null
    }

    private fun clearSplashTimers() {
        clearSplashTimeout()
        rdTimeDelay?.let { handlerTimeDelay?.removeCallbacks(it) }
        rdTimeDelay = null
        handlerTimeDelay = null
    }

    fun loadSplashInterstitialAds(
        id: String,
        timeOut: Long,
        timeDelay: Long,
        listener: InterstitialAdListener
    ) = whenAdsReady {
        val generation = startSplash()
        val appContext = AdsProvider.getInstance().applicationContextOrNull()
        if (appContext == null || !canRequestFullScreenAds(appContext, true)) {
            finishSplash(generation) { listener.onNextAction() }; return@whenAdsReady
        }
        startSplashTimers(generation, timeOut, timeDelay, listener)
        loadInterstitialAdRaw(id, object : InterstitialAdListener {
            override fun onLoaded(ad: ApInterstitialAd) = onSplashLoaded(generation, ad, listener)

            override fun onFailed(error: ApAdError) {
                finishSplash(generation) { listener.onFailed(error) }
            }
        })
    }

    /** Resets the splash state for a new load and returns its generation (main thread). */
    private fun startSplash(): Int {
        clearSplashTimers()
        isTimeDelay = false; isTimeout = false
        splashFinished = false
        return ++splashGeneration
    }

    /**
     * Exactly one of onReady / onNextAction / onFailed reaches the listener per splash load, as
     * in AppOpenAdManager. A failed waterfall used to report onFailed and then, when the still
     * pending timeout fired, onNextAction too, so the caller continued twice.
     */
    private fun finishSplash(generation: Int, terminal: () -> Unit) {
        if (generation != splashGeneration || splashFinished) return
        splashFinished = true
        clearSplashTimers()
        terminal()
    }

    /** Timers shared by both splash entry points (main thread; load results are delivered there too). */
    private fun startSplashTimers(generation: Int, timeOut: Long, timeDelay: Long, listener: InterstitialAdListener) {
        handlerTimeDelay = Handler(Looper.getMainLooper())
        val delay = Runnable {
            if (isTimeout) return@Runnable
            if (interstitialSplash != null) finishSplash(generation) { listener.onReady() } else isTimeDelay = true
        }
        rdTimeDelay = delay
        handlerTimeDelay?.postDelayed(delay, timeDelay)
        if (timeOut > 0) {
            handlerTimeout = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                isTimeout = true
                finishSplash(generation) {
                    if (interstitialSplash != null) listener.onReady() else listener.onNextAction()
                }
            }
            rdTimeout = timeout
            handlerTimeout?.postDelayed(timeout, timeOut)
        }
    }

    private fun onSplashLoaded(generation: Int, ad: ApInterstitialAd, listener: InterstitialAdListener) {
        if (generation != splashGeneration) {
            // Fill from a splash load that a newer one replaced: nothing will show it.
            runCatching { ad.interstitialAd?.destroy() }
            return
        }
        interstitialSplash = ad.interstitialAd
        FullScreenAdLruCache.put(SPLASH_TAG, ad)
        // Timed out already: the flow went on via onNextAction. Keep the ad cached (for
        // onCheckShowSplashWhenFail) but do not report it as ready a second time.
        if (isTimeout) return
        clearSplashTimeout()
        if (isTimeDelay) finishSplash(generation) { listener.onReady() }
    }

    fun loadSplashListAds(
        listId: List<String>,
        timeOut: Long,
        timeDelay: Long,
        listener: InterstitialAdListener
    ) = whenAdsReady {
        val generation = startSplash()
        val appContext = AdsProvider.getInstance().applicationContextOrNull()
        if (appContext == null || !canRequestFullScreenAds(appContext, true)) {
            finishSplash(generation) { listener.onNextAction() }; return@whenAdsReady
        }
        startSplashTimers(generation, timeOut, timeDelay, listener)
        loadNextAdSplashList(generation, listId, 0, listener)
    }

    private fun loadNextAdSplashList(
        generation: Int,
        ids: List<String>,
        index: Int,
        listener: InterstitialAdListener
    ) {
        if (generation != splashGeneration) return
        if (index >= ids.size) {
            finishSplash(generation) { listener.onFailed(ApAdError("All ad IDs failed.")) }
            return
        }
        loadInterstitialAdRaw(ids[index], object : InterstitialAdListener {
            override fun onLoaded(ad: ApInterstitialAd) = onSplashLoaded(generation, ad, listener)

            override fun onFailed(error: ApAdError) {
                loadNextAdSplashList(generation, ids, index + 1, listener)
            }
        })
    }

    fun onShowSplash(activity: Activity, listener: InterstitialAdListener) {
        val ad = FullScreenAdLruCache.pollInterstitial(SPLASH_TAG)?.interstitialAd ?: interstitialSplash
        if (ad == null) {
            Log.d(TAG, "onShowSplash: no ad loaded -> onNextAction()")
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            listener.onNextAction()
            return
        }
        interstitialSplash = null
        CoroutineScope(Dispatchers.Main.immediate).launch {
            showAd(
                activity,
                ad,
                listener,
                loadingDialog.current
            )
        }
    }

    fun onCheckShowSplashWhenFail(
        activity: Activity,
        listener: InterstitialAdListener,
        timeDelay: Int
    ) {
        Handler(activity.mainLooper).postDelayed({
            if (interstitialSplashLoaded() && !isShowLoadingSplash) onShowSplash(
                activity,
                listener
            )
        }, timeDelay.toLong())
    }


    private fun safeDismissDialog(activity: Activity) {
        isShowLoadingSplash = false
        // Always release the reference: a finishing Activity used to leave it pinned here.
        AdsProvider.runOnMain { loadingDialog.dismiss() }
    }
}
