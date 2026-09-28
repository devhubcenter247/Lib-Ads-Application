package com.lib.ads.gma.app

import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.application.AdsMultiDexApplication
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.util.AppLogger

class GmaApplication : AdsMultiDexApplication() {

    override fun createAdSdkConfig(): AdSdkConfig = AdSdkConfig(
        this,
        AdSdkConfig.ENVIRONMENT_DEVELOP,
    ).apply {
        appAdId = getString(R.string.admod_app_id)
        listIdAdResume = listOf(BuildConfig.ads_open_app)
        showMessageForTester = true
        listDeviceTest = listOf(
            "9B96D6E1B898DDB9D135BA906DB8CD63"
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.isEnabled= true
    }

}
