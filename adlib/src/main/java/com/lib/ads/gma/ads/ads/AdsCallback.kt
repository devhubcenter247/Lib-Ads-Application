package com.lib.ads.gma.ads.ads

import com.google.android.gms.ads.admanager.AdManagerAdView
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.ads.wrapper.ApRewardAd
import com.lib.ads.gma.ads.ads.wrapper.ApRewardItem

/**
 * Callback interface for ad events using wrapper classes.
 * All methods have default empty implementations for convenience.
 */
open class AdsCallback {

    open fun onNextAction() {}

    open fun onAdClosed() {}

    open fun onRewardedAdClosed(earnedReward: Boolean) {}

    open fun onAdFailedToLoad(adError: ApAdError?) {}

    open fun onAdFailedToShow(adError: ApAdError?) {}

    open fun onAdLoaded() {}

    open fun onRewardAdLoaded(apRewardAd: ApRewardAd?) {}

    /**
     * Called when splash ad is loaded and ready to show (when showSplashIfReady = false).
     */
    open fun onAdSplashReady() {}

    open fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {}

    open fun onAdClicked() {}

    open fun onAdOpened() {}

    open fun onAdImpression() {}

    open fun onNativeAdLoaded(nativeAd: ApNativeAd) {}

    open fun onNativeVideoStart() {}
    open fun onNativeVideoPlay() {}
    open fun onNativeVideoPause() {}
    open fun onNativeVideoEnd() {}
    open fun onNativeVideoMute(isMuted: Boolean) {}

    open fun onUserEarnedReward(rewardItem: ApRewardItem) {}

    open fun onInterstitialShow() {}

    open fun onBannerLoaded(adView: AdManagerAdView?) {}
}
