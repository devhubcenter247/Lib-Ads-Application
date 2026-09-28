package com.lib.ads.gma.ads.application

import androidx.multidex.MultiDexApplication
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.event.FirebaseAnalytics
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.SharePreferenceUtils

/**
 * Base application class with MultiDex support and ad SDK initialization.
 */
abstract class AdsMultiDexApplication : MultiDexApplication() {

    protected lateinit var adSdkConfig: AdSdkConfig
    protected var listTestDevice: MutableList<String> = mutableListOf()

    override fun onCreate() {
        super.onCreate()
        adSdkConfig = AdSdkConfig(this)
        if (SharePreferenceUtils.getInstallTime(this) == 0L) {
            SharePreferenceUtils.setInstallTime(this)
        }
        FirebaseAnalytics.init(this)
        AppUtil.currentTotalRevenue001Ad = SharePreferenceUtils.getCurrentTotalRevenue001Ad(this)
    }
}
