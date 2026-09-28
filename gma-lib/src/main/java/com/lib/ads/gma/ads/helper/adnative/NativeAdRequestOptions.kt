package com.lib.ads.gma.ads.helper.adnative

import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesPlacement
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest

/**
 * Options used to build a GMA [com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest].
 * UI/layout concerns do not belong here.
 */
class NativeAdRequestOptions {
    var nativeAdTypes: List<NativeAd.NativeAdType> = listOf(NativeAd.NativeAdType.NATIVE)
        private set
    var customFormatIds: List<String> = emptyList()
        private set
    var disableImageDownloading: Boolean = false
        private set
    var mediaAspectRatio: NativeAd.NativeMediaAspectRatio? = null
        private set
    var adChoicesPlacement: AdChoicesPlacement? = null
        private set
    var videoOptions: VideoOptions? = null
        private set
    var customClickGestureDirection: NativeAd.SwipeGestureDirection? = null
        private set
    var customClickGestureAllowTaps: Boolean = false
        private set
    var adSize: AdSize? = null
        private set
    var adSizes: List<AdSize> = emptyList()
        private set
    var manualImpressionEnabled: Boolean? = null
        private set
    /** AdMob Ad Placements id — tags requests built from these options with a UI-location identifier. */
    var placementId: Long? = null
        private set

    fun setNativeAdTypes(value: List<NativeAd.NativeAdType>) = apply {
        require(value.isNotEmpty()) { "nativeAdTypes cannot be empty" }
        nativeAdTypes = value.toList()
    }

    /**
     * Convenience for [NativeAd.NativeAdType.BANNER] — a native request that may return a banner
     * creative instead of (or, with [includeNativeType], alongside) a native one. Restricted to Ad
     * Manager accounts; on plain AdMob accounts this returns no fill.
     */
    fun requestBannerAdType(includeNativeType: Boolean = false) = apply {
        nativeAdTypes = if (includeNativeType) {
            listOf(NativeAd.NativeAdType.NATIVE, NativeAd.NativeAdType.BANNER)
        } else {
            listOf(NativeAd.NativeAdType.BANNER)
        }
    }

    fun setCustomFormatIds(value: List<String>) = apply { customFormatIds = value.toList() }

    fun disableImageDownloading() = apply { disableImageDownloading = true }

    fun setMediaAspectRatio(value: NativeAd.NativeMediaAspectRatio) = apply { mediaAspectRatio = value }

    fun setAdChoicesPlacement(value: AdChoicesPlacement) = apply { adChoicesPlacement = value }

    fun setVideoOptions(value: VideoOptions?) = apply { videoOptions = value }

    fun setCustomClickGestureDirection(
        direction: NativeAd.SwipeGestureDirection,
        allowTaps: Boolean
    ) = apply {
        customClickGestureDirection = direction
        customClickGestureAllowTaps = allowTaps
    }

    fun setAdSize(value: AdSize) = apply {
        adSize = value
        adSizes = emptyList()
    }

    fun setAdSizes(value: List<AdSize>) = apply {
        adSizes = value.toList()
        adSize = null
    }

    fun setManualImpressionEnabled(value: Boolean) = apply { manualImpressionEnabled = value }

    fun setPlacementId(value: Long) = apply { placementId = value }
}

/**
 * Builds the actual [NativeAdRequest] for [adUnitId] from these options — the single place every
 * native load ([com.lib.ads.gma.ads.manager.NativeAdManager.loadNativeAdRaw], used by holders and
 * [com.lib.ads.gma.ads.helper.adnative.NativeAds] preloads) builds its request, so a setting here
 * (including [NativeAdRequestOptions.placementId]) applies everywhere, from XML and Compose.
 */
fun NativeAdRequestOptions.toNativeAdRequest(adUnitId: String): NativeAdRequest {
    val builder = NativeAdRequest.Builder(adUnitId, nativeAdTypes)
    if (customFormatIds.isNotEmpty()) builder.setCustomFormatIds(customFormatIds)
    if (disableImageDownloading) builder.disableImageDownloading()
    mediaAspectRatio?.let(builder::setMediaAspectRatio)
    adChoicesPlacement?.let(builder::setAdChoicesPlacement)
    videoOptions?.let(builder::setVideoOptions)
    customClickGestureDirection?.let { builder.enableCustomClickGestureDirection(it, customClickGestureAllowTaps) }
    adSize?.let(builder::setAdSize)
    if (adSizes.isNotEmpty()) builder.setAdSizes(adSizes)
    manualImpressionEnabled?.let { builder.setManualImpressionEnabled(it) }
    placementId?.let(builder::setPlacementId)
    return builder.build()
}
