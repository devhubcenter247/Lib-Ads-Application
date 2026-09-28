package com.lib.ads.gma.ads.helper.appopen

import android.app.Application
import android.content.Context

/**
 * Create an [AppOpenAdHelper] for manual splash / app-open flows.
 *
 * [autoInitialize] is false by default because [AppOpenAdHelper.initialize] registers process
 * lifecycle callbacks for app-resume ads. For splash-only usage, call [AppOpenAdHelper.waitLoadAndShow]
 * directly without enabling resume behavior.
 */
fun Application.appOpenAd(
    adUnitId: String,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoInitialize: Boolean = false,
): Lazy<AppOpenAdHelper> = lazy {
    buildAppOpenHelper(
        this,
        AppOpenAdConfig(adUnitId, canShowAds).apply {
            setLoadTimeout(loadTimeoutMs)
            setMaxAdAgeHours(maxAdAgeHours)
            setSplashMinDelay(splashMinDelayMs)
            showDelay = showDelayMs
        },
        autoInitialize,
    )
}

/**
 * Activity / Context-friendly variant of [appOpenAd], matching the style of
 * `interstitialAdWaterfall(...)`.
 */
fun Context.appOpenAd(
    adUnitId: String,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoInitialize: Boolean = false,
): Lazy<AppOpenAdHelper> = lazy {
    buildAppOpenHelper(
        requireApplication(),
        AppOpenAdConfig(adUnitId, canShowAds).apply {
            setLoadTimeout(loadTimeoutMs)
            setMaxAdAgeHours(maxAdAgeHours)
            setSplashMinDelay(splashMinDelayMs)
            showDelay = showDelayMs
        },
        autoInitialize,
    )
}

/** Waterfall variant of [appOpenAd]. */
fun Application.appOpenAdWaterfall(
    adUnitIds: List<String>,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoInitialize: Boolean = false,
): Lazy<AppOpenAdHelper> = lazy {
    buildAppOpenHelper(
        this,
        AppOpenAdConfig(adUnitIds, canShowAds).apply {
            setLoadTimeout(loadTimeoutMs)
            setMaxAdAgeHours(maxAdAgeHours)
            setSplashMinDelay(splashMinDelayMs)
            showDelay = showDelayMs
        },
        autoInitialize,
    )
}

/** Activity / Context-friendly waterfall variant of [appOpenAd]. */
fun Context.appOpenAdWaterfall(
    adUnitIds: List<String>,
    canShowAds: Boolean = true,
    loadTimeoutMs: Long = 30_000L,
    maxAdAgeHours: Int = 4,
    splashMinDelayMs: Long = 3_000L,
    showDelayMs: Long = 800L,
    autoInitialize: Boolean = false,
): Lazy<AppOpenAdHelper> = lazy {
    buildAppOpenHelper(
        requireApplication(),
        AppOpenAdConfig(adUnitIds, canShowAds).apply {
            setLoadTimeout(loadTimeoutMs)
            setMaxAdAgeHours(maxAdAgeHours)
            setSplashMinDelay(splashMinDelayMs)
            showDelay = showDelayMs
        },
        autoInitialize,
    )
}

private fun buildAppOpenHelper(
    application: Application,
    config: AppOpenAdConfig,
    autoInitialize: Boolean,
): AppOpenAdHelper = AppOpenAdHelper(application, config).also { helper ->
    if (autoInitialize) helper.initialize()
}

private fun Context.requireApplication(): Application =
    applicationContext as? Application
        ?: error("AppOpenAdHelper requires an Application context")
