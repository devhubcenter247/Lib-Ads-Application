package com.lib.ads.gma.ads.helper.banner

import android.content.Context
import com.lib.ads.gma.ads.helper.BannerCollapseGravity
import com.lib.ads.gma.ads.manager.BannerAdManager

/**
 * Small tag-based preload API for banners.
 *
 * ```kotlin
 * BannerAds.preload(this, "home_banner", BuildConfig.ad_banner)
 * // Configure the helper with BannerAdConfig.setPreloadTag("home_banner")
 * ```
 */
object BannerAds {
    fun preload(
        context: Context,
        tag: String,
        adUnitId: String,
        collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
        useInlineAdaptive: Boolean = false,
        maxHeightDp: Int = 50,
        containerWidthPx: Int = 0,
    ) {
        BannerAdManager.preloadBanner(
            context = context,
            tag = tag,
            id = adUnitId,
            collapsibleGravity = collapsibleGravity.gravity,
            useInlineAdaptive = useInlineAdaptive,
            maxHeight = maxHeightDp,
            containerWidthPx = containerWidthPx,
        )
    }

    fun preload(
        context: Context,
        tag: String,
        adUnitIds: List<String>,
        collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
        useInlineAdaptive: Boolean = false,
        maxHeightDp: Int = 50,
        containerWidthPx: Int = 0,
    ) {
        BannerAdManager.preloadBanner(
            context = context,
            tag = tag,
            ids = adUnitIds,
            collapsibleGravity = collapsibleGravity.gravity,
            useInlineAdaptive = useInlineAdaptive,
            maxHeight = maxHeightDp,
            containerWidthPx = containerWidthPx,
        )
    }

    fun get(tag: String) = BannerAdManager.takePreloadedBanner(tag)

    fun available(tag: String): Boolean = BannerAdManager.hasPreloadedBanner(tag)

    fun clear(tag: String) = BannerAdManager.clearPreloadedBanner(tag)
}
