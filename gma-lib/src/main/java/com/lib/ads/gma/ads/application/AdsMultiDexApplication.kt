package com.lib.ads.gma.ads.application

import android.app.ActivityManager
import android.os.Build
import android.os.Process
import androidx.multidex.MultiDexApplication
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.google.android.ump.UserMessagingPlatform
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.event.FirebaseAnalyticsUtil
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.banner.BannerAds
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.SharePreferenceUtils

/** Shared application bootstrap for apps using the GMA library. */
@OptIn(ExperimentalApi::class)
abstract class AdsMultiDexApplication : MultiDexApplication() {

    private lateinit var adSdkConfig: AdSdkConfig
   /** Build the complete SDK config before the single automatic initialize call. */
    protected abstract fun createAdSdkConfig(): AdSdkConfig

    /**
     * Override when UMP consent must be collected before GMA initialization. Even then, GMA is
     * initialized here when consent was already given in an earlier session — a process restored
     * straight into a non-splash screen would otherwise run the whole session without ads.
     */
    protected open fun initializeAdsOnCreate(): Boolean = true

    /** Starts Adjust / AppsFlyer in onCreate, independent of GMA. Override to defer them yourself. */
    protected open fun initializeAttributionOnCreate(): Boolean = true

    fun initializeAds(onReady: (() -> Unit)? = null) {
        // Not configured in a secondary process (see onCreate).
        if (!::adSdkConfig.isInitialized) return
        AdsProvider.getInstance().initialize(this, adSdkConfig, onReady)
    }

    override fun onCreate() {
        super.onCreate()
        // A secondary process (one declared by the app or by a mediation SDK) must not start ads,
        // attribution or Firebase a second time: duplicate sessions/events and wasted startup.
        if (!isMainProcess()) return
        adSdkConfig = createAdSdkConfig()
        check(!adSdkConfig.appAdId.isNullOrBlank()) {
            "AdsMultiDexApplication.createAdSdkConfig() must provide appAdId"
        }
        // Apply the environment synchronously: Ads.initialize() configures on a coroutine and may
        // be deferred (consent-first apps), but billing reads VARIANT_DEV as soon as it starts.
        AppUtil.VARIANT_DEV = adSdkConfig.isVariantDev
        if (adSdkConfig.enableLog) AdsProvider.getInstance().isEnableAdsLog = true
        if (SharePreferenceUtils.getInstallTime(this) == 0L) {
            SharePreferenceUtils.setInstallTime(this)
        }
        FirebaseAnalyticsUtil.init(this)

        if (initializeAttributionOnCreate()) {
            AdsProvider.getInstance().initAttribution(this, adSdkConfig)
        }
        AdsProvider.getInstance().setDeferredInitializer { initializeAds() }
        if (initializeAdsOnCreate() || hasStoredAdConsent()) {
            initializeAds()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The app is in the background and the system wants memory back: drop buffered native and
        // banner ads (views + media). Full-screen ads are small and kept.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            NativeAds.trimBuffers()
            BannerAds.trimBuffers()
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        // Unknown name: assume main rather than skip initialization altogether.
        return name == null || name == packageName
    }

    private fun hasStoredAdConsent(): Boolean =
        runCatching { UserMessagingPlatform.getConsentInformation(this).canRequestAds() }.getOrDefault(false)
}
