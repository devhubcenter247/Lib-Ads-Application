package com.lib.ads.gma.ads.helper.interstitial

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener

fun Context.interstitialAd(
    adUnitId: String,
    lifecycleOwner: LifecycleOwner,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    adCallback: InterstitialAdListener? = null,
    preloadTag: String? = null,
): Lazy<InterstitialAdHelper> = lazy {
    InterstitialAdHelper(
        this,
        lifecycleOwner,
        InterstitialAdConfig.simple(adUnitId, loadTimeoutMs = loadTimeoutMs, canShowAds = canShowAds)
            .setPreloadTag(preloadTag)
    ).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.requestAds(InterstitialAdParam.Request)
    }
}

fun Context.interstitialAdWaterfall(
    adUnitIds: List<String>,
    lifecycleOwner: LifecycleOwner,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    adCallback: InterstitialAdListener? = null,
    preloadTag: String? = null,
): Lazy<InterstitialAdHelper> = lazy {
    InterstitialAdHelper(
        this,
        lifecycleOwner,
        InterstitialAdConfig.waterfall(adUnitIds, loadTimeoutMs = loadTimeoutMs, canShowAds = canShowAds)
            .setPreloadTag(preloadTag)
    ).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.requestAds(InterstitialAdParam.Request)
    }
}
