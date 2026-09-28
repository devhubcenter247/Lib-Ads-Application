package com.lib.ads.gma.ads.helper.adnative.params

import androidx.annotation.LayoutRes
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.lib.ads.gma.ads.helper.utils.AdMediation

data class NativeLayoutMediation(
    val mediationType: AdMediation,
    @LayoutRes val layoutId: Int
)

/** The layout for [nativeAd]'s mediation network, falling back to [defaultLayoutId]. */
@LayoutRes
internal fun List<NativeLayoutMediation>.layoutFor(nativeAd: NativeAd?, @LayoutRes defaultLayoutId: Int): Int {
    if (isEmpty() || nativeAd == null) return defaultLayoutId
    val mediation = AdMediation.get(nativeAd)
    return firstOrNull { it.mediationType == mediation }?.layoutId ?: defaultLayoutId
}
