package com.lib.ads.gma.ads.ads.wrapper

import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd

/**
 * Wrapper class for reward ads (both RewardedAd and RewardedInterstitialAd).
 */
class ApRewardAd : ApAdBase {

    var admobReward: RewardedAd? = null
        private set

    var admobRewardInter: RewardedInterstitialAd? = null
        private set

    constructor() : super()

    constructor(status: StatusAd) : super(status)

    constructor(admobReward: RewardedAd?) : super() {
        this.admobReward = admobReward
        if (admobReward != null) {
            status = StatusAd.AD_LOADED
        }
    }

    constructor(admobRewardInter: RewardedInterstitialAd?) : super() {
        this.admobRewardInter = admobRewardInter
        if (admobRewardInter != null) {
            status = StatusAd.AD_LOADED
        }
    }

    fun setAdmobReward(admobReward: RewardedAd?) {
        this.admobReward = admobReward
        if (admobReward != null) {
            status = StatusAd.AD_LOADED
        }
    }

    fun setAdmobReward(admobRewardInter: RewardedInterstitialAd?) {
        this.admobRewardInter = admobRewardInter
        if (admobRewardInter != null) {
            status = StatusAd.AD_LOADED
        }
    }

    /**
     * Cleans up reward ad references after showing.
     */
    fun clean() {
        admobReward = null
        admobRewardInter = null
    }

    override fun isReady(): Boolean = admobReward != null || admobRewardInter != null

    /**
     * Checks if this is a rewarded interstitial ad.
     */
    fun isRewardInterstitial(): Boolean = admobRewardInter != null
}
