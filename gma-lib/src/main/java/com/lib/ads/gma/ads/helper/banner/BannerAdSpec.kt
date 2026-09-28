package com.lib.ads.gma.ads.helper.banner

import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity
import java.util.concurrent.TimeUnit

enum class BannerHeightConfig {
    LARGE_PORTRAIT_ADAPTIVE,
    INLINE_ADAPTIVE,
}

class BannerAdSpec(
    val tag: String,
    val adUnitIds: List<String>,
    val placementId: Long? = null,
    val bufferSize: Int = 1,
    val ttlMs: Long = TimeUnit.HOURS.toMillis(4),
    val size: BannerSize = BannerSize.LargePortraitAdaptive,
    val collapsibleGravity: BannerCollapseGravity? = null,
) {
    /** Derived loading values kept internal so every caller configures sizing through [size]. */
    internal val widthDp: Int? get() = when (val value = size) {
        is BannerSize.Fixed -> value.widthDp
        is BannerSize.Width -> value.widthDp
        else -> null
    }
    internal val heightConfig: BannerHeightConfig get() = when (size) {
        BannerSize.LargePortraitAdaptive, is BannerSize.Width, is BannerSize.Height, is BannerSize.Fixed -> BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE
        is BannerSize.InlineAdaptive -> BannerHeightConfig.INLINE_ADAPTIVE
    }
    internal val maxHeightDp: Int get() = when (val value = size) {
        BannerSize.LargePortraitAdaptive, is BannerSize.Width, is BannerSize.Height, is BannerSize.Fixed -> 0
        is BannerSize.InlineAdaptive -> value.maxHeightDp.coerceAtLeast(1)
    }
    internal val customWidthDp: Int? get() = (size as? BannerSize.Fixed)?.widthDp
    internal val customHeightDp: Int? get() = when (val value = size) {
        is BannerSize.Fixed -> value.heightDp
        is BannerSize.Height -> value.heightDp
        else -> null
    }
    val idAds: String get() = adUnitIds.lastOrNull().orEmpty()
    companion object {
        fun simple(tag: String, adUnitId: String, placementId: Long? = null, bufferSize: Int = 1, size: BannerSize = BannerSize.LargePortraitAdaptive, collapsibleGravity: BannerCollapseGravity? = null) = BannerAdSpec(tag, listOf(adUnitId), placementId, bufferSize = bufferSize, size = size, collapsibleGravity = collapsibleGravity)
        fun waterfall(tag: String, adUnitIds: List<String>, placementId: Long? = null, bufferSize: Int = 1, size: BannerSize = BannerSize.LargePortraitAdaptive, collapsibleGravity: BannerCollapseGravity? = null) = BannerAdSpec(tag, adUnitIds, placementId, bufferSize = bufferSize, size = size, collapsibleGravity = collapsibleGravity)
    }
}
