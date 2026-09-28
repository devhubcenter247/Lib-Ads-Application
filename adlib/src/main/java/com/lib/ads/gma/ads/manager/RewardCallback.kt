package com.lib.ads.gma.ads.manager

import com.google.android.gms.ads.rewarded.RewardItem

interface RewardCallback {
    fun onUserEarnedReward(rewardItem: RewardItem?)
    fun onRewardedAdClosed()
    fun onRewardedAdClosed(earnedReward: Boolean) {
        onRewardedAdClosed()
    }
    fun onRewardedAdFailedToShow(codeError: Int)
    fun onAdClicked()
    fun onAdImpression()
}
