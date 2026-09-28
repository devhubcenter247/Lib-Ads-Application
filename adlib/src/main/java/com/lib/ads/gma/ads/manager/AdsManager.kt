package com.lib.ads.gma.ads.manager

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import com.facebook.shimmer.ShimmerFrameLayout
import com.lib.ads.gma.ads.admob.AppOpenManager
import com.lib.ads.gma.ads.admob.AdsConsentManager
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.event.LogEventManager
import com.lib.ads.gma.ads.helper.BannerCollapseGravity
import com.lib.ads.gma.ads.helper.isOnline
import com.lib.ads.gma.ads.helper.adnative.NativeAdParam
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.provider.NativeAdProviderHelper
import com.lib.ads.gma.ads.helper.banner.BannerAdConfig
import com.lib.ads.gma.ads.helper.banner.BannerAdHelper
import com.lib.ads.gma.ads.helper.banner.BannerAdParam
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdConfig
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdHelper
import com.unity3d.ads.metadata.MetaData
import com.google.ads.mediation.admob.AdMobAdapter
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.adlib.R
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safe, small facade for the most common ad flows.
 *
 * Use these functions once per placement (normally from `Activity.onCreate`). The returned helper
 * is optional and is only needed when the caller wants advanced control or event listeners.
 */
object AdsManager {

    private const val TAG = "AdCrosscuts"
    private const val BANNER_ADS = 2
    private const val INTERS_ADS = 3
    private const val REWARD_ADS = 4
    private const val NATIVE_ADS = 5

    @JvmStatic
    @Volatile
    var disableAdResumeWhenClickAds = false

    @JvmStatic
    @Volatile
    var openActivityAfterShowInterAds = false

    @JvmStatic
    @Volatile
    var canRequestAds = true

    fun canRequestAds(context: Context): Boolean = canRequestAds &&
        !isPurchased(context) && AdsConsentManager.getConsentResult(context) && isOnline(context)

    fun handleAdClick(context: Context, adUnitId: String) {
        if (disableAdResumeWhenClickAds) AppOpenManager.getInstance().disableAdResumeByClickAction()
        LogEventManager.logClickAdsEvent(context, adUnitId)
    }

    fun handleAdImpression() = LogEventManager.onTrackImpression()

    fun logPaidEvent(context: Context, adValue: com.google.android.gms.ads.AdValue, adUnitId: String, responseInfo: com.google.android.gms.ads.ResponseInfo?, adType: AdType) {
        responseInfo?.let { LogEventManager.logPaidAdImpression(context, adValue, adUnitId, it, adType) }
    }

    @Volatile
    private var fullScreenAdShowing = false

    fun setFullScreenAdShowing(shown: Boolean) {
        fullScreenAdShowing = shown
        AppOpenManager.getInstance().isInterstitialShowing = shown
    }

    fun isFullScreenAdShowing(): Boolean = fullScreenAdShowing

    /** Enable the debug overlay and test-ad diagnostics. */
    @JvmStatic
    var showMessageForTester = false

    /** Query the existing purchase gate through the unified API. */
    @JvmStatic
    fun isPurchased(context: Context): Boolean = AppPurchase.getInstance().isPurchased(context)

    /** Query the process-wide purchase gate through the unified API. */
    @JvmStatic
    fun isPurchased(): Boolean = AppPurchase.getInstance().isPurchased()

    fun getAdRequest(): com.google.android.gms.ads.AdRequest = com.google.android.gms.ads.AdRequest.Builder().build()

    fun getAdRequestForCollapsibleBanner(gravity: String): com.google.android.gms.ads.AdRequest = com.google.android.gms.ads.AdRequest.Builder()
        .addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply { putString("collapsible", gravity) })
        .build()

    fun checkTestId(context: Context, adTypeCode: Int, id: String) {
        if (context.resources.getStringArray(R.array.list_id_test).contains(id)) showTestIdAlert(context, adTypeCode, id)
    }

    fun showLoadingDialog(activity: Activity): PrepareLoadingAdsDialog? = try {
        PrepareLoadingAdsDialog(activity).apply { setCancelable(false); show() }
    } catch (e: Exception) { Log.e(TAG, "Error showing loading dialog: ${e.message}"); null }

    fun dismissDialog(dialog: PrepareLoadingAdsDialog?, activity: Activity) {
        try { if (dialog?.isShowing == true && !activity.isDestroyed) dialog.dismiss() }
        catch (e: Exception) { Log.e(TAG, "Error dismissing dialog: ${e.message}") }
    }

    private fun showTestIdAlert(context: Context, typeAds: Int, id: String) {
        val prefix = when (typeAds) {
            BANNER_ADS -> "Banner Ads: "; INTERS_ADS -> "Interstitial Ads: "; REWARD_ADS -> "Rewarded Ads: "; NATIVE_ADS -> "Native Ads: "; else -> ""
        }
        val notification = NotificationCompat.Builder(context, "warning_ads")
            .setContentTitle("Found test ad id").setContentText(prefix + id).setSmallIcon(R.drawable.ic_warning).build()
        val manager = NotificationManagerCompat.from(context)
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel("warning_ads", "Warning Ads", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) manager.notify(typeAds, notification)
        Log.e(TAG, "Found test ad id on debug: ${AppUtil.VARIANT_DEV}")
        check(AppUtil.VARIANT_DEV) { "Found test ad id on environment production. Id found: $id" }
    }

    /**
     * Pass the user's privacy choices to Unity Ads before requesting mediated ads.
     *
     * Values are nullable so the SDK does not invent a choice when the app has not collected one.
     * The app remains responsible for mapping its CMP/UMP result to these Unity consent values.
     */
    @JvmStatic
    @JvmOverloads
    fun setUnityPrivacy(
        context: Context,
        gdprConsent: Boolean? = null,
        privacyConsent: Boolean? = null,
    ) {
        gdprConsent?.let { consent ->
            MetaData(context.applicationContext).apply {
                set("gdpr.consent", consent)
                commit()
            }
        }
        privacyConsent?.let { consent ->
            MetaData(context.applicationContext).apply {
                set("privacy.consent", consent)
                commit()
            }
        }
    }

    /** Create, attach and request one banner. No separate `requestAds()` call is needed. */
    @JvmStatic
    @JvmOverloads
    fun showBanner(
        activity: AppCompatActivity,
        container: FrameLayout,
        adUnitId: String,
        enabled: Boolean = true,
        useInline: Boolean = false,
        maxHeightDp: Int = 50,
        collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
        callback: AdsCallback? = null,
    ): BannerAdHelper = showBanner(
        activity = activity,
        container = container,
        adUnitIds = listOf(adUnitId),
        enabled = enabled,
        useInline = useInline,
        maxHeightDp = maxHeightDp,
        collapsibleGravity = collapsibleGravity,
        callback = callback,
    )

    /** Waterfall overload of [showBanner]. Ad unit IDs are tried in order. */
    @JvmStatic
    @JvmOverloads
    fun showBanner(
        activity: AppCompatActivity,
        container: FrameLayout,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        useInline: Boolean = false,
        maxHeightDp: Int = 50,
        collapsibleGravity: BannerCollapseGravity = BannerCollapseGravity.None,
        callback: AdsCallback? = null,
    ): BannerAdHelper {
        val ids = requireAdUnitIds(adUnitIds)
        val config = if (ids.size == 1) {
            BannerAdConfig.simple(ids.first(), useInline, maxHeightDp, collapsibleGravity)
        } else {
            BannerAdConfig.waterfall(ids, useInline, maxHeightDp, collapsibleGravity)
        }

        return BannerAdHelper(activity, activity, config)
            .setBannerContentView(container)
            .setEnableListBanner(ids.size > 1)
            .also { helper ->
                callback?.let(helper::registerAdListener)
                // enabled gates this specific placement, independent of the global
                // AdsManager.canRequestAds switch — false skips the request but still returns
                // a usable helper the caller can drive manually later (helper.requestAds(...)).
                if (enabled) helper.requestAds(BannerAdParam.Request)
            }
    }

    /**
     * Create, attach and request one XML native ad.
     *
     * [preloadBufferSize] defaults to zero so a placement does not leave an unused matched ad in
     * memory. Set it to one only for a placement that is shown repeatedly.
     */
    @JvmStatic
    @JvmOverloads
    fun showNative(
        activity: AppCompatActivity,
        tag: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        adUnitId: String,
        @LayoutRes layoutId: Int,
        enabled: Boolean = true,
        preloadBufferSize: Int = 0,
        callback: AdsCallback? = null,
    ): NativeAdProviderHelper = showNative(
        activity = activity,
        tag = tag,
        container = container,
        shimmer = shimmer,
        adUnitIds = listOf(adUnitId),
        layoutId = layoutId,
        enabled = enabled,
        preloadBufferSize = preloadBufferSize,
        callback = callback,
    )

    /** Waterfall overload of [showNative]. Ad unit IDs are tried in order. */
    @JvmStatic
    @JvmOverloads
    fun showNative(
        activity: AppCompatActivity,
        tag: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        adUnitIds: List<String>,
        @LayoutRes layoutId: Int,
        enabled: Boolean = true,
        preloadBufferSize: Int = 0,
        callback: AdsCallback? = null,
    ): NativeAdProviderHelper {
        require(tag.isNotBlank()) { "Native ad tag must not be blank" }
        require(layoutId != 0) { "Native ad layoutId must be a valid layout resource" }
        val ids = requireAdUnitIds(adUnitIds)
        val bufferSize = preloadBufferSize.coerceIn(0, 10)
        val spec = if (ids.size == 1) {
            NativeAdSpec.simple(tag, ids.first(), layoutId, bufferSize)
        } else {
            NativeAdSpec.waterfall(tag, ids, layoutId, bufferSize)
        }

        return NativeAdProviderHelper(activity, activity, spec)
            .setShimmerLayoutView(shimmer)
            .setNativeContentView(container)
            .also { helper ->
                callback?.let(helper::registerAdListener)
                // enabled gates this specific placement, independent of the global
                // AdsManager.canRequestAds switch — false skips the request but still returns
                // a usable helper the caller can drive manually later (helper.requestAds(...)).
                if (enabled) helper.requestAds(NativeAdParam.Request.CreateRequest)
            }
    }

    /**
     * Load and show a one-shot interstitial, then call [onComplete] exactly once after dismiss,
     * show failure, timeout, or no-fill. Navigation therefore never happens before `show()`.
     */
    @JvmStatic
    @JvmOverloads
    fun showInterstitial(
        activity: ComponentActivity,
        adUnitId: String,
        enabled: Boolean = true,
        timeoutMs: Long = 10_000L,
        onComplete: () -> Unit,
    ): InterstitialAdHelper = showInterstitial(
        activity = activity,
        adUnitIds = listOf(adUnitId),
        enabled = enabled,
        timeoutMs = timeoutMs,
        onComplete = onComplete,
    )

    /** Waterfall overload of [showInterstitial]. Ad unit IDs are tried in order. */
    @JvmStatic
    @JvmOverloads
    fun showInterstitial(
        activity: ComponentActivity,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        timeoutMs: Long = 10_000L,
        onComplete: () -> Unit,
    ): InterstitialAdHelper {
        val ids = requireAdUnitIds(adUnitIds)
        val config = if (ids.size == 1) {
            InterstitialAdConfig.simple(
                adUnitId = ids.first(),
                autoReloadAfterShow = false,
                loadTimeoutMs = timeoutMs,
                canReloadAds = false,
            )
        } else {
            InterstitialAdConfig.waterfall(
                adUnitIds = ids,
                autoReloadAfterShow = false,
                loadTimeoutMs = timeoutMs,
                canReloadAds = false,
            )
        }
        val helper = InterstitialAdHelper(activity, activity, config)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch — false skips the load/show entirely and completes
        // the flow immediately, same contract as "no ad available".
        if (!enabled) {
            onComplete()
            return helper
        }
        val completed = AtomicBoolean(false)
        helper.waitLoadAndShow(
            activity = activity,
            timeoutMs = timeoutMs.coerceAtLeast(1L),
            callback = object : AdsCallback() {
                override fun onNextAction() {
                    if (completed.compareAndSet(false, true)) onComplete()
                }
            },
        )
        return helper
    }

    /** Exclude only the supplied screens from app-open ads; does not disable resume ads globally. */
    @JvmStatic
    fun excludeAppOpen(vararg activityClasses: Class<out Activity>) {
        activityClasses.forEach(AppOpenManager.getInstance()::disableAppResumeWithActivity)
    }

    /** Remove screens previously added through [excludeAppOpen]. */
    @JvmStatic
    fun includeAppOpen(vararg activityClasses: Class<out Activity>) {
        activityClasses.forEach(AppOpenManager.getInstance()::enableAppResumeWithActivity)
    }

    private fun requireAdUnitIds(adUnitIds: List<String>): List<String> =
        adUnitIds.map(String::trim)
            .filter(String::isNotEmpty)
            .also { require(it.isNotEmpty()) { "At least one non-blank ad unit ID is required" } }
}
