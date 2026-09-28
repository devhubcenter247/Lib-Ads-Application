package com.lib.ads.gma.ads.model.wrapper

import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd

class ApInterstitialAd : ApAdBase {
    @JvmField
    var interstitialAd: InterstitialAd? = null

    constructor() : super()

    constructor(status: StatusAd) : super(status)

    constructor(interstitialAd: InterstitialAd) : super(StatusAd.AD_LOADED) {
        this.interstitialAd = interstitialAd
    }

    fun setInterstitialAd(interstitialAd: InterstitialAd) {
        this.interstitialAd = interstitialAd
        status = StatusAd.AD_LOADED
    }

    override fun isReady() = interstitialAd != null
}
