package com.lib.ads.gma.ads.helper.utils

import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd

/** Mediation network returned by GMA for any ad format. */
enum class AdMediation(private vararg val adapterNames: String) {
    ADMOB("AdMobAdapter"),
    FACEBOOK("FacebookMediationAdapter"),
    APPLOVIN("AppLovinMediationAdapter"),
    MINTEGRAL("MintegralMediationAdapter"),
    PANGLE("PangleMediationAdapter"),
    VUNGLE("VungleMediationAdapter");

    companion object {
        fun from(responseInfo: ResponseInfo?): AdMediation? {
            val adapterClassName = responseInfo?.adapterClassName ?: return null
            return entries.firstOrNull { mediation ->
                mediation.adapterNames.any(adapterClassName::contains)
            }
        }

        fun get(nativeAd: NativeAd): AdMediation? = from(nativeAd.getResponseInfo())
    }
}

fun ResponseInfo.mediation(): AdMediation? = AdMediation.from(this)
