package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lib.ads.gma.gma.R
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.helper.fullscreen.dismissSafely
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.*
import com.lib.ads.gma.ads.util.runOnMain
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback

/** Owns rewarded / rewarded-interstitial ad loading & showing — matches adlib's RewardAdManager. */
object RewardAdManager {
    private const val REWARD_TRANSITION_COVER_DELAY_MS = 250L
    private const val TAG = "RewardAdManager"

    fun preloadRewardAd(id: String, placementId: Long? = null) = whenAdsReady {
        RewardedAdPreloader.start(id, PreloadConfiguration(AdsManager.getAdRequest(id, placementId)))
    }

    fun getRewardAdPreload(id: String): ApRewardAd? =
        RewardedAdPreloader.pollAd(id)?.let(::ApRewardAd)

    fun preloadRewardInterstitialAd(id: String, placementId: Long? = null) = whenAdsReady {
        RewardedInterstitialAdPreloader.start(
            id,
            PreloadConfiguration(AdsManager.getAdRequest(id, placementId)),
        )
    }

    fun getRewardInterstitialAdPreload(id: String): ApRewardAd? =
        RewardedInterstitialAdPreloader.pollAd(id)?.let(::ApRewardAd)

    /** Loads a single rewarded ad straight from the SDK (test-id alert + purchase gate + load call). */
    fun loadRewardAdRaw(id: String, listener: RewardAdListener, placementId: Long? = null) {
        Ads.getInstance().applicationContextOrNull()?.let { context ->
            if (context.resources.getStringArray(R.array.list_id_test).contains(id)) AdsManager.showTestIdAlert(context, AdsManager.REWARD_ADS, id)
        }
        if (AppPurchase.getInstance().isPurchased()) {
            listener.onFailed(ApAdError("App is purchased")); return
        }
        RewardedAd.load(AdsManager.getAdRequest(id, placementId), object : AdLoadCallback<RewardedAd> {
            override fun onAdLoaded(ad: RewardedAd) {
                listener.onLoaded(ApRewardAd(ad))
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                super.onAdFailedToLoad(adError)
                listener.onFailed(ApAdError(adError))
            }
        })
    }

    /** Loads a single rewarded-interstitial ad straight from the SDK. */
    fun loadRewardInterstitialAdRaw(
        id: String,
        listener: RewardAdListener,
        placementId: Long? = null,
        ssvCustomData: String? = null,
    ) {
        Ads.getInstance().applicationContextOrNull()?.let { context ->
            if (context.resources.getStringArray(R.array.list_id_test).contains(id)) AdsManager.showTestIdAlert(context, AdsManager.REWARD_ADS, id)
        }
        if (AppPurchase.getInstance().isPurchased()) {
            listener.onFailed(ApAdError("App is purchased")); return
        }
        RewardedInterstitialAd.load(AdsManager.getAdRequest(id, placementId), object : AdLoadCallback<RewardedInterstitialAd> {
            override fun onAdLoaded(ad: RewardedInterstitialAd) {
                ssvCustomData?.takeIf { it.isNotEmpty() }?.let {
                    ad.setServerSideVerificationOptions(ServerSideVerificationOptions("", it))
                }
                listener.onLoaded(ApRewardAd(ad))
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                listener.onFailed(ApAdError(adError))
            }
        })
    }

    fun loadRewardAd(id: String, listener: RewardAdListener, placementId: Long? = null) =
        whenAdsReady { loadRewardAdRaw(id, listener, placementId) }

    @Deprecated("Context is no longer required; use loadRewardAd(id, listener, placementId)")
    fun loadRewardAd(context: android.content.Context, id: String, listener: RewardAdListener, placementId: Long? = null) =
        loadRewardAd(id, listener, placementId)

    fun loadRewardAdList(tag: String, ids: List<String>?, listener: RewardAdListener, placementId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { Ads.MAIN.post { loadRewardAdList(tag, ids, listener, placementId) }; return }
        val list = ids.orEmpty()
        if (list.isEmpty()) { listener.onFailed(ApAdError("list id is null or empty")); return }
        fun load(index: Int) {
            loadRewardAd(list[index], listener = object : RewardAdListener {
                override fun onLoaded(ad: ApRewardAd): Unit {
                    FullScreenAdLruCache.put(tag, ad)
                    listener.onLoaded(ad)
                }
                override fun onFailed(error: ApAdError) { if (index + 1 < list.size) load(index + 1) else listener.onFailed(error) }
            }, placementId)
        }
        load(0)
    }

    @Deprecated("Use the overload that accepts a placement tag")
    fun loadRewardAdList(context: android.content.Context, ids: List<String>?, listener: RewardAdListener) =
        loadRewardAdList("reward:${ids.orEmpty().lastOrNull().orEmpty()}", ids, listener)

    fun loadRewardInterstitialAd(
        id: String,
        listener: RewardAdListener,
        placementId: Long? = null,
        ssvCustomData: String? = null,
    ) = whenAdsReady {
        loadRewardInterstitialAdRaw(id, listener, placementId, ssvCustomData)
    }

    @Deprecated("Context is no longer required; use loadRewardInterstitialAd(id, listener, placementId)")
    fun loadRewardInterstitialAd(context: android.content.Context, id: String, listener: RewardAdListener, placementId: Long? = null) =
        loadRewardInterstitialAd(id, listener, placementId)

    fun loadRewardedInterstitialAd(
        id: String,
        listener: RewardAdListener,
        placementId: Long? = null,
        ssvCustomData: String? = null,
    ) = loadRewardInterstitialAd(id, listener, placementId, ssvCustomData)

    fun preloadRewardInterstitialAd(
        preloadId: String,
        adUnitId: String,
        placementId: Long? = null,
    ) = whenAdsReady {
        RewardedInterstitialAdPreloader.start(
            preloadId,
            PreloadConfiguration(AdsManager.getAdRequest(adUnitId, placementId)),
        )
    }

    fun loadRewardInterstitialAdList(
        tag: String,
        ids: List<String>?,
        listener: RewardAdListener,
        placementId: Long? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Ads.MAIN.post { loadRewardInterstitialAdList(tag, ids, listener, placementId) }
            return
        }
        val list = ids.orEmpty()
        if (list.isEmpty()) {
            listener.onFailed(ApAdError("list id is null or empty"))
            return
        }
        fun load(index: Int) {
            loadRewardInterstitialAd(list[index], object : RewardAdListener {
                override fun onLoaded(ad: ApRewardAd) {
                    FullScreenAdLruCache.putRewardInterstitial(tag, ad)
                    listener.onLoaded(ad)
                }

                override fun onFailed(error: ApAdError) {
                    if (index + 1 < list.size) load(index + 1) else listener.onFailed(error)
                }
            }, placementId)
        }
        load(0)
    }

    fun forceShowRewardAd(activity: Activity, ad: ApRewardAd?, listener: RewardAdListener) {
        if (Looper.myLooper() != Looper.getMainLooper()) { Ads.MAIN.post { forceShowRewardAd(activity, ad, listener) }; return }
        if (ad == null || !ad.isReady()) { Log.e(TAG, "forceShowRewardAd: ad not ready"); listener.onNotReady(); return }
        showReward(activity, ad, null, listener)
    }

    fun loadReward(id: String, listener: RewardAdListener, placementId: Long? = null) =
        whenAdsReady { loadRewardAdRaw(id, listener, placementId) }

    /** Loads a rewarded waterfall into the placement-owned cache. */
    fun loadReward(tag: String, ids: List<String>, listener: RewardAdListener, placementId: Long? = null) =
        loadRewardAdList(tag, ids, listener, placementId)

    fun showReward(activity: Activity, tag: String, listener: RewardAdListener, waitingDialog: Dialog? = null) {
        val ad = FullScreenAdLruCache.pollReward(tag)
        if (ad == null) { listener.onNotReady(); return }
        showReward(activity, ad, null, listener, waitingDialog)
    }

    fun showReward(activity: Activity, ad: ApRewardAd, onNextAction: (() -> Unit)?, listener: RewardAdListener, waitingDialog: Dialog? = null) {
        showRewardAdRaw(activity, ad, object : RewardAdListener {
            override fun onLoaded(ad: ApRewardAd) = listener.onLoaded(ad)
            override fun onFailed(error: ApAdError) = listener.onFailed(error)
            override fun onShown(ad: ApRewardAd) = listener.onShown(ad)
            override fun onImpression(ad: ApRewardAd) = listener.onImpression(ad)
            override fun onRewarded(ad: ApRewardAd, item: ApRewardItem) = listener.onRewarded(ad, item)
            override fun onClicked(ad: ApRewardAd) = listener.onClicked(ad)
            override fun onDismissed(ad: ApRewardAd) { onNextAction?.invoke(); listener.onDismissed(ad) }
            override fun onFailedToShow(error: ApAdError) { listener.onFailedToShow(error); onNextAction?.invoke() }
            override fun onNotReady() { listener.onNotReady(); onNextAction?.invoke() }
        }, waitingDialog)
    }

    fun showRewardedInterstitialAd(
        activity: Activity,
        ad: ApRewardAd,
        listener: RewardAdListener,
        waitingDialog: Dialog? = null,
    ) {
        if (!ad.isRewardInterstitial()) {
            listener.onFailedToShow(ApAdError("Ad is not a rewarded interstitial"))
            return
        }
        showReward(activity, ad, null, listener, waitingDialog)
    }

    fun showRewardedInterstitialAd(
        activity: Activity,
        tag: String,
        listener: RewardAdListener,
        waitingDialog: Dialog? = null,
    ) {
        val ad = FullScreenAdLruCache.pollRewardInterstitial(tag)
        if (ad == null) {
            listener.onNotReady()
            return
        }
        showRewardedInterstitialAd(activity, ad, listener, waitingDialog)
    }

    /** Shows an already-loaded [ApRewardAd] (reward or reward-interstitial) and wires its full-screen callbacks. */
    fun showRewardAdRaw(activity: Activity, apAd: ApRewardAd, listener: RewardAdListener, waitingDialog: Dialog? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { Ads.MAIN.post { showRewardAdRaw(activity, apAd, listener, waitingDialog) }; return }
        if (!apAd.isReady()) {
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            listener.onNotReady()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            listener.onFailedToShow(ApAdError("Activity can no longer show a rewarded ad"))
            return
        }
        if (AppPurchase.getInstance().isPurchased()) {
            waitingDialog.dismissSafely()
            Ads.getInstance().setFullScreenAdShowing(false)
            listener.onFailedToShow(ApAdError("App is purchased"))
            return
        }
        if (apAd.isRewardInterstitial()) {
            val ad = apAd.rewardInterstitial!!
            val responseInfo = ad.getResponseInfo()
            val adUnitId = responseInfo.extractAdUnitIdOrNull().orEmpty()
            ad.adEventCallback =(object : RewardedInterstitialAdEventCallback {
                override fun onAdDismissedFullScreenContent() {
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    listener.onDismissed(apAd)
                }

                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    listener.onFailedToShow(ApAdError(fullScreenContentError.message))
                }

                override fun onAdShowedFullScreenContent() {
                    Ads.getInstance().setFullScreenAdShowing(true)
                    Handler(Looper.getMainLooper()).postDelayed({ waitingDialog.dismissSafely() }, REWARD_TRANSITION_COVER_DELAY_MS)
                    listener.onShown(apAd)
                }

                override fun onAdClicked() {
                    AdsLogEventManager.logClickAdsEvent(activity, adUnitId)
                    listener.onClicked(apAd)
                }

                override fun onAdImpression() {
                    AdsLogEventManager.onTrackImpression(activity)
                    listener.onImpression(apAd)
                }

                override fun onAdPaid(adValue: AdValue) {
                    AdsLogEventManager.logPaidAdImpression(activity, adValue, responseInfo, AdType.REWARDED)
                    listener.onPaid(adValue)
                }
                override fun onAdMetadataChanged() {
                    listener.onMetadataChanged()
                }
            })
            ad.show(activity) { rewardItem ->
                runOnMain { listener.onRewarded(apAd, ApRewardItem(rewardItem)) }
            }
        } else {
            val ad = apAd.rewardAd!!
            val responseInfo = ad.getResponseInfo()
            val adUnitId = responseInfo.extractAdUnitIdOrNull().orEmpty()
            ad.adEventCallback =(object : RewardedAdEventCallback {
                override fun onAdDismissedFullScreenContent() {
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    listener.onDismissed(apAd)
                }

                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                    waitingDialog.dismissSafely()
                    Ads.getInstance().setFullScreenAdShowing(false)
                    listener.onFailedToShow(ApAdError(fullScreenContentError.message))
                }

                override fun onAdShowedFullScreenContent() {
                    Ads.getInstance().setFullScreenAdShowing(true)
                    Handler(Looper.getMainLooper()).postDelayed({ waitingDialog.dismissSafely() }, REWARD_TRANSITION_COVER_DELAY_MS)
                    listener.onShown(apAd)
                }

                override fun onAdClicked() {
                    AdsLogEventManager.logClickAdsEvent(activity, adUnitId)
                    listener.onClicked(apAd)
                }

                override fun onAdImpression() {
                    AdsLogEventManager.onTrackImpression(activity)
                    listener.onImpression(apAd)
                }

                override fun onAdPaid(adValue: AdValue) {
                    AdsLogEventManager.logPaidAdImpression(activity, adValue, responseInfo, AdType.REWARDED)
                    listener.onPaid(adValue)
                }
                override fun onAdMetadataChanged() {
                    listener.onMetadataChanged()
                }
            })
            ad.show(activity) { rewardItem ->
                runOnMain { listener.onRewarded(apAd, ApRewardItem(rewardItem)) }
            }
        }
    }
}
