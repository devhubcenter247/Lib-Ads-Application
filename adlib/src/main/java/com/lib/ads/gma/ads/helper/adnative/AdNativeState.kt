package com.lib.ads.gma.ads.helper.adnative

import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd

/**
 * Sealed class representing the different states of a native ad.
 */
sealed class AdNativeState {
    /**
     * Represents the state where no native ad is available.
     */
    object None : AdNativeState()
    /**
     * Represents the state where the native ad is currently being loaded.
     */
    object Loading : AdNativeState()
    /**
     * Represents the state where the native ad request has been canceled.
     */
    object Cancel : AdNativeState()
    /**
     * Represents the state where the native ad has been successfully loaded.
     *
     * @param adNative The [ApNativeAd] representing the loaded native ad.
     */
    data class Loaded(val adNative: ApNativeAd) : AdNativeState()
    /**
     * Represents the state where an attempt to load the native ad has failed.
     *
     * @param error The [ApAdError] describing the failure, or null if ads are disabled/not requested.
     */
    data class Fail(val error: ApAdError? = null) : AdNativeState()
}
