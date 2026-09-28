package com.lib.ads.gma.ads.helper.appopen

import android.app.Activity
import android.content.Context

fun appOpenAd(
    adUnitId: String,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoPreload: Boolean = false,
    tagConfig: String? = adUnitId,
): Lazy<AppOpenAdHelper> = lazy {
    AppOpenAdHelper(
        AppOpenAdConfig(
            tagConfig = tagConfig,
            listId = listOf(adUnitId),
            canShowAds = canShowAds,
            loadTimeout = loadTimeoutMs,
            splashMinDelay = splashMinDelayMs,
            showDelay = showDelayMs,
            maxAdAgeHours = maxAdAgeHours,
        )
    ).also { if (autoPreload) it.preloadAd() }
}

fun appOpenAdWaterfall(
    adUnitIds: List<String>,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoPreload: Boolean = false,
    tagConfig: String? = adUnitIds.firstOrNull(),
): Lazy<AppOpenAdHelper> = lazy {
    AppOpenAdHelper(
        AppOpenAdConfig(
            tagConfig = tagConfig,
            listId = adUnitIds,
            canShowAds = canShowAds,
            loadTimeout = loadTimeoutMs,
            splashMinDelay = splashMinDelayMs,
            showDelay = showDelayMs,
            maxAdAgeHours = maxAdAgeHours,
        )
    ).also { if (autoPreload) it.preloadAd() }
}
