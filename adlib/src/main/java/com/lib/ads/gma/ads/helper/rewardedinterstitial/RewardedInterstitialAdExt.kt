package com.lib.ads.gma.ads.helper.rewardedinterstitial

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.ads.AdsCallback

// ──────────────────────────────────────────────
// One-liners — mirror of `nativeAd { }` / `bannerAd { }`
// ──────────────────────────────────────────────

/**
 * Create a [RewardedInterstitialAdHelper] in one call. Loading only needs a [Context] (the
 * application context is enough); showing needs an Activity passed to
 * [RewardedInterstitialAdHelper.forceShow].
 *
 * Returns a [Lazy] for `by` delegation. When [autoLoad] is true the ad starts loading on first
 * access. Pass [lifecycleOwner] to enable auto-cancel on destroy + auto-reload on resume; omit it
 * (default) and you drive everything yourself, releasing with [RewardedInterstitialAdHelper.destroy].
 *
 * ```kotlin
 * private val rewardInter by rewardedInterstitialAd(adUnitId = BuildConfig.ad_rewarded_inter, intervalBetweenAds = 30)
 *
 * // later (Activity context already known → no need to pass it)
 * rewardInter.forceShow(object : AdsCallback() {
 *     override fun onUserEarnedReward(rewardItem: ApRewardItem) { grantReward() }
 *     override fun onNextAction() { /* continue */ }
 * })
 * ```
 */
fun Context.rewardedInterstitialAd(
    adUnitId: String,
    autoLoad: Boolean = true,
    autoReloadAfterShow: Boolean = true,
    intervalBetweenAds: Long = 0L,
    loadTimeoutMs: Long = 30_000L,
    ssvCustomData: String? = null,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: AdsCallback? = null,
): Lazy<RewardedInterstitialAdHelper> = lazy {
    buildRewardedInterstitialHelper(
        this, lifecycleOwner,
        RewardedInterstitialAdConfig.simple(adUnitId, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, ssvCustomData, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

/** Waterfall variant of [rewardedInterstitialAd]. */
fun Context.rewardedInterstitialAdWaterfall(
    adUnitIds: List<String>,
    autoLoad: Boolean = true,
    autoReloadAfterShow: Boolean = true,
    intervalBetweenAds: Long = 0L,
    loadTimeoutMs: Long = 30_000L,
    ssvCustomData: String? = null,
    canShowAds: Boolean = true,
    canReloadAds: Boolean = true,
    lifecycleOwner: LifecycleOwner? = null,
    adCallback: AdsCallback? = null,
): Lazy<RewardedInterstitialAdHelper> = lazy {
    buildRewardedInterstitialHelper(
        this, lifecycleOwner,
        RewardedInterstitialAdConfig.waterfall(adUnitIds, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, ssvCustomData, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

private fun buildRewardedInterstitialHelper(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    config: RewardedInterstitialAdConfig,
    autoLoad: Boolean,
    adCallback: AdsCallback?,
): RewardedInterstitialAdHelper = RewardedInterstitialAdHelper(context, lifecycleOwner, config).also { helper ->
    adCallback?.let { helper.registerAdListener(it) }
    if (autoLoad) helper.requestAds(RewardedInterstitialAdParam.Request)
}
