package com.lib.ads.gma.ads.ads.wrapper

import android.view.View
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.nativead.NativeAd
import com.lib.ads.gma.ads.helper.adnative.AdNativeMediation

/**
 * Wrapper class for native ads.
 */
class ApNativeAd : ApAdBase {

    var layoutCustomNative: Int = 0
    var nativeView: View? = null
    var admobNativeAd: NativeAd? = null
        set(value) {
            field = value
            if (value != null) {
                status = StatusAd.AD_LOADED
            }
        }

    /** Raw mediation adapter class name for the loaded ad, or null. */
    val mediationAdapterClassName: String?
        get() = admobNativeAd?.responseInfo?.mediationAdapterClassName

    /** Resolved mediation network for the loaded ad ([AdNativeMediation.UNKNOWN] if not loaded/unknown). */
    val mediation: AdNativeMediation
        get() = admobNativeAd?.let { AdNativeMediation.getOrUnknown(it) } ?: AdNativeMediation.UNKNOWN

    constructor() : super()

    constructor(status: StatusAd) : super(status)

    constructor(layoutCustomNative: Int, nativeView: View?) : super() {
        this.layoutCustomNative = layoutCustomNative
        this.nativeView = nativeView
        status = StatusAd.AD_LOADED
    }
    constructor(ad: NativeAd?): super(){
        this.admobNativeAd = ad
    }

    constructor(layoutCustomNative: Int, admobNativeAd: NativeAd?) : super() {
        this.layoutCustomNative = layoutCustomNative
        this.admobNativeAd = admobNativeAd
        if (admobNativeAd != null) {
            status = StatusAd.AD_LOADED
        }
    }

    override fun isReady(): Boolean = nativeView != null || admobNativeAd != null
    fun destroy(){
        admobNativeAd?.destroy()
    }
    fun responseInfo(): ResponseInfo?{
        return admobNativeAd?.responseInfo
    }
    override fun toString(): String {
        return "Status:$status == nativeView:$nativeView == admobNativeAd:$admobNativeAd"
    }
}
