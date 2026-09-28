package com.lib.ads.gma.ads.helper.adnative

import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.helper.params.IAdsParam

/**
 * Sealed class representing different parameters for a native advertisement.
 */
sealed class NativeAdParam : IAdsParam {
    /**
     * Represents the state where the native ad is ready to be displayed.
     *
     * @param nativeAd The [ApNativeAd] representing the ready native ad.
     */
    data class Ready(val nativeAd: ApNativeAd) : NativeAdParam()
    /**
     * Sealed class representing different types of requests for a native ad.
     */
    sealed class Request : NativeAdParam() {
        /**
         * Represents the state where a new native ad request is created.
         */
        object CreateRequest : Request()
        /**
         * Represents the state where a previously created native ad request is running in state onResume of activity or fragment.
         */
        object ResumeRequest : Request()

        companion object {
            /**
             * Factory method to create a [Request] instance for creating a new native ad request.
             *
             * @return A new [Request] instance for creating a new native ad request.
             */

            fun create(): Request {
                return CreateRequest
            }
        }
    }
}
