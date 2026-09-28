package com.lib.ads.gma.ads.helper.adnative.callback

import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.ads.wrapper.ApRewardItem
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeAdCallback {
    private val listAdCallback: CopyOnWriteArrayList<AdsCallback> = CopyOnWriteArrayList()

    fun registerAdListener(adCallback: AdsCallback) {
        this.listAdCallback.add(adCallback)
    }

    fun unregisterAdListener(adCallback: AdsCallback) {
        this.listAdCallback.remove(adCallback)
    }

    fun unregisterAllAdListener() {
        this.listAdCallback.clear()
    }

    private fun invokeAdListener(action: (adCallback: AdsCallback) -> Unit) {
        listAdCallback.forEach(action)
    }

    fun invokeListenerAdCallback(internalAdCallback: AdsCallback? = null): AdsCallback {
        return object : AdsCallback() {
            override fun onNextAction() {
                super.onNextAction()
                internalAdCallback?.onNextAction()
                invokeAdListener { it.onNextAction() }
            }

            override fun onAdClosed() {
                super.onAdClosed()
                internalAdCallback?.onAdClosed()
                invokeAdListener { it.onAdClosed() }
            }

            override fun onAdFailedToLoad(adError: ApAdError?) {
                super.onAdFailedToLoad(adError)
                internalAdCallback?.onAdFailedToLoad(adError)
                invokeAdListener { it.onAdFailedToLoad(adError) }
            }

            override fun onAdFailedToShow(adError: ApAdError?) {
                super.onAdFailedToShow(adError)
                internalAdCallback?.onAdFailedToShow(adError)
                invokeAdListener { it.onAdFailedToShow(adError) }
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                internalAdCallback?.onAdLoaded()
                invokeAdListener { it.onAdLoaded() }
            }

            override fun onAdSplashReady() {
                super.onAdSplashReady()
                internalAdCallback?.onAdSplashReady()
                invokeAdListener { it.onAdSplashReady() }
            }

            override fun onInterstitialLoad(interstitialAd: ApInterstitialAd?) {
                super.onInterstitialLoad(interstitialAd)
                internalAdCallback?.onInterstitialLoad(interstitialAd)
                invokeAdListener { it.onInterstitialLoad(interstitialAd) }
            }

            override fun onAdClicked() {
                super.onAdClicked()
                internalAdCallback?.onAdClicked()
                invokeAdListener { it.onAdClicked() }
            }

            override fun onAdImpression() {
                super.onAdImpression()
                internalAdCallback?.onAdImpression()
                invokeAdListener { it.onAdImpression() }
            }

            override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                super.onNativeAdLoaded(nativeAd)
                internalAdCallback?.onNativeAdLoaded(nativeAd)
                invokeAdListener { it.onNativeAdLoaded(nativeAd) }
            }

            override fun onUserEarnedReward(rewardItem: ApRewardItem) {
                super.onUserEarnedReward(rewardItem)
                internalAdCallback?.onUserEarnedReward(rewardItem)
                invokeAdListener { it.onUserEarnedReward(rewardItem) }
            }

            override fun onInterstitialShow() {
                super.onInterstitialShow()
                internalAdCallback?.onInterstitialShow()
                invokeAdListener { it.onInterstitialShow() }
            }
        }
    }
}
