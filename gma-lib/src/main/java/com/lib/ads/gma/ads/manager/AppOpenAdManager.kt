package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.AppOpenAdListener
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Owns explicit splash app-open loading/showing. App resume ads stay in AppOpenManager. */
object AppOpenAdManager {
    private const val AD_TRANSITION_COVER_DELAY = 250L
    private const val SPLASH_POLL_INTERVAL_MS = 150L
    private const val TAG = "AppOpenAdManager"
    private const val DEFAULT_TAG = "__splash_app_open__"
    private var openAdLoaded: AppOpenAd? = null
    private var timeoutRunnable: Runnable? = null
    private var readyRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private var loadingAdsDialog: PrepareLoadingAdsDialog? = null
    private var showing = false

    fun isSplashAdLoaded(): Boolean = openAdLoaded != null

    fun loadSplashAppOpenAd(
        id: String,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
        tag: String = DEFAULT_TAG,
    ) = loadSplashAppOpenAds(listOf(id), timeOut, timeDelay, listener, skipUninitializedAdapters, placementId, tag)

    /**
     * Loads a waterfall into our tag-owned cache. The NextGen SDK preloader is intentionally not
     * used here: callers must be able to decide exactly which cached placement to show.
     */
    fun loadSplashAppOpenAds(
        ids: List<String>,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
        tag: String = DEFAULT_TAG,
    ) = whenAdsReady {
        clearTimers()
        openAdLoaded = null
        val appContext = AdsProvider.getInstance().applicationContextOrNull()
        if (ids.isEmpty() || appContext == null || !canRequestFullScreenAds(appContext, true)) {
            listener.onNextAction()
            return@whenAdsReady
        }
        if (AppPurchase.getInstance().isPurchased()) {
            listener.onNextAction()
            return@whenAdsReady
        }

        val startedAt = System.currentTimeMillis()
        var finished = false
        val handler = Handler(Looper.getMainLooper())

        timeoutRunnable = Runnable {
            finished = true
            openAdLoaded = null
            listener.onNextAction()
        }
        if (timeOut > 0) handler.postDelayed(timeoutRunnable!!, timeOut)

        fun onAdReady(ad: AppOpenAd) {
            if (finished) return
            openAdLoaded = ad
            FullScreenAdLruCache.put(tag, ad)
            val remaining = timeDelay - (System.currentTimeMillis() - startedAt)
            readyRunnable = Runnable { if (!finished) listener.onReady() }
            handler.postDelayed(readyRunnable!!, remaining.coerceAtLeast(0L))
        }

        val poll = Runnable {
            if (finished) return@Runnable
            fun load(index: Int) {
                if (index >= ids.size) { if (!finished) listener.onNextAction(); return }
                AppOpenAd.load(AdsManager.getAdRequest(ids[index], placementId, skipUninitializedAdapters), object : AdLoadCallback<AppOpenAd> {
                    override fun onAdLoaded(ad: AppOpenAd) = onAdReady(ad)
                    override fun onAdFailedToLoad(adError: LoadAdError) { load(index + 1) }
                })
            }
            load(0)
        }
        pollRunnable = poll
        poll.run()
    }

    private fun showLoadingDialog(activity: Activity) {
        try {
            dismissLoadingDialog()
            loadingAdsDialog = PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error showing loading dialog: ${e.message}")
        }
    }

    private fun dismissLoadingDialog() {
        try {
            loadingAdsDialog?.takeIf { it.isShowing }?.dismiss()
            loadingAdsDialog = null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error dismissing loading dialog: ${e.message}")
        }
    }

    private fun showAd(
        activity: Activity,
        appOpenAd: AppOpenAd,
        oneShotCallback: AppOpenAdListener? = null,
        waitingDialog: Dialog? = null,
    ) {

        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val adUnitId = appOpenAd.adUnitId
        if (activity.isFinishing || activity.isDestroyed) {
            AppLogger.w(TAG, "showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            oneShotCallback?.onNextAction()
            return
        }
        if (waitingDialog == null) showLoadingDialog(activity)
        appOpenAd.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
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
                    oneShotCallback?.onDismissed()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                scope.launch {
                    AppLogger.w(
                        TAG,
                        "onAdFailedToShowFullScreenContent: ${fullScreenContentError.message}"
                    )
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
                    oneShotCallback?.onClicked()
                }
            }

            override fun onAdImpression() {
                scope.launch {
                    AppLogger.w(TAG, "onAdImpression")
                    AdsLogEventManager.onTrackImpression(activity)
                    oneShotCallback?.onImpression()
                }
            }

            override fun onAdPaid(value: AdValue) {
                scope.launch {
                    AppLogger.w(TAG, "onAdPaid: ${value.valueMicros}")
                    AdsLogEventManager.logPaidAdImpression(
                        activity,
                        value,
                        appOpenAd.getResponseInfo(),
                        AdType.INTERSTITIAL
                    )
                    oneShotCallback?.onPaid(value)
                }
            }
        }
        scope.launch {
            delay(800.milliseconds)
            if (activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
                AppLogger.w(TAG, "showAd: Activity is no longer able to show an interstitial")
                waitingDialog.dismissSafely()
                AdsProvider.getInstance().setFullScreenAdShowing(false)
                dismissLoadingDialog()
                oneShotCallback?.onNextAction()
                return@launch
            }
            try {
                oneShotCallback?.onNextAction()
                appOpenAd.show(activity)
            } catch (e: Exception) {
                AppLogger.w(TAG, "showAd: failed to show interstitial ad: ${e.message}")
                waitingDialog.dismissSafely()
                AdsProvider.getInstance().setFullScreenAdShowing(false)
                dismissLoadingDialog()
                oneShotCallback?.onFailedToShow(ApAdError(e.message ?: "Failed to show ad"))
            }
        }
    }


    fun checkShowSplashWhenFail(activity: Activity, listener: AppOpenAdListener, delayMs: Long) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(delayMs.milliseconds)
            if (openAdLoaded != null && !showing) {
                val ad = openAdLoaded!!
                openAdLoaded = null
                FullScreenAdLruCache.clear(DEFAULT_TAG)
                showAd(activity, appOpenAd = ad, listener)
            } else listener.onNextAction()
        }
    }

    /** Shows the ad loaded by [loadSplashAppOpenAd]/[loadSplashAppOpenAds]; if none is ready (called too early, load failed, or already shown), continues the flow via [AppOpenAdListener.onNextAction] instead of crashing. */
    fun showSplashAppOpen(activity: Activity, listener: AppOpenAdListener, tag: String = DEFAULT_TAG) {
        val ad = FullScreenAdLruCache.pollAppOpen(tag) ?: openAdLoaded ?: run { listener.onNextAction(); return }
        openAdLoaded = null
        showAd(activity, appOpenAd = ad, listener, loadingAdsDialog)
    }



    fun cancelSplash() {
        clearTimers()
        openAdLoaded = null
        FullScreenAdLruCache.clear(DEFAULT_TAG)
        showing = false
    }

    private fun finishShow(dialog: android.app.Dialog?) {
        openAdLoaded = null
        showing = false
        dialog.dismissSafely()
        AdsProvider.getInstance().setFullScreenAdShowing(false)
    }

    private fun clearTimers() {
        val handler = Handler(Looper.getMainLooper())
        timeoutRunnable?.let(handler::removeCallbacks)
        readyRunnable?.let(handler::removeCallbacks)
        pollRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        readyRunnable = null
        pollRunnable = null
    }
}
