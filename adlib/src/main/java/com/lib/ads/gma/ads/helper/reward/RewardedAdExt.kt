package com.lib.ads.gma.ads.helper.reward

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.ads.AdsCallback

/**
 * Create a rewarded ad outside Compose. Loading only needs a [Context] (the application context
 * is enough); showing needs an Activity passed to [RewardedAdHelper.forceShow].
 *
 * Pass [lifecycleOwner] to enable auto-cancel on destroy + auto-reload on resume. Omit it
 * (default) and you drive everything yourself: load via [autoLoad], show with
 * `forceShow(callback, activity)`, and release with [RewardedAdHelper.destroy].
 */
fun Context.rewardedAd(
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
): Lazy<RewardedAdHelper> = lazy {
    buildRewardedHelper(
        this, lifecycleOwner,
        RewardedAdConfig.simple(adUnitId, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, ssvCustomData, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

fun Context.rewardedAdWaterfall(
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
): Lazy<RewardedAdHelper> = lazy {
    buildRewardedHelper(
        this, lifecycleOwner,
        RewardedAdConfig.waterfall(adUnitIds, autoReloadAfterShow, intervalBetweenAds, loadTimeoutMs, ssvCustomData, canShowAds, canReloadAds),
        autoLoad, adCallback
    )
}

private fun buildRewardedHelper(
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    config: RewardedAdConfig,
    autoLoad: Boolean,
    adCallback: AdsCallback?,
): RewardedAdHelper = RewardedAdHelper(context, lifecycleOwner, config).also { helper ->
    adCallback?.let { helper.registerAdListener(it) }
    if (autoLoad) helper.requestAds(RewardedAdParam.Request)
}
