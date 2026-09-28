package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.LogLevel
import com.google.android.gms.ads.AgeRestrictedTreatment
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.lib.ads.gma.ads.admob.AppOpenManager
import com.lib.ads.gma.ads.ads.AdInitCallback
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.event.AdAppsflyer
import com.lib.ads.gma.ads.util.AppUtil
import java.util.concurrent.atomic.AtomicBoolean

object AdSdkInitializer {
    private const val TAG = "AdSdkInitializer"
    private const val TAG_ADJUST = "AzAdjust"

    var adConfig: AdSdkConfig? = null
        private set
    private var initCallback: AdInitCallback? = null
    private var initAdSuccess = false
    private var application: Application? = null
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    fun init(application: Application, adConfig: AdSdkConfig?) {
        if (adConfig == null) {
            throw RuntimeException("can not set AzAdConfig null")
        }
        this.application = application
        this.adConfig = adConfig
        AppUtil.VARIANT_DEV = adConfig.isVariantDev()
        Log.i(TAG, "Config variant dev: ${AppUtil.VARIANT_DEV}")

        setupAppsflyer(application)
        adConfig.adjustConfig?.let { adjustConfig ->
            setupAdjust(adConfig.isVariantDev(), adjustConfig.adjustToken, adjustConfig.fbAppId)
        }
    }

    fun initAdsNetwork() {
        val config = adConfig ?: return
        val app = application ?: return

        if (!isMobileAdsInitializeCalled.compareAndSet(false, true)) {
            return
        }

        Log.d(TAG, "initAdsNetwork")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()

            if (app.packageName != processName) {
                WebView.setDataDirectorySuffix(processName)
            }
        }

        val ageRestrictedTreatment = when {
            config.tagForChildDirectedTreatment == 1 ||
                    config.tagForUnderAgeOfConsent == 1 -> {
                AgeRestrictedTreatment.CHILD
            }
            else -> AgeRestrictedTreatment.UNSPECIFIED
        }

        val requestConfig = RequestConfiguration.Builder()
            .setTestDeviceIds(config.listDeviceTest)
            .setAgeRestrictedTreatment(ageRestrictedTreatment)
            .also { builder ->
                config.tagForChildDirectedTreatment?.let {
                    builder.setTagForChildDirectedTreatment(it)
                }

                config.tagForUnderAgeOfConsent?.let {
                    builder.setTagForUnderAgeOfConsent(it)
                }

                config.maxAdContentRating?.let {
                    builder.setMaxAdContentRating(it)
                }

                config.publisherPrivacyPersonalizationState?.let {
                    builder.setPublisherPrivacyPersonalizationState(it)
                }
            }
            .build()

        MobileAds.setRequestConfiguration(requestConfig)

        MobileAds.initialize(app) { initializationStatus ->
            initializationStatus.adapterStatusMap.forEach { (adapterClass, status) ->
                Log.d(
                    TAG,
                    "Adapter: $adapterClass, " +
                            "Description: ${status.description}, " +
                            "Latency: ${status.latency}ms"
                )
            }

            if (config.isEnableAdResume()) {
                val listIds = config.listIdAdResume
                val singleId = config.idAdResume

                when {
                    !listIds.isNullOrEmpty() -> {
                        AppOpenManager.getInstance()
                            .init(config.application, listIds)
                    }

                    !singleId.isNullOrEmpty() -> {
                        AppOpenManager.getInstance()
                            .init(config.application, singleId)
                    }
                }
            }

            initAdSuccess = true
            initCallback?.initAdSuccess()
        }
    }

    fun setInitCallback(initCallback: AdInitCallback?) {
        this.initCallback = initCallback
        if (initAdSuccess) {
            initCallback?.initAdSuccess()
        }
    }

    private fun setupAppsflyer(context: Application) {
        val config = adConfig ?: return
        if (config.isEnableAppsflyer()) {
            Log.i(TAG, "init appsflyer:")
            AdAppsflyer.enableAppsflyer = true
            config.appsflyerConfig?.appsflyerToken?.let { token ->
                AdAppsflyer.getInstance().init(context, token, config.isVariantDev())
            }
        }
    }

    private fun setupAdjust(buildDebug: Boolean, adjustToken: String?, fbAppId: String?) {
        val config = adConfig?.adjustConfig ?: return
        if (!config.enableAdjust) return

        val environment = if (buildDebug) AdjustConfig.ENVIRONMENT_SANDBOX else AdjustConfig.ENVIRONMENT_PRODUCTION
        Log.i(TAG_ADJUST, "setupAdjust: $environment")

        val adjustConfig = AdjustConfig(adConfig?.application, adjustToken, environment).apply {
            setLogLevel(LogLevel.VERBOSE)
            setOnAttributionChangedListener { attribution ->
                Log.d(TAG_ADJUST, "Attribution callback called!")
                Log.d(TAG_ADJUST, "Attribution: $attribution")
            }
            setOnEventTrackingSucceededListener { adjustEventSuccess ->
                Log.d(TAG_ADJUST, "Event success callback called!")
                Log.d(TAG_ADJUST, "Event success data: $adjustEventSuccess")
            }
            setOnEventTrackingFailedListener { adjustEventFailure ->
                Log.d(TAG_ADJUST, "Event failure callback called!")
                Log.d(TAG_ADJUST, "Event failure data: $adjustEventFailure")
            }
            setOnSessionTrackingSucceededListener { adjustSessionSuccess ->
                Log.d(TAG_ADJUST, "Session success callback called!")
                Log.d(TAG_ADJUST, "Session success data: $adjustSessionSuccess")
            }
            setOnSessionTrackingFailedListener { adjustSessionFailure ->
                Log.d(TAG_ADJUST, "Session failure callback called!")
                Log.d(TAG_ADJUST, "Session failure data: $adjustSessionFailure")
            }
            enableSendingInBackground()
            setFbAppId(fbAppId)
        }

        Adjust.initSdk(adjustConfig)
        adConfig?.application?.registerActivityLifecycleCallbacks(AdjustLifecycleCallbacks())
    }

    private class AdjustLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { Adjust.onResume() }
        override fun onActivityPaused(activity: Activity) { Adjust.onPause() }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
    }
}
