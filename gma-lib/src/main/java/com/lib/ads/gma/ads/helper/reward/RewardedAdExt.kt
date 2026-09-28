package com.lib.ads.gma.ads.helper.reward

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.model.wrapper.RewardAdListener

fun Context.rewardedAd(
    adUnitId: String,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: RewardAdListener? = null,
    preloadTag: String? = null,
): Lazy<RewardedAdHelper> = lazy {
    RewardedAdHelper(this, lifecycleOwner, RewardedAdConfig(listOf(adUnitId), loadTimeoutMs, canShowAds, preloadTag = preloadTag)).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.load()
    }
}

fun Context.rewardedAdWaterfall(
    adUnitIds: List<String>,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: RewardAdListener? = null,
    preloadTag: String? = null,
): Lazy<RewardedAdHelper> = lazy {
    RewardedAdHelper(this, lifecycleOwner, RewardedAdConfig(adUnitIds, loadTimeoutMs, canShowAds, preloadTag = preloadTag)).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.load()
    }
}

fun Context.rewardedInterstitialAd(
    adUnitId: String,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: RewardAdListener? = null,
    preloadTag: String? = null,
): Lazy<RewardedInterstitialAdHelper> = lazy {
    RewardedInterstitialAdHelper(
        this,
        lifecycleOwner,
        RewardedAdConfig(listOf(adUnitId), loadTimeoutMs, canShowAds, preloadTag = preloadTag),
    ).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.load()
    }
}

fun Context.rewardedInterstitialAdWaterfall(
    adUnitIds: List<String>,
    autoLoad: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    canShowAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: RewardAdListener? = null,
    preloadTag: String? = null,
): Lazy<RewardedInterstitialAdHelper> = lazy {
    RewardedInterstitialAdHelper(
        this,
        lifecycleOwner,
        RewardedAdConfig(adUnitIds, loadTimeoutMs, canShowAds, preloadTag = preloadTag),
    ).also {
        adCallback?.let(it::registerAdListener)
        if (autoLoad) it.load()
    }
}
