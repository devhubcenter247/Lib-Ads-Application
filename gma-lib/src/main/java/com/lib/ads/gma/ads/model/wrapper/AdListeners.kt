package com.lib.ads.gma.ads.model.wrapper

import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.common.AdValue

interface NativeAdListener {
    fun onLoaded(ad: ApNativeAd) = Unit
    fun onFailed(error: ApAdError) = Unit
    fun onClicked(ad: ApNativeAd) = Unit
    fun onImpression(ad: ApNativeAd) = Unit
    fun onPaid(adValue: AdValue) = Unit
    fun onShownFullScreenContent(ad: ApNativeAd) = Unit
    fun onDismissedFullScreenContent(ad: ApNativeAd) = Unit
    fun onFailedToShowFullScreenContent(ad: ApNativeAd, error: ApAdError) = Unit
    fun onSwipeGestureClicked(ad: ApNativeAd) = Unit
}


interface BannerAdListener {
    fun onLoaded(adView: AdView?) = Unit
    fun onFailed(error: ApAdError) = Unit
    fun onClicked(adView: AdView?) = Unit
    fun onImpression(adView: AdView?) = Unit
    fun onPaid(adValue: AdValue) = Unit
    fun onAppEvent(name: String, data: String?) = Unit
    fun onShownFullScreenContent() = Unit
    fun onDismissedFullScreenContent() = Unit
    fun onFailedToShowFullScreenContent(error: ApAdError) = Unit
    fun onRefreshed() = Unit
    fun onFailedToRefresh(error: ApAdError) = Unit
}

interface InterstitialAdListener {
    fun onReady() = Unit
    fun onNextAction() = Unit
    fun onLoaded(ad: ApInterstitialAd) = Unit
    fun onFailed(error: ApAdError) = Unit
    fun onShown(ad: ApInterstitialAd) = Unit
    fun onImpression(ad: ApInterstitialAd) = Unit
    fun onClicked(ad: ApInterstitialAd) = Unit
    fun onDismissed(ad: ApInterstitialAd) = Unit
    fun onFailedToShow(error: ApAdError) = Unit
    fun onPaid(adValue: AdValue) = Unit
    fun onAppEvent(name: String, data: String?) = Unit

}

interface RewardAdListener {
    fun onLoaded(ad: ApRewardAd) = Unit
    fun onFailed(error: ApAdError) = Unit
    fun onShown(ad: ApRewardAd) = Unit
    fun onImpression(ad: ApRewardAd) = Unit
    fun onRewarded(ad: ApRewardAd, item: ApRewardItem) = Unit
    fun onClicked(ad: ApRewardAd) = Unit
    fun onDismissed(ad: ApRewardAd) = Unit

    /**
     * Fired right after [onDismissed]; [earnedReward] is true only if [onRewarded] fired before close.
     * Use it to pick the next UI step — grant the reward only in [onRewarded].
     */
    fun onRewardedAdClosed(ad: ApRewardAd, earnedReward: Boolean) = Unit
    fun onFailedToShow(error: ApAdError) = Unit
    fun onNotReady() = Unit
    fun onPaid(adValue: AdValue) = Unit
    fun onMetadataChanged() = Unit
}

interface AppOpenAdListener {
    fun onLoaded() = Unit
    fun onShown() = Unit
    fun onDismissed() = Unit
    fun onFailed(error: ApAdError) = Unit
    fun onClicked() = Unit
    fun onImpression() = Unit
    fun onPaid(adValue: AdValue) = Unit
    fun onFailedToShow(error: ApAdError) = Unit

    fun onReady() = Unit
    fun onNextAction() = Unit

    fun onAppEvent(name: String, data: String?) = Unit

}
