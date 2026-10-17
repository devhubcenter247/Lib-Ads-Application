package com.lib.ads.gma.app

import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.application.AdsMultiDexApplication
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.config.AdjustConfig
import com.lib.ads.gma.ads.config.AppsflyerConfig
import com.lib.ads.gma.ads.util.AppLogger

class GmaApplication : AdsMultiDexApplication() {

    /**
     * Consent-first: on the first launch GMA starts after the UMP form (SplashActivity calls
     * AdsProvider.initializeIfNeeded()); on later launches the stored consent starts it here.
     * Adjust / AppsFlyer start in onCreate either way.
     */
    override fun initializeAdsOnCreate(): Boolean = false

    override fun createAdSdkConfig(): AdSdkConfig = AdSdkConfig(
        this,
        if (BuildConfig.env_dev) AdSdkConfig.ENVIRONMENT_DEVELOP else AdSdkConfig.ENVIRONMENT_PRODUCTION,
    ).apply {
        appAdId = getString(R.string.admod_app_id)
        listIdAdResume = listOf(BuildConfig.ads_open_app)
        showMessageForTester = true
        listDeviceTest = listOf(
            "9B96D6E1B898DDB9D135BA906DB8CD63"
        )
        if (BuildConfig.adjust_token.isNotBlank()) {
            adjustConfig = AdjustConfig(enableAdjust = true, adjustToken = BuildConfig.adjust_token).apply {
                // Adjust event *token* from the dashboard, not an event name.
                eventNamePurchase = BuildConfig.adjust_event_purchase
            }
        }
        if (BuildConfig.af_dev_key.isNotBlank()) {
            appsflyerConfig = AppsflyerConfig(enableAppsflyer = true, devKey = BuildConfig.af_dev_key)
        }
        // Per-ad-unit load time / failure events while developing; opt-in, off in production.
        logAdHealthEvents = BuildConfig.env_dev
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.isEnabled= true
    }

}
