package com.lib.ads.gma.ads.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.webkit.WebView
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.initialization.AdapterInitializationConfig
import com.lib.ads.gma.ads.helper.appopen.AppOpenManager
import com.lib.ads.gma.ads.config.AdSdkConfig
import com.lib.ads.gma.ads.event.AdsAdjust
import com.lib.ads.gma.ads.event.AdsAppsflyer
import com.lib.ads.gma.ads.util.AppLogger
import com.lib.ads.gma.ads.util.AppUtil
import com.adjust.sdk.Adjust
import com.adjust.sdk.LogLevel
import java.util.concurrent.atomic.AtomicBoolean
import com.adjust.sdk.AdjustConfig as AdjustSdkConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * SDK lifecycle only: configuration, Google Mobile Ads / Adjust initialization, and readiness
 * signaling. Per-format ad loading/showing lives in the `manager` package (InterstitialAdManager,
 * RewardAdManager, BannerAdManager, NativeAdManager) and shared cross-cutting helpers live in
 * [com.lib.ads.gma.ads.manager.AdsManager] — mirrors adlib's Ads/AdsManager split.
 */
open class AdsProvider private constructor() {
    lateinit var adConfig: AdSdkConfig
        private set
    private lateinit var application: Application
    private lateinit var appAdId: String

    /** Enables verbose ad lifecycle logs. Disabled by default for production builds. */
    var isEnableAdsLog: Boolean = false
        set(value) {
            field = value
            AppLogger.isEnabled = value
            AppLogger.i(TAG, "Ads lifecycle logging: $value")
        }

    fun setAdsLogEnabled(enabled: Boolean) {
        isEnableAdsLog = enabled
    }

    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    @Volatile
    private var deferredInitializer: (() -> Unit)? = null
    private val initializationStarted = AtomicBoolean(false)
    private val mobileAdsReady = CompletableDeferred<Unit>()
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isConfigured() =
        ::adConfig.isInitialized && ::application.isInitialized && ::appAdId.isInitialized

    /** Application context for helpers that need to issue a normal SDK request off-screen. */
    internal fun applicationContextOrNull(): android.content.Context? =
        if (::application.isInitialized) application else null

    val adConfigOrNull: AdSdkConfig?
        get() = if (::adConfig.isInitialized) adConfig else null

    fun isMobileAdsReady() = mobileAdsReady.isCompleted

    /** Suspends until GMA initialization completes, returning false on timeout. */
    suspend fun awaitReadySuspend(timeoutMs: Long = 15_000L): Boolean {
        if (mobileAdsReady.isCompleted) return true
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L).milliseconds) {
            suspendCancellableCoroutine { continuation ->
                mobileAdsReady.invokeOnCompletion {
                    if (continuation.isActive) continuation.resume(true)
                }
            }
        } ?: false
    }

    /** Callback variant for Java/Kotlin callers that do not use coroutines. */
    fun awaitReady(timeoutMs: Long = 15_000L, onResult: (isSuccess: Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            onResult(awaitReadySuspend(timeoutMs))
        }
    }

    fun runWhenReady(callback: () -> Unit) {
        // invokeOnCompletion is race-safe: if initialization completes before or after
        // registration, the callback is still delivered exactly once on the main thread.
        mobileAdsReady.invokeOnCompletion {
            runOnMain(callback)
        }
    }

    private fun configure(application: Application, adConfig: AdSdkConfig) {
        this.application = application
        this.adConfig = adConfig
        val appAdId =
            adConfig.appAdId ?: throw IllegalArgumentException("app ad id can be not null")
        this.appAdId = appAdId
        AppUtil.VARIANT_DEV = adConfig.isVariantDev
        AppLogger.i(TAG, "Config variant dev: ${AppUtil.VARIANT_DEV}")
        // Request configuration (test device ids, age-restricted treatment, ...) is applied after
        // MobileAds.initialize() completes, in initAdsNetwork() — the GMA Next-Gen SDK throws
        // IllegalStateException("MobileAds.initialize must be called before using the Google
        // Mobile Ads SDK.") if setRequestConfiguration() runs any earlier.
        adConfig.adjustConfig?.let {
            if (it.enableAdjust) setupAdjust(
                adConfig.isVariantDev,
                it.adjustToken,
                it.fbAppId
            )
        }
        adConfig.appsflyerConfig?.let {
            if (it.enableAppsflyer) {
                AdsAppsflyer.connect(
                    application = application,
                    devKey = it.devKey,
                    debug = adConfig.isVariantDev,
                    events = it.events,
                    alreadyInitialized = it.alreadyInitialized,
                )
            }
        }
    }

    private fun applyRequestConfiguration() {
        adConfig.requestConfiguration?.let(::setRequestConfiguration)
            ?: adConfig.listDeviceTest
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { deviceIds ->
                    setRequestConfiguration(
                        RequestConfiguration.Builder()
                            .setTestDeviceIds(deviceIds)
                            .build()
                    )
                }
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(
        application: Application,
        config: AdSdkConfig,
        onReady: (() -> Unit)? = null,
    ) {
        requireNotNull(config.appAdId) { "Ad SDK application ID is required" }
        if (!isMobileAdsInitializeCalled.compareAndSet(false, true)) {
            // The first initialization may still be running. Do not call the callback early.
            onReady?.let(::runWhenReady)
            return
        }

        initializationScope.launch {
            configure(application, config)
            initAdsNetwork {
                onReady?.invoke()
            }
        }
    }

    fun setRequestConfiguration(configuration: RequestConfiguration) {
        MobileAds.setRequestConfiguration(configuration)
    }

    fun isInitialized(): Boolean = isMobileAdsReady()

    /** Registered by [com.lib.ads.gma.ads.application.AdsMultiDexApplication] so callers can start a deferred initialization. */
    internal fun setDeferredInitializer(initializer: () -> Unit) {
        deferredInitializer = initializer
    }

    /**
     * Starts the deferred initialization if the app turned off `initializeAdsOnCreate()` and has
     * not initialized yet. Safe to call repeatedly; does nothing once initialization was requested.
     */
    fun initializeIfNeeded() {
        if (isMobileAdsInitializeCalled.get()) return
        val initializer = deferredInitializer
        if (initializer == null) {
            AppLogger.w(TAG, "initializeIfNeeded: no deferred initializer; call AdsMultiDexApplication.initializeAds()")
            return
        }
        AppLogger.d(TAG, "initializeIfNeeded: starting deferred initialization")
        initializer()
    }

    fun isFullScreenAdShowing(): Boolean = adConfigOrNull?.fullScreenAdShowing == true

    fun setFullScreenAdShowing(showing: Boolean) {
        adConfigOrNull?.fullScreenAdShowing = showing
        AppOpenManager.getInstance().isInterstitialShowing = showing
    }

    fun whenReady(action: () -> Unit) = runWhenReady(action)


    @OptIn(ExperimentalApi::class)
    private fun initAdsNetwork(initFinished: () -> Unit) {
        runWhenReady(initFinished)
        if (!isConfigured()) {
            AppLogger.w(TAG, "initAdsNetwork called before Ads.initialize(); callback queued")
            return
        }
        AppLogger.d(TAG, "initAdsNetwork")
        initGma(
            application,
            appAdId,
            adConfig.adapterInitializationConfig
        ) {
            applyRequestConfiguration()
            mobileAdsReady.complete(Unit)
            if (adConfig.isEnableAdResume()) {
                val listIdAdResume = adConfig.listIdAdResume
                val idAdResume = adConfig.idAdResume
                if (!listIdAdResume.isNullOrEmpty()) {
                    AppOpenManager.getInstance()
                        .init(adConfig.application, listIdAdResume, placementId = adConfig.placementIdAdResume)
                } else if (!idAdResume.isNullOrEmpty()) {
                    AppOpenManager.getInstance()
                        .init(adConfig.application, idAdResume, placementId = adConfig.placementIdAdResume)
                }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    internal fun initGma(
        application: Application,
        appAdId: String,
        adapterInitializationConfig: AdapterInitializationConfig? = null,
        onInitialized: (() -> Unit)? = null,
    ) {
        require(appAdId.isNotBlank()) { "Google Mobile Ads application ID must not be blank" }
        if (Build.VERSION.SDK_INT >= 28) {
            val processName = Application.getProcessName()
            if (application.packageName != processName) WebView.setDataDirectorySuffix(processName)
        }
        if (!initializationStarted.compareAndSet(false, true)) {
            // A second caller must wait for the real SDK callback.
            onInitialized?.let(::runWhenReady)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val builder = InitializationConfig.Builder(appAdId)
            adapterInitializationConfig?.let(builder::setAdapterInitializationConfig)
            if(adConfig.isDisableValidator){
                builder.setNativeValidatorDisabled()
            }
            MobileAds.initialize(application, builder.build()) {
                runOnMain { onInitialized?.invoke() }
            }
        }
    }

    private fun setupAdjust(buildDebug: Boolean, adjustToken: String, fbAppId: String) {
        AdsAdjust.enableAdjust = true
        val environment =
            if (buildDebug) AdjustSdkConfig.ENVIRONMENT_SANDBOX else AdjustSdkConfig.ENVIRONMENT_PRODUCTION
        AppLogger.i(TAG_ADJUST, "setupAdjust: $environment")
        val config = AdjustSdkConfig(application, adjustToken, environment)
        config.setLogLevel(LogLevel.VERBOSE)
        config.setOnAttributionChangedListener { attribution ->
            AppLogger.d(TAG_ADJUST, "Attribution callback called!")
            AppLogger.d(TAG_ADJUST, "Attribution: $attribution")
        }
        config.setOnEventTrackingSucceededListener { success ->
            AppLogger.d(TAG_ADJUST, "Event success callback called!")
            AppLogger.d(TAG_ADJUST, "Event success data: $success")
        }
        config.setOnEventTrackingFailedListener { failure ->
            AppLogger.d(TAG_ADJUST, "Event failure callback called!")
            AppLogger.d(TAG_ADJUST, "Event failure data: $failure")
        }
        config.setOnSessionTrackingSucceededListener { success ->
            AppLogger.d(TAG_ADJUST, "Session success callback called!")
            AppLogger.d(TAG_ADJUST, "Session success data: $success")
        }
        config.setOnSessionTrackingFailedListener { failure ->
            AppLogger.d(TAG_ADJUST, "Session failure callback called!")
            AppLogger.d(TAG_ADJUST, "Session failure data: $failure")
        }
        config.enableSendingInBackground()
        config.fbAppId = fbAppId
        Adjust.initSdk(config)
        application.registerActivityLifecycleCallbacks(AdjustLifecycleCallbacks())
    }

    private class AdjustLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = Adjust.onResume()
        override fun onActivityPaused(activity: Activity) = Adjust.onPause()
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
    }

    companion object {
        const val TAG_ADJUST = "AdsAdjust"
        const val TAG = "Ads"

        internal val MAIN = Handler(Looper.getMainLooper())

        /** Executes immediately on main; posts only when called from another thread. */
        internal fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() === Looper.getMainLooper()) block() else MAIN.post(block)
        }

        @Volatile
        private var INSTANCE: AdsProvider? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): AdsProvider = INSTANCE ?: AdsProvider().also { INSTANCE = it }
    }
}

/** Runs [block] once the SDK is ready, always on the main thread. Doesn't need an `Ads` receiver. */
fun whenAdsReady(block: () -> Unit) = AdsProvider.getInstance().runWhenReady(block)
