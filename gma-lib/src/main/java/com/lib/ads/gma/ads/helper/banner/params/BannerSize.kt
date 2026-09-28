package com.lib.ads.gma.ads.helper.banner.params

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize

/** Banner sizing policy. Dimensions are expressed in dp. */
sealed interface BannerSize {
    data object LargePortraitAdaptive : BannerSize
    data class InlineAdaptive(val maxHeightDp: Int = 56) : BannerSize
    /** Uses this width and lets GMA calculate the anchored-adaptive height. */
    data class Width(val widthDp: Int) : BannerSize
    /** Uses the device screen width and this exact height. */
    data class Height(val heightDp: Int) : BannerSize
    data class Fixed(val widthDp: Int, val heightDp: Int) : BannerSize
}

/**
 * Height in dp a loading placeholder should reserve for this size, so the layout does not jump
 * when the banner arrives. Adaptive sizes need a [Context]; without one, [fallbackDp] is used.
 */
/**
 * Height in dp a loading placeholder should reserve for this size, so the layout does not jump
 * when the banner arrives. Adaptive sizes need a [Context]; without one, [fallbackDp] is used.
 */

internal fun BannerSize.loadingHeightDp(context: Context?, fallbackDp: Int = 56): Int {
    when (this) {
        is BannerSize.Fixed -> return heightDp.coerceAtLeast(1)
        is BannerSize.Height -> return heightDp.coerceAtLeast(1)
        else -> Unit
    }

    if (context == null) return fallbackDp
    val metrics = context.resources.displayMetrics
    val density = metrics.density.coerceAtLeast(1f)
    val screenWidthDp = (metrics.widthPixels / density).toInt().coerceAtLeast(1)
    val adSize = when (this) {
        BannerSize.LargePortraitAdaptive ->
            AdSize.getLargePortraitAnchoredAdaptiveBannerAdSize(context, screenWidthDp) // Dùng hàm mới

        is BannerSize.Width ->
            AdSize.getLargePortraitAnchoredAdaptiveBannerAdSize(context, widthDp.coerceAtLeast(1)) // Dùng hàm mới

        is BannerSize.InlineAdaptive ->
            AdSize.getInlineAdaptiveBannerAdSize(screenWidthDp, maxHeightDp.coerceAtLeast(1))

        is BannerSize.Height, is BannerSize.Fixed -> error("Fixed height handled above")
    }
    return adSize.height.coerceAtLeast(1)
}