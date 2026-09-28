package com.lib.ads.gma.ads.helper.adnative

import androidx.annotation.LayoutRes
/**
 * Data class representing the layout mediation configuration for a native ad.
 *
 * @param mediationType The mediation platform type for the native ad.
 * @param layoutId The resource identifier of the layout to be used for the native ad.
 */
data class NativeLayoutMediation(
    val mediationType: AdNativeMediation,
    @LayoutRes
    val layoutId: Int
)
