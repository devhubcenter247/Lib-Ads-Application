package com.lib.ads.gma.ads.helper.banner

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.helper.BannerCollapseGravity

// ──────────────────────────────────────────────
// XML one-liners — mirror of helper/adnative `nativeAd { }`
// ──────────────────────────────────────────────

/**
 * Create a [BannerAdHelper] for a single ad unit with one call.
 *
 * Returns a [Lazy] so it can be used with `by` delegation before `setContentView`.
 * Builds the config, wires the container (+ shimmer), and auto-requests.
 *
 * ```kotlin
 * private val bannerHelper by bannerAd(
 *     adUnitId = BuildConfig.ad_banner,
 *     container = { binding.frAds },
 * )
 * // collapsible:
 * private val bannerHelper by bannerAd(
 *     adUnitId = BuildConfig.ad_banner_collapse,
 *     container = { binding.frAds },
 *     collapsibleGravity = "bottom",
 * )
 * ```
 */
fun AppCompatActivity.bannerAd(
    adUnitId: String,
    container: () -> FrameLayout,
    collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    adCallback: AdsCallback? = null,
    preloadTag: String? = null,
): Lazy<BannerAdHelper> = lazy {
    buildBannerHelper(
        context = this, owner = this,
        config = BannerAdConfig.simple(adUnitId, useInline, maxHeightDp, collapsibleGravity, canShowAds, canReloadAds)
            .setPreloadTag(preloadTag),
        isList = false, container = container, autoRequest = autoRequest, adCallback = adCallback
    )
}

/** Waterfall variant of [bannerAd]. */
fun AppCompatActivity.bannerAdWaterfall(
    adUnitIds: List<String>,
    container: () -> FrameLayout,
    collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    adCallback: AdsCallback? = null,
    preloadTag: String? = null,
): Lazy<BannerAdHelper> = lazy {
    buildBannerHelper(
        context = this, owner = this,
        config = BannerAdConfig.waterfall(adUnitIds, useInline, maxHeightDp, collapsibleGravity, canShowAds, canReloadAds)
            .setPreloadTag(preloadTag),
        isList = true, container = container, autoRequest = autoRequest, adCallback = adCallback
    )
}

/** [bannerAd] usable from any [LifecycleOwner] (e.g. a Fragment) with an explicit activity. */
fun LifecycleOwner.bannerAd(
    activity: Activity,
    adUnitId: String,
    container: () -> FrameLayout,
    collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
    useInline: Boolean = false,
    maxHeightDp: Int = 50,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    adCallback: AdsCallback? = null,
    preloadTag: String? = null,
): Lazy<BannerAdHelper> = lazy {
    buildBannerHelper(
        context = activity, owner = this,
        config = BannerAdConfig.simple(adUnitId, useInline, maxHeightDp, collapsibleGravity, canShowAds, canReloadAds)
            .setPreloadTag(preloadTag),
        isList = false, container = container, autoRequest = autoRequest, adCallback = adCallback
    )
}

private fun buildBannerHelper(
    context: Context,
    owner: LifecycleOwner,
    config: BannerAdConfig,
    isList: Boolean,
    container: () -> FrameLayout,
    autoRequest: Boolean,
    adCallback: AdsCallback?,
): BannerAdHelper = BannerAdHelper(context, owner, config)
    .setBannerContentView(container())
    .setEnableListBanner(isList)
    .also { helper ->
        adCallback?.let { helper.registerAdListener(it) }
        if (autoRequest) helper.requestAds(BannerAdParam.Request)
    }
