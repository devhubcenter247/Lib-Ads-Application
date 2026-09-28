package com.lib.ads.gma.app

import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.application.AdsMultiDexApplication
import com.lib.ads.gma.ads.config.AdSdkConfig

/** Legacy application name retained, but now initialized through the public GMA API. */
class MyApplication : AdsMultiDexApplication() {
    override fun createAdSdkConfig(): AdSdkConfig = AdSdkConfig(
        this,
        if (BuildConfig.env_dev) AdSdkConfig.ENVIRONMENT_DEVELOP else AdSdkConfig.ENVIRONMENT_PRODUCTION,
    ).apply {
        appAdId = getString(R.string.admod_app_id)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MyApplication
            private set
        fun getApplication(): MyApplication = instance
    }
}
