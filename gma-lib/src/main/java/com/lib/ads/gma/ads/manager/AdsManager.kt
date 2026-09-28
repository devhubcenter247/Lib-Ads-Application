package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.lib.ads.gma.ads.helper.banner.bindToContainer
import com.lib.ads.gma.ads.helper.banner.createBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdHolder
import com.lib.ads.gma.ads.helper.interstitial.*
import com.lib.ads.gma.ads.helper.reward.*
import com.lib.ads.gma.ads.model.wrapper.*
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.lib.ads.gma.ads.helper.adnative.NativeAdRequestOptions
import com.lib.ads.gma.ads.model.wrapper.AppOpenAdListener
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.gma.R

/** Small facade with the same intent as adlib's `AdsManager`. */
object AdsManager {
    private const val TAG = "AdsManager"

    internal const val BANNER_ADS = 2
    internal const val INTERS_ADS = 3
    internal const val REWARD_ADS = 4
    internal const val NATIVE_ADS = 5

    @OptIn(ExperimentalApi::class)
    fun getAdRequest(
        adUnitId: String,
        placementId: Long? = null,
        skipUninitializedAdapters: Boolean = false,
    ): AdRequest = AdRequest.Builder(adUnitId).apply {
        placementId?.let { setPlacementId(it) }
        if (skipUninitializedAdapters) skipUninitializedAdapters()
    }.build()

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun showTestIdAlert(context: Context, typeAds: Int, id: String) {
        val content = when (typeAds) {
            BANNER_ADS -> "Banner Ads: "
            INTERS_ADS -> "Interstitial Ads: "
            REWARD_ADS -> "Rewarded Ads: "
            NATIVE_ADS -> "Native Ads: "
            else -> ""
        } + id
        val notification = NotificationCompat.Builder(context, "warning_ads")
            .setContentTitle("Found test ad id")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_warning)
            .build()
        notification.flags = notification.flags or 0x10
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannel("warning_ads", "Warning Ads", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(typeAds, notification)
        }
        android.util.Log.e(TAG, "Found test ad id on debug : ${AppUtil.VARIANT_DEV}")
        check(AppUtil.VARIANT_DEV) { "Found test ad id on environment production. Id found: $id" }
    }

    /** Opens the SDK debug menu for an explicitly supplied test ad unit. */
    @JvmStatic
    fun openDebugMenu(activity: Activity, adUnitId: String) {
        MobileAds.openDebugMenu(activity, adUnitId)
    }

    /**
     * Shows a banner in [container] (XML), bound to [activity]'s lifecycle. The returned holder
     * exposes state/callbacks; [tag] shares a buffer with preloads registered under it.
     */
    @JvmStatic
    @JvmOverloads
    fun showBanner(
        activity: AppCompatActivity,
        container: FrameLayout,
        adUnitId: String,
        enabled: Boolean = true,
        callback: BannerAdListener? = null,
        placementId: Long? = null,
        tag: String = "ads_manager_banner:$adUnitId",
    ): BannerAdHolder {
        val holder = createBannerAdHolder(
            tag = tag,
            enabled = enabled,
            options = BannerAdPreloadHolderOptions(
                fallbackAdUnitIds = listOf(adUnitId),
                lifecycleOwner = activity,
                autoRequestOnStart = true,
                placementId = placementId,
            ),
        ).value
        callback?.let(holder::registerAdCallback)
        holder.bindToContainer(activity, container)
        return holder
    }

    @JvmStatic
    fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        adUnitId: String,
        enabled: Boolean = true,
        onComplete: () -> Unit,
        placementId: Long? = null,
    ) {
        if (!enabled) {
            onComplete()
            return
        }
        // onNextAction() is waitLoadAndShow's single fire-exactly-once-always signal — it fires
        // synchronously up front regardless of outcome (loaded, timed out, failed to show), so
        // it's the only reliable place to guarantee onComplete() actually runs. Wiring it to
        // onDismissed/onFailedToShow instead left onComplete() unreachable whenever the ad never
        // loaded at all (e.g. timeout) — the caller's flow would hang forever.
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        InterstitialAdHelper(
            activity,
            activity,
            InterstitialAdConfig(listOf(adUnitId), canShowAds = true, placementId = placementId),
        ).waitLoadAndShow(activity, callback = object : InterstitialAdListener {
            override fun onNextAction() { if (completed.compareAndSet(false, true)) onComplete() }
        })
    }

    @JvmStatic
    fun loadAndShowRewarded(
        activity: AppCompatActivity,
        adUnitId: String,
        enabled: Boolean = true,
        callback: RewardAdListener,
        placementId: Long? = null,
    ) {
        if (!enabled) {
            callback.onNotReady()
            return
        }
        RewardedAdHelper(
            activity,
            activity,
            RewardedAdConfig(listOf(adUnitId), canShowAds = true, placementId = placementId),
        ).waitLoadAndShow(activity, callback = callback)
    }

    @JvmStatic
    fun loadNative(
        activity: AppCompatActivity,
        container: FrameLayout,
        adUnitId: String,
        layoutId: Int,
        callback: NativeAdListener? = null,
        placementId: Long? = null,
    ) {
        NativeAdManager.loadNativeAdResultCallback(
            activity,
            adUnitId,
            layoutId,
            object : NativeAdListener {
                override fun onLoaded(nativeAd: ApNativeAd) {
                    activity.runOnUiThread {
                        container.removeAllViews()
                        NativeAdManager.populateNativeAdView(activity, nativeAd, container, null)
                        callback?.onLoaded(nativeAd)
                    }
                }

                override fun onFailed(error: ApAdError) {
                    callback?.onFailed(error)
                }

                override fun onClicked(ad: ApNativeAd) {
                    callback?.onClicked(ad)
                }

                override fun onImpression(ad: ApNativeAd) {
                    callback?.onImpression(ad)
                }
            },
            options = NativeAdRequestOptions().apply { placementId?.let(::setPlacementId) },
        )
    }

    @JvmStatic
    fun excludeAppOpen(vararg activityClasses: Class<out Activity>) {
        val manager = com.lib.ads.gma.ads.helper.appopen.AppOpenManager.getInstance()
        activityClasses.forEach(manager::disableAppResumeWithActivity)
    }

    @JvmStatic
    fun includeAppOpen(vararg activityClasses: Class<out Activity>) {
        val manager = com.lib.ads.gma.ads.helper.appopen.AppOpenManager.getInstance()
        activityClasses.forEach(manager::enableAppResumeWithActivity)
    }

    /**
     * Opts the **resume** app-open ad (shown when the user switches back into the app) out of
     * showing until [gate] returns `true` — e.g. `AdsManager.setAppOpenEligibilityGate { sessionCount >= 3 }`
     * to skip app-open ads during a new user's first sessions. Pass `null` to always allow
     * showing (the default). Does not affect the splash app-open ad shown on cold start — see
     * [loadSplashAppOpenAd]/[showSplashAppOpen] for that flow.
     */
    @JvmStatic
    fun setAppOpenEligibilityGate(gate: (() -> Boolean)?) {
        com.lib.ads.gma.ads.helper.appopen.AppOpenManager.getInstance().setEligibilityGate(gate)
    }

    /**
     * One-liner for the **splash** app-open ad (the ad shown on cold start, before the user's
     * first screen) — distinct from [loadSplashInterstitialAds]/[onShowSplash], which load a
     * splash *interstitial* instead. Delegates to [AppOpenAdManager], previously only reachable
     * by importing `com.lib.ads.application.ads.manager.AppOpenAdManager` directly.
     */
    @JvmStatic
    fun loadSplashAppOpenAd(
        id: String,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
        tag: String = "__splash_app_open__",
    ) = AppOpenAdManager.loadSplashAppOpenAd(id, timeOut, timeDelay, listener, skipUninitializedAdapters, placementId, tag)

    @Deprecated("Context is no longer required")
    @JvmStatic
    fun loadSplashAppOpenAd(
        context: Context,
        id: String,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
    ) = loadSplashAppOpenAd(id, timeOut, timeDelay, listener, skipUninitializedAdapters, placementId)

    @JvmStatic
    fun loadSplashAppOpenAds(
        ids: List<String>,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
        tag: String = "__splash_app_open__",
    ) = AppOpenAdManager.loadSplashAppOpenAds(ids, timeOut, timeDelay, listener, skipUninitializedAdapters, placementId, tag)

    @Deprecated("Context is no longer required")
    @JvmStatic
    fun loadSplashAppOpenAds(
        context: Context,
        ids: List<String>,
        timeOut: Long,
        timeDelay: Long,
        listener: AppOpenAdListener,
        skipUninitializedAdapters: Boolean = false,
        placementId: Long? = null,
    ) = loadSplashAppOpenAds(ids, timeOut, timeDelay, listener, skipUninitializedAdapters, placementId)

    @JvmStatic
    fun showSplashAppOpen(
        activity: Activity,
        listener: AppOpenAdListener,
        tag: String = "__splash_app_open__",
    ) = AppOpenAdManager.showSplashAppOpen(activity, listener, tag)

    fun getInterstitialAdsList(
        tag: String,
        ids: List<String>?,
        callback: InterstitialAdListener
    ) = InterstitialAdManager.getInterstitialAdsList(tag, ids, callback)

    @Deprecated("Context is no longer required")
    fun getInterstitialAdsList(context: Context, ids: List<String>?, callback: InterstitialAdListener) =
        getInterstitialAdsList("interstitial:${ids.orEmpty().lastOrNull().orEmpty()}", ids, callback)

    fun forceShowInterstitial(
        activity: Activity,
        ad: ApInterstitialAd,
        callback: InterstitialAdListener
    ) = InterstitialAdManager.forceShowInterstitial(activity, ad, callback)



    fun loadSplashInterstitialAds(
        id: String,
        timeOut: Long,
        timeDelay: Long,
        adListener: InterstitialAdListener
    ) = InterstitialAdManager.loadSplashInterstitialAds(id, timeOut, timeDelay, adListener)

    @Deprecated("Context is no longer required")
    fun loadSplashInterstitialAds(
        context: Context,
        id: String,
        timeOut: Long,
        timeDelay: Long,
        adListener: InterstitialAdListener,
    ) = loadSplashInterstitialAds(id, timeOut, timeDelay, adListener)

    fun loadSplashListAds(
        listId: List<String>,
        timeOut: Long,
        timeDelay: Long,
        adCallback: InterstitialAdListener
    ) = InterstitialAdManager.loadSplashListAds(listId, timeOut, timeDelay, adCallback)

    @Deprecated("Context is no longer required")
    fun loadSplashListAds(
        context: Context,
        listId: List<String>,
        timeOut: Long,
        timeDelay: Long,
        adCallback: InterstitialAdListener,
    ) = loadSplashListAds(listId, timeOut, timeDelay, adCallback)

    fun onShowSplash(activity: Activity, adListener: InterstitialAdListener) =
        InterstitialAdManager.onShowSplash(activity, adListener)

    fun onCheckShowSplashWhenFail(
        activity: Activity,
        callback: InterstitialAdListener,
        delay: Int
    ) = InterstitialAdManager.onCheckShowSplashWhenFail(activity, callback, delay)

    fun loadRewardAdList(
        tag: String,
        ids: List<String>?,
        callback: RewardAdListener
    ) = RewardAdManager.loadRewardAdList(tag, ids, callback)

    @Deprecated("Use the overload that accepts a placement tag")
    fun loadRewardAdList(context: Context, ids: List<String>?, callback: RewardAdListener) =
        loadRewardAdList("reward:${ids.orEmpty().lastOrNull().orEmpty()}", ids, callback)

    fun showRewardAd(activity: Activity, ad: ApRewardAd?, callback: RewardAdListener) =
        RewardAdManager.forceShowRewardAd(activity, ad, callback)

    /** Loads a rewarded waterfall into [tag]; show it later with [showRewardAd]. */
    fun loadRewardAd(
        tag: String,
        ids: List<String>,
        callback: RewardAdListener,
        placementId: Long? = null,
    ) = RewardAdManager.loadReward(tag, ids, callback, placementId)

    fun showRewardAd(
        activity: Activity,
        tag: String,
        callback: RewardAdListener,
    ) = RewardAdManager.showReward(activity, tag, callback)

    /** Loads an interstitial waterfall into [tag]; show it later with [showInterstitialAd]. */
    fun loadInterstitialAd(
        tag: String,
        ids: List<String>,
        callback: InterstitialAdListener,
        placementId: Long? = null,
    ) = InterstitialAdManager.loadInterstitial(tag, ids, callback, placementId)

    fun showInterstitialAd(
        activity: Activity,
        tag: String,
        callback: InterstitialAdListener,
    ) = InterstitialAdManager.showInterstitial(activity, tag, callback)
}
