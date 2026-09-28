package com.lib.ads.gma.ads.helper.adnative

import com.google.android.gms.ads.nativead.NativeAd

/**
 * Enum class representing different mediation platforms for native ads.
 *
 * @property clazz The corresponding mediation adapter class for each platform.
 */
enum class AdNativeMediation(val clazz: String) {
    ADMOB("AdMobAdapter"),
    FACEBOOK("FacebookMediationAdapter"),
    APPLOVIN("AppLovinMediationAdapter"),
    MINTEGRAL("MintegralMediationAdapter"),
    PANGLE("PangleMediationAdapter"),
    VUNGLE("VungleMediationAdapter"),

    /** Loaded ad whose mediation network could not be determined. */
    UNKNOWN("");

    companion object {
        /**
         * Gets the [AdNativeMediation] based on the provided [NativeAd].
         *
         * @param nativeAd The native ad from which to extract mediation information.
         * @return The corresponding [AdNativeMediation] or null if not found.
         */
        fun get(nativeAd: NativeAd): AdNativeMediation? {
            val adapterClassName = nativeAd.responseInfo?.mediationAdapterClassName ?: return null
            return entries.find { it != UNKNOWN && adapterClassName.contains(it.clazz) }
        }

        /** Like [get] but never null — returns [UNKNOWN] when the network can't be resolved. */
        fun getOrUnknown(nativeAd: NativeAd): AdNativeMediation = get(nativeAd) ?: UNKNOWN
    }
}
