package com.lib.ads.gma.ads.model.wrapper

import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd

class ApRewardAd : ApAdBase {
    @JvmField
    var rewardAd: RewardedAd? = null

    @JvmField
    var rewardInterstitial: RewardedInterstitialAd? = null

    constructor() : super()

    constructor(status: StatusAd) : super(status)

    constructor(rewardAd: RewardedAd) : super(StatusAd.AD_LOADED) {
        this.rewardAd = rewardAd
    }

    constructor(rewardInterstitial: RewardedInterstitialAd) : super(StatusAd.AD_LOADED) {
        this.rewardInterstitial = rewardInterstitial
    }

    fun setRewardAd(rewardAd: RewardedAd) {
        this.rewardAd = rewardAd
        status = StatusAd.AD_LOADED
    }

    fun setRewardAd(rewardInterstitial: RewardedInterstitialAd) {
        this.rewardInterstitial = rewardInterstitial
    }

    fun clean() {
        rewardAd = null
        rewardInterstitial = null
    }

    override fun isReady() = rewardAd != null || rewardInterstitial != null

    fun isRewardInterstitial() = rewardInterstitial != null
}
