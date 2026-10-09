package com.lib.ads.gma.ads.application

import androidx.multidex.MultiDexApplication
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.event.FirebaseAnalyticsUtil
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.SharePreferenceUtils

/** Shared application bootstrap for apps using the GMA library. */
@OptIn(ExperimentalApi::class)
abstract class AdsMultiDexApplication : MultiDexApplication() {

    private lateinit var adSdkConfig: AdSdkConfig
   /** Build the complete SDK config before the single automatic initialize call. */
    protected abstract fun createAdSdkConfig(): AdSdkConfig

    /** Override when UMP consent must be collected before GMA initialization. */
    protected open fun initializeAdsOnCreate(): Boolean = true

    fun initializeAds(onReady: (() -> Unit)? = null) {
        AdsProvider.getInstance().initialize(this, adSdkConfig, onReady)
    }

    override fun onCreate() {
        super.onCreate()
        adSdkConfig = createAdSdkConfig()
        check(!adSdkConfig.appAdId.isNullOrBlank()) {
            "AdsMultiDexApplication.createAdSdkConfig() must provide appAdId"
        }
        // Apply the environment synchronously: Ads.initialize() configures on a coroutine and may
        // be deferred (consent-first apps), but billing reads VARIANT_DEV as soon as it starts.
        AppUtil.VARIANT_DEV = adSdkConfig.isVariantDev
        if (adSdkConfig.enableLog) AdsProvider.getInstance().isEnableAdsLog = true
        AdsProvider.getInstance().setDeferredInitializer { initializeAds() }
        if (initializeAdsOnCreate()) {
            initializeAds()
        }
        if (SharePreferenceUtils.getInstallTime(this) == 0L) {
            SharePreferenceUtils.setInstallTime(this)
        }
        FirebaseAnalyticsUtil.init(this)

        AppUtil.currentTotalRevenue001Ad =
            SharePreferenceUtils.getCurrentTotalRevenue001Ad(this)
    }
}
