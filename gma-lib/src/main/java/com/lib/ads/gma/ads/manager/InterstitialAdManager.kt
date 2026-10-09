package com.lib.ads.gma.ads.manager

import android.app.Activity
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
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.canRequestFullScreenAds
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdHelper.Companion.AD_TRANSITION_COVER_DELAY
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

    // Loading-dialog state shared by the splash and regular show flows.
    private var loadingAdsDialog: PrepareLoadingAdsDialog? = null
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
        InterstitialAd.load(AdsManager.getAdRequest(id, placementId), object : AdLoadCallback<InterstitialAd> {
            override fun onAdLoaded(ad: InterstitialAd) {
                listener.onLoaded(ApInterstitialAd(ad))
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                listener.onFailed(ApAdError(adError))
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
        if (ad.interstitialAd == null || ad.isNotReady()) {
            Log.e(TAG, "forceShowInterstitial: ad is not ready"); listener.onFailedToShow(
                ApAdError(
                    "Interstitial not ready"
                )
            ); return
        } else {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                runCatching {
                    showAd(activity, ad.interstitialAd!!, listener, loadingAdsDialog)
                }
            }
        }
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

    private suspend fun showAd(
        activity: Activity,
        interstitialAd: InterstitialAd,
        oneShotCallback: InterstitialAdListener? = null,
        waitingDialog: Dialog? = null,
    ) {

        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val adUnitId = interstitialAd.adUnitId
        if (activity.isFinishing || activity.isDestroyed) {
            AppLogger.w(TAG, "showAd: Activity can no longer show an ad")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            oneShotCallback?.onNextAction()
            return
        }
        if (waitingDialog == null) showLoadingDialog(activity)
        interstitialAd.adEventCallback = object : InterstitialAdEventCallback {
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
                    oneShotCallback?.onDismissed(ApInterstitialAd(interstitialAd))
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
                        interstitialAd.getResponseInfo(),
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

        delay(800.milliseconds)
        if (activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            AppLogger.w(TAG, "showAd: Activity is no longer able to show an interstitial")
            waitingDialog.dismissSafely()
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            dismissLoadingDialog()
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
        if (ad?.interstitialAd == null) { listener.onNextAction(); return }
        showInterstitialAdInternal(activity, ad.interstitialAd!!, null, listener)
    }


    private fun showInterstitialAdInternal(
        activity: Activity,
        ad: InterstitialAd,
        waitingDialog: Dialog?,
        listener: InterstitialAdListener
    ) = CoroutineScope(Dispatchers.Main.immediate).launch {
        if (waitingDialog == null) runCatching {
            loadingAdsDialog?.dismiss(); loadingAdsDialog =
            PrepareLoadingAdsDialog(activity); loadingAdsDialog?.setCancelable(
            false
        ); loadingAdsDialog?.show()
        }.onFailure {
            listener.onFailedToShow(ApAdError(it.message ?: "Unable to show loading dialog"))
            return@onFailure
        }
        if (activity.isFinishing || activity.isDestroyed) {
            dismissShowDialog(activity, waitingDialog)
            listener.onFailedToShow(ApAdError("Activity can no longer show an interstitial"))
            return@launch
        }
        showAd(activity, ad, listener, waitingDialog)
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
        clearSplashTimers()
        isTimeDelay = false; isTimeout = false
        val appContext = AdsProvider.getInstance().applicationContextOrNull()
        if (appContext == null || !canRequestFullScreenAds(appContext, true)) {
            listener.onNextAction(); return@whenAdsReady
        }
        handlerTimeDelay = Handler(Looper.getMainLooper())
        val delay =
            Runnable { if (interstitialSplash != null) listener.onReady() else isTimeDelay = true }
        rdTimeDelay = delay; handlerTimeDelay?.postDelayed(delay, timeDelay)
        if (timeOut > 0) {
            handlerTimeout = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                isTimeout =
                    true; if (interstitialSplash != null) listener.onReady() else listener.onNextAction()
            }
            rdTimeout = timeout; handlerTimeout?.postDelayed(timeout, timeOut)
        }
        loadInterstitialAdRaw(id, object : InterstitialAdListener {
            override fun onLoaded(ad: ApInterstitialAd) {
                interstitialSplash = ad.interstitialAd
                FullScreenAdLruCache.put(SPLASH_TAG, ad)
                clearSplashTimeout()
                if (isTimeout || isTimeDelay) listener.onReady()
            }

            override fun onFailed(error: ApAdError) {
                if (!isTimeout) listener.onFailed(error)
            }
        })
    }

    fun loadSplashListAds(
        listId: List<String>,
        timeOut: Long,
        timeDelay: Long,
        listener: InterstitialAdListener
    ) = whenAdsReady {
        clearSplashTimers()
        isTimeDelay = false; isTimeout = false
        val appContext = AdsProvider.getInstance().applicationContextOrNull()
        if (appContext == null || !canRequestFullScreenAds(appContext, true)) {
            listener.onNextAction(); return@whenAdsReady
        }
        handlerTimeDelay = Handler(Looper.getMainLooper())
        val delay =
            Runnable { if (interstitialSplash != null) listener.onReady() else isTimeDelay = true }
        rdTimeDelay = delay
        handlerTimeDelay?.postDelayed(delay, timeDelay)
        if (timeOut > 0) {
            handlerTimeout = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                isTimeout =
                    true; if (interstitialSplash != null) listener.onReady() else listener.onNextAction()
            }
            rdTimeout = timeout; handlerTimeout?.postDelayed(timeout, timeOut)
        }
        loadNextAdSplashList(listId, 0, listener)
    }

    private fun loadNextAdSplashList(
        ids: List<String>,
        index: Int,
        listener: InterstitialAdListener
    ) {
        if (index >= ids.size) {
            listener.onFailed(ApAdError("All ad IDs failed.")); return
        }
        loadInterstitialAdRaw(ids[index], object : InterstitialAdListener {
            override fun onLoaded(ad: ApInterstitialAd) {
                interstitialSplash = ad.interstitialAd
                FullScreenAdLruCache.put(SPLASH_TAG, ad)
                clearSplashTimeout()
                if (isTimeout || isTimeDelay) listener.onReady()
            }

            override fun onFailed(error: ApAdError) {
                loadNextAdSplashList(ids, index + 1, listener)
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
                loadingAdsDialog
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
        AdsProvider.runOnMain {
            if (loadingAdsDialog?.isShowing == true && !activity.isFinishing && !activity.isDestroyed) {
                runCatching { loadingAdsDialog?.dismiss() }
                loadingAdsDialog = null
            }
        }
    }
}
