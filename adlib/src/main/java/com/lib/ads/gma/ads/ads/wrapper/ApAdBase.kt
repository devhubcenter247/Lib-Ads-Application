package com.lib.ads.gma.ads.ads.wrapper

/**
 * Abstract base class for ad wrappers.
 *
 * @param status Initial status of the ad.
 */
abstract class ApAdBase(
    var status: StatusAd = StatusAd.AD_INIT
) {
    /**
     * Checks if the ad is ready to be shown.
     */
    abstract fun isReady(): Boolean

    /**
     * Checks if the ad is not ready.
     */
    fun isNotReady(): Boolean = !isReady()

    /**
     * Checks if the ad is currently loading.
     */
    fun isLoading(): Boolean = status == StatusAd.AD_LOADING

    /**
     * Checks if the ad failed to load.
     */
    fun isLoadFail(): Boolean = status == StatusAd.AD_LOAD_FAIL
}
