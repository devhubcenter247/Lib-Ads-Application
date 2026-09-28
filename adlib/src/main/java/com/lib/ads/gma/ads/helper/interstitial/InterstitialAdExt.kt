package com.lib.ads.gma.ads.helper.interstitial

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.ads.AdsCallback

// ──────────────────────────────────────────────
// One-liners — mirror of `nativeAd { }` / `bannerAd { }`
// ──────────────────────────────────────────────

/**
 * Create an [InterstitialAdHelper] in one call. Loading only needs a [Context] (the application
 * context is enough); showing needs an Activity passed to [InterstitialAdHelper.forceShow].
 *
 * Returns a [Lazy] for `by` delegation. When [autoLoad] is true the ad starts loading on first
 * access. Pass [lifecycleOwner] to enable auto-cancel on destroy + auto-reload on resume; omit it
 * (default) and you drive everything yourself, releasing with [InterstitialAdHelper.destroy].
 *
 * ```kotlin
 * private val inter by interstitialAd(adUnitId = BuildConfig.ad_interstitial, intervalBetweenAds = 15)
 *
 * // later (Activity context already known → no need to pass it)
 * inter.forceShow(object : AdsCallback() { override fun onNextAction() { /* navigate */ } })
 * ```
 */
fun Context.interstitialAd(
    adUnitId: String,
    autoLoad: Boolean = true,
    autoReloadAfterShow: Boolean = true,
    intervalBetweenAds: Long = 0L,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: AdsCallback? = null,
): Lazy<InterstitialAdHelper> = lazy {
    buildInterstitialHelper(
        this, lifecycleOwner,
        InterstitialAdConfig.simple(adUnitId, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

/** Waterfall variant of [interstitialAd]. */
fun Context.interstitialAdWaterfall(
    adUnitIds: List<String>,
    autoLoad: Boolean = true,
    autoReloadAfterShow: Boolean = true,
    intervalBetweenAds: Long = 0L,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: AdsCallback? = null,
): Lazy<InterstitialAdHelper> = lazy {
    buildInterstitialHelper(
        this, lifecycleOwner,
        InterstitialAdConfig.waterfall(adUnitIds, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

private fun buildInterstitialHelper(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    config: InterstitialAdConfig,
    autoLoad: Boolean,
    adCallback: AdsCallback?,
): InterstitialAdHelper = InterstitialAdHelper(context, lifecycleOwner, config).also { helper ->
    adCallback?.let { helper.registerAdListener(it) }
    if (autoLoad) helper.requestAds(InterstitialAdParam.Request)
}
