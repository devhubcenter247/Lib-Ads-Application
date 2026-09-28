package com.lib.ads.gma.ads.helper.adnative.provider

import android.app.Activity
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.LoadAdError
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.helper.adnative.NativeAdParam
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.core.NativeAdLoader

// ==================== Group A: XML/View Extensions ====================

/**
 * Create a [NativeAdProviderHelper] for a single ad unit with minimal boilerplate.
 *
 * Returns a [Lazy] so it can be used with `by` delegation before `setContentView`.
 * When [autoRequest] is true the ad is requested on first access. When
 * [preloadBufferSize] > 0 the placement also preloads via [NativeAdSpec].
 *
 * ```kotlin
 * private val nativeAdHelper by nativeAd(
 *     tag = "home",
 *     adUnitId = BuildConfig.ad_native,
 *     layoutId = R.layout.layout_native_common,
 *     shimmer = { binding.shimmerAd },
 *     content = { binding.flNativeAd },
 * )
 * ```
 */
fun AppCompatActivity.nativeAd(
    tag: String,
    adUnitId: String,
    @LayoutRes layoutId: Int,
    shimmer: () -> ShimmerFrameLayout,
    content: () -> FrameLayout,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    preloadBufferSize: Int = 1,
    adCallback: AdsCallback? = null
): Lazy<NativeAdProviderHelper> = lazy {
    buildHelper(
        activity = this, owner = this,
        spec = NativeAdSpec.simple(tag, adUnitId, layoutId, preloadBufferSize, canShowAds, canReloadAds),
        shimmer = shimmer, content = content, autoRequest = autoRequest, adCallback = adCallback
    )
}

/** Waterfall variant of [nativeAd]. */
fun AppCompatActivity.nativeAdWaterfall(
    tag: String,
    adUnitIds: List<String>,
    @LayoutRes layoutId: Int,
    shimmer: () -> ShimmerFrameLayout,
    content: () -> FrameLayout,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    preloadBufferSize: Int = 1,
    adCallback: AdsCallback? = null
): Lazy<NativeAdProviderHelper> = lazy {
    buildHelper(
        activity = this, owner = this,
        spec = NativeAdSpec.waterfall(tag, adUnitIds, layoutId, preloadBufferSize, canShowAds = canShowAds, canReloadAds = canReloadAds),
        shimmer = shimmer, content = content, autoRequest = autoRequest, adCallback = adCallback
    )
}

/** [nativeAd] usable from any [LifecycleOwner] (e.g. Fragment) with an explicit activity. */
fun LifecycleOwner.nativeAd(
    activity: Activity,
    tag: String,
    adUnitId: String,
    @LayoutRes layoutId: Int,
    shimmer: () -> ShimmerFrameLayout,
    content: () -> FrameLayout,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    autoRequest: Boolean = true,
    preloadBufferSize: Int = 1,
    adCallback: AdsCallback? = null
): Lazy<NativeAdProviderHelper> = lazy {
    buildHelper(
        activity = activity, owner = this,
        spec = NativeAdSpec.simple(tag, adUnitId, layoutId, preloadBufferSize, canShowAds, canReloadAds),
        shimmer = shimmer, content = content, autoRequest = autoRequest, adCallback = adCallback
    )
}

private fun buildHelper(
    activity: Activity,
    owner: LifecycleOwner,
    spec: NativeAdSpec,
    shimmer: () -> ShimmerFrameLayout,
    content: () -> FrameLayout,
    autoRequest: Boolean,
    adCallback: AdsCallback?
): NativeAdProviderHelper = NativeAdProviderHelper(activity, owner, spec)
    .setShimmerLayoutView(shimmer())
    .setNativeContentView(content())
    .also { helper ->
        adCallback?.let { helper.registerAdListener(it) }
        if (autoRequest) helper.requestAds(NativeAdParam.Request.CreateRequest)
    }

// ==================== Group B: Compose Extensions ====================

/**
 * Display a native ad with minimal setup — no [NativeAdSpec] needed.
 *
 * ```kotlin
 * NativeAd(tag = "home", adUnitId = BuildConfig.ad_native) { ad -> NativeAdView(ad) { ... } }
 * ```
 */
@Composable
fun NativeAd(
    tag: String,
    adUnitId: String,
    modifier: Modifier = Modifier,
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    autoReloadOnResume: Boolean = true,
    loading: @Composable (Boolean) -> Unit = { isShow -> DefaultNativeAdLoading(isShow) },
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = { }
) {
    val spec = remember(tag, adUnitId) { NativeAdSpec.simple(tag, adUnitId) }
    NativeAdWithPreload(
        spec = spec, modifier = modifier, timeoutPerIdMs = timeoutPerIdMs,
        autoReloadOnResume = autoReloadOnResume, loading = loading, error = error, nativeView = nativeView
    )
}


/** Waterfall variant of the Compose [NativeAd]. */
@Composable
fun NativeAd(
    tag: String,
    adUnitIds: List<String>,
    modifier: Modifier = Modifier,
    timeoutPerIdMs: Long = NativeAdLoader.DEFAULT_TIMEOUT_MS,
    autoReloadOnResume: Boolean = true,
    loading: @Composable (Boolean) -> Unit = { isShow -> DefaultNativeAdLoading(isShow) },
    error: @Composable (LoadAdError?) -> Unit = {},
    nativeView: @Composable (ApNativeAd) -> Unit = { }
) {
    val spec = remember(tag, adUnitIds.hashCode()) { NativeAdSpec.waterfall(tag, adUnitIds) }
    NativeAdWithPreload(
        spec = spec, modifier = modifier, timeoutPerIdMs = timeoutPerIdMs,
        autoReloadOnResume = autoReloadOnResume, loading = loading, error = error, nativeView = nativeView
    )
}
