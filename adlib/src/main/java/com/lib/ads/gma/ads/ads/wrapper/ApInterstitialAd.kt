package com.lib.ads.gma.ads.ads.wrapper

import com.google.android.gms.ads.interstitial.InterstitialAd

/**
 * Wrapper class for AdMob InterstitialAd.
 */
class ApInterstitialAd : ApAdBase {

    var interstitialAd: InterstitialAd? = null
        set(value) {
            field = value
            if (value != null) {
                status = StatusAd.AD_LOADED
            }
        }

    constructor() : super()

    constructor(status: StatusAd) : super(status)

    constructor(interstitialAd: InterstitialAd?) : super() {
        this.interstitialAd = interstitialAd
        if (interstitialAd != null) {
            status = StatusAd.AD_LOADED
        }
    }

    override fun isReady(): Boolean = interstitialAd != null
}
