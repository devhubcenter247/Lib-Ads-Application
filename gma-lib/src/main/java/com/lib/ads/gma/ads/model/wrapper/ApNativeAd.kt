package com.lib.ads.gma.ads.model.wrapper

import android.view.View
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull

class ApNativeAd : ApAdBase {
    var layoutCustomNative: Int = 0
    var nativeView: View? = null

    @JvmField
    var nativeAd: NativeAd? = null

    constructor() : super()

    constructor(status: StatusAd) : super(status)


    constructor(layoutCustomNative: Int, nativeView: View) : super(StatusAd.AD_LOADED) {
        this.layoutCustomNative = layoutCustomNative
        this.nativeView = nativeView
    }

    constructor(layoutCustomNative: Int, nativeAd: NativeAd) : super(StatusAd.AD_LOADED) {
        this.layoutCustomNative = layoutCustomNative
        this.nativeAd = nativeAd
    }

    fun setNativeAd(nativeAd: NativeAd?) {
        this.nativeAd = nativeAd
        if (nativeAd != null) status = StatusAd.AD_LOADED
    }

    override fun isReady() = nativeView != null || nativeAd != null

    override fun toString() =
        "Status:$status == nativeView:$nativeView == nativeAd:$nativeAd"
}

/**
 * The ad unit id that actually served this ad, read back from the SDK's response info. Lets a
 * caller who polled a waterfall list (e.g. [com.lib.ads.gma.ads.helper.adnative.NativeAds.get])
 * find out which specific id in the list won, for per-placement fill-rate/show-rate analysis.
 */
fun ApNativeAd.resolvedAdUnitId(): String? = nativeAd?.getResponseInfo()?.extractAdUnitIdOrNull()
