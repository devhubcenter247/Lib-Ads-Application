package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApRewardAd
import com.lib.ads.gma.ads.ads.wrapper.ApRewardItem
import com.lib.ads.gma.ads.helper.fullscreen.isAppInForeground
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppLogger
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions

object RewardAdManager {
    private const val TAG = "RewardAdManager"
    private const val REWARD_ADS = 4

    /**
     * Load a rewarded ad for a single ad unit.
     *
     * @param context Any valid Context for loading the ad.
     * @param id Rewarded ad unit id.
     * @param callback Receives load success/failure and the loaded ad.
     * @param ssvCustomData Optional server-side verification payload.
     * Use this when your backend needs to validate reward eligibility.
     * Common values: user id, session id, order id, or purchase token.
     */
    fun loadRewardAd(
        context: Context,
        id: String,
        callback: AdsCallback,
        enabled: Boolean = true,
        ssvCustomData: String? = null,
    ) {
        AdsManager.checkTestId(context, REWARD_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(context)) {
            callback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val appCtx = context.applicationContext
        AdLoadStats.recordRequested(AdType.REWARDED, adUnitId = id)

        RewardedAd.load(context, id, AdsManager.getAdRequest(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(rewardedAd: RewardedAd) {
                AdLoadStats.recordLoaded(AdType.REWARDED, adUnitId = id)
                rewardedAd.setImmersiveMode(true)
                ssvCustomData?.let { customData ->
                    rewardedAd.setServerSideVerificationOptions(
                        ServerSideVerificationOptions.Builder().setCustomData(customData).build()
                    )
                }
                Log.d(TAG, "RewardedAd onAdLoaded:")
                rewardedAd.setOnPaidEventListener { adValue ->
                    Log.d(TAG, "OnPaidEvent Reward: ${adValue.valueMicros}")
                    AdsManager.logPaidEvent(appCtx, adValue, rewardedAd.adUnitId, rewardedAd.responseInfo, AdType.REWARDED)
                }
                callback.onAdLoaded()
                callback.onRewardAdLoaded(ApRewardAd(rewardedAd))
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                AdLoadStats.recordFailed(AdType.REWARDED, adUnitId = id, reason = loadAdError.code.toString())
                callback.onAdFailedToLoad(ApAdError(loadAdError))
                Log.e(TAG, "RewardedAd onAdFailedToLoad: ${loadAdError.message}")
            }
        })
    }

    /**
     * Load a rewarded ad using waterfall fallback across multiple ad unit ids.
     *
     * This API does not need an Activity to load the ad. It only requires a Context,
     * because the loader tries each ad unit one by one until one succeeds or the list
     * is exhausted. Keep an Activity for the later show step only.
     *
     * @param context Any valid Context for loading.
     * @param listId Ordered fallback list. The first non-empty id is requested first.
     * @param adsCallback Receives the first successful ad or the final failure.
     */
    fun loadRewardAdList(context: Context, listId: List<String>?, adsCallback: AdsCallback) {
        if (listId.isNullOrEmpty()) {
            adsCallback.onAdFailedToLoad(ApAdError("list id is null or empty"))
            return
        }
        val index = intArrayOf(0)
        val adCallback = object : AdsCallback() {
            override fun onRewardAdLoaded(apRewardAd: ApRewardAd?) {
                super.onRewardAdLoaded(apRewardAd)
                AppLogger.d(TAG, "getRewardAdList onRewardAdLoaded:")
                adsCallback.onRewardAdLoaded(apRewardAd)
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                AppLogger.e(TAG, "onAdFailedToLoad: ${adError?.message}")
                if (index[0] < listId.size - 1) {
                    index[0] = index[0] + 1
                    AppLogger.d(TAG, "getRewardAdList: ${index[0]} id: ${listId[index[0]]}")
                    loadRewardAd(context, listId[index[0]], this)
                } else {
                    adsCallback.onAdFailedToLoad(adError)
                }
            }
        }
        AppLogger.d(TAG, "getRewardAdList: ${index[0]} id: ${listId[index[0]]}")
        loadRewardAd(context, listId[index[0]], adCallback)
    }

    /**
     * Load a rewarded-interstitial ad for a single ad unit.
     *
     * @param context Any valid Context for loading the ad.
     * @param id Rewarded-interstitial ad unit id.
     * @param callback Receives load success/failure and the loaded ad.
     * @param ssvCustomData Optional server-side verification payload.
     * Use this when your backend needs to validate reward eligibility.
     * Common values: user id, session id, order id, or purchase token.
     */
    fun loadRewardInterstitialAd(
        context: Context,
        id: String,
        callback: AdsCallback,
        enabled: Boolean = true,
        ssvCustomData: String? = null,
    ) {
        AdsManager.checkTestId(context, REWARD_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(context)) {
            callback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val appCtx = context.applicationContext
        AdLoadStats.recordRequested(AdType.REWARDED, adUnitId = id)

        RewardedInterstitialAd.load(
            context,
            id,
            AdsManager.getAdRequest(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedInterstitialAd) {
                    AdLoadStats.recordLoaded(AdType.REWARDED, adUnitId = id)
                    rewardedAd.setImmersiveMode(true)
                    ssvCustomData?.let { customData ->
                        rewardedAd.setServerSideVerificationOptions(
                            ServerSideVerificationOptions.Builder().setCustomData(customData).build()
                        )
                    }
                    Log.i(TAG, "RewardInterstitial onAdLoaded")
                    rewardedAd.setOnPaidEventListener { adValue ->
                        Log.d(TAG, "OnPaidEvent Reward: ${adValue.valueMicros}")
                        AdsManager.logPaidEvent(appCtx, adValue, rewardedAd.adUnitId, rewardedAd.responseInfo, AdType.REWARDED)
                    }
                    callback.onAdLoaded()
                    callback.onRewardAdLoaded(ApRewardAd(rewardedAd))
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    AdLoadStats.recordFailed(AdType.REWARDED, adUnitId = id, reason = loadAdError.code.toString())
                    callback.onAdFailedToLoad(ApAdError(loadAdError))
                    Log.e(TAG, "RewardInterstitial onAdFailedToLoad: ${loadAdError.message}")
                }
            })
    }

    fun loadRewardedInterstitialAd(
        context: Context,
        id: String,
        callback: AdsCallback,
        enabled: Boolean = true,
        ssvCustomData: String? = null,
    ) {
        loadRewardInterstitialAd(context, id, callback, enabled = enabled, ssvCustomData = ssvCustomData)
    }

    fun showRewardAd(
        activity: Activity,
        apRewardAd: ApRewardAd,
        callback: AdsCallback,
        grantRewardIfPurchased: Boolean = false
    ) {
        if (!apRewardAd.isReady()) {
            Log.e(TAG, "showRewardAd fail: reward ad not ready")
            callback.onNextAction()
            return
        }
        if (apRewardAd.isRewardInterstitial()) {
            showRewardInterstitial(activity, apRewardAd.admobRewardInter, grantRewardIfPurchased, object : RewardCallback {
                override fun onUserEarnedReward(rewardItem: com.google.android.gms.ads.rewarded.RewardItem?) {
                    callback.onUserEarnedReward(ApRewardItem(rewardItem))
                }

                override fun onRewardedAdClosed() {
                    onRewardedAdClosed(false)
                }

                override fun onRewardedAdClosed(earnedReward: Boolean) {
                    apRewardAd.clean()
                    callback.onRewardedAdClosed(earnedReward)
                    callback.onAdClosed()
                    callback.onNextAction()
                }

                override fun onRewardedAdFailedToShow(codeError: Int) {
                    apRewardAd.clean()
                    callback.onAdFailedToShow(ApAdError(AdError(codeError, "note msg", "Reward")))
                    callback.onNextAction()
                }

                override fun onAdClicked() {
                    callback.onAdClicked()
                }

                override fun onAdImpression() {
                    callback.onAdImpression()
                }
            })
        } else {
            showRewardAds(activity, apRewardAd.admobReward, grantRewardIfPurchased, object : RewardCallback {
                override fun onUserEarnedReward(rewardItem: com.google.android.gms.ads.rewarded.RewardItem?) {
                    callback.onUserEarnedReward(ApRewardItem(rewardItem))
                }

                override fun onRewardedAdClosed() {
                    onRewardedAdClosed(false)
                }

                override fun onRewardedAdClosed(earnedReward: Boolean) {
                    apRewardAd.clean()
                    callback.onRewardedAdClosed(earnedReward)
                    callback.onAdClosed()
                    callback.onNextAction()
                }

                override fun onRewardedAdFailedToShow(codeError: Int) {
                    apRewardAd.clean()
                    callback.onAdFailedToShow(ApAdError(AdError(codeError, "note msg", "Reward")))
                    callback.onNextAction()
                }

                override fun onAdClicked() {
                    callback.onAdClicked()
                }

                override fun onAdImpression() {
                    callback.onAdImpression()
                }
            })
        }
    }

    fun showRewardedInterstitialAd(
        activity: Activity,
        apRewardAd: ApRewardAd,
        callback: AdsCallback,
        grantRewardIfPurchased: Boolean = false
    ) {
        if (!apRewardAd.isRewardInterstitial()) {
            callback.onAdFailedToShow(ApAdError(AdError(0, "Not a rewarded interstitial ad", "Reward")))
            callback.onNextAction()
            return
        }
        showRewardAd(activity, apRewardAd, callback, grantRewardIfPurchased)
    }

    private fun showRewardInterstitial(
        activity: Activity,
        rewardedInterstitialAd: RewardedInterstitialAd?,
        grantRewardIfPurchased: Boolean,
        adCallback: RewardCallback
    ) {
        if (AdsManager.isPurchased(activity)) {
            if (grantRewardIfPurchased) {
                adCallback.onUserEarnedReward(null)
            }
            adCallback.onRewardedAdClosed(grantRewardIfPurchased)
            return
        }
        val appCtx = activity.applicationContext

        if (rewardedInterstitialAd == null || activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            adCallback.onRewardedAdFailedToShow(0)
        } else {
            var earnedReward = false
            rewardedInterstitialAd.fullScreenContentCallback =
                object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        super.onAdDismissedFullScreenContent()
                        adCallback.onRewardedAdClosed(earnedReward)
                        AdsManager.setFullScreenAdShowing(false)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        super.onAdFailedToShowFullScreenContent(adError)
                        adCallback.onRewardedAdFailedToShow(adError.code)
                    }

                    override fun onAdShowedFullScreenContent() {
                        super.onAdShowedFullScreenContent()
                        AdsManager.setFullScreenAdShowing(true)
                    }

                    override fun onAdClicked() {
                        super.onAdClicked()
                        AdsManager.handleAdClick(appCtx, rewardedInterstitialAd.adUnitId)
                        adCallback.onAdClicked()
                    }

                    override fun onAdImpression() {
                        super.onAdImpression()
                        AdsManager.handleAdImpression()
                        adCallback.onAdImpression()
                    }
                }
            rewardedInterstitialAd.show(activity) { rewardItem ->
                earnedReward = true
                adCallback.onUserEarnedReward(rewardItem)
            }
        }
    }

    private fun showRewardAds(
        activity: Activity,
        rewardedAd: RewardedAd?,
        grantRewardIfPurchased: Boolean,
        adCallback: RewardCallback
    ) {
        if (AdsManager.isPurchased()) {
            if (grantRewardIfPurchased) {
                adCallback.onUserEarnedReward(null)
            }
            adCallback.onRewardedAdClosed(grantRewardIfPurchased)
            return
        }
        val appCtx = activity.applicationContext

        if (rewardedAd == null || activity.isFinishing || activity.isDestroyed || !isAppInForeground()) {
            adCallback.onRewardedAdFailedToShow(0)
        } else {
            var earnedReward = false
            rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    super.onAdDismissedFullScreenContent()
                    adCallback.onRewardedAdClosed(earnedReward)
                    AdsManager.setFullScreenAdShowing(false)
                }
                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    super.onAdFailedToShowFullScreenContent(adError)
                    adCallback.onRewardedAdFailedToShow(adError.code)
                }

                override fun onAdShowedFullScreenContent() {
                    super.onAdShowedFullScreenContent()
                    AdsManager.setFullScreenAdShowing(true)
                }
                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsManager.handleAdClick(appCtx, rewardedAd.adUnitId)
                    adCallback.onAdClicked()
                }

                override fun onAdImpression() {
                    AdsManager.handleAdImpression()
                    super.onAdImpression()
                    adCallback.onAdImpression()
                }
            }
            rewardedAd.show(activity) { rewardItem ->
                earnedReward = true
                adCallback.onUserEarnedReward(rewardItem)
            }
        }
    }
}
