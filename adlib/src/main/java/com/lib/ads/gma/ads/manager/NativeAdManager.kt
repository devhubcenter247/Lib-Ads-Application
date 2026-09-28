package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MediaAspectRatio
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.lib.adlib.R
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.helper.adnative.core.NativeAdLoader
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cold (non-buffered) native loading + view population.
 *
 * Loading is delegated to the unified [NativeAdLoader]; this object adds the
 * fire-and-forget callback API used by the helpers and owns the AdMob view binding
 * ([populateNativeAdView] / [populateUnifiedNativeAdView]).
 */
object NativeAdManager {
    private const val TAG = "NativeAdManager"
    private const val NATIVE_ADS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Load a single native ad, delegating to [NativeAdLoader]. */
    fun loadNativeAd(context: Context, id: String, enabled: Boolean = true, layoutCustomNative: Int, callback: AdsCallback) {
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch (checked inside NativeAdLoader.loadOne).
        if (!enabled) {
            callback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        scope.launch {
            val result = NativeAdLoader.loadOne(
                context = context,
                adUnitId = id,
                layoutCustomNative = layoutCustomNative,
                callbacks = listOf(callback)
            )
            when (result) {
                is NativeAdLoader.LoadResult.Success -> {
                    NativeAdLoader.notifyLoaded(result, layoutCustomNative, listOf(callback))
                }
                is NativeAdLoader.LoadResult.Failure -> {
                    callback.onAdFailedToLoad(result.error?.let { ApAdError(it) } ?: ApAdError("native load failed"))
                }
            }
        }
    }

    /** Waterfall load (no per-id timeout). First successful id wins. */
    fun loadNativeList(
        context: Context,
        listId: List<String>?,
        enabled: Boolean = true,
        layoutCustomNative: Int,
        adCallback: AdsCallback,
    ) {
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch (checked inside NativeAdLoader.loadOne).
        if (!enabled) {
            adCallback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        if (listId.isNullOrEmpty()) {
            adCallback.onAdFailedToLoad(ApAdError("list id is null or empty"))
            return
        }
        scope.launch {
            val result = NativeAdLoader.waterfall(
                context = context.applicationContext,
                adUnitIds = listId,
                layoutCustomNative = layoutCustomNative,
                callbacks = listOf(adCallback)
            )
            if (result == null) adCallback.onAdFailedToLoad(ApAdError("all native ids failed"))
        }
    }

    /** Waterfall load with a per-id timeout (index-aligned with [listId]). */
    fun loadNativeListTimeOut(
        context: Context,
        listId: List<String>?,
        enabled: Boolean = true,
        timeOutPerId: List<Long>?,
        layoutCustomNative: Int,
        adCallback: AdsCallback,
    ) {
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch (checked inside NativeAdLoader.loadOne).
        if (!enabled) {
            adCallback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        if (listId.isNullOrEmpty()) {
            adCallback.onAdFailedToLoad(ApAdError("list id is null or empty"))
            return
        }
        scope.launch {
            val result = NativeAdLoader.waterfall(
                context = context.applicationContext,
                adUnitIds = listId,
                timeoutsMs = timeOutPerId ?: emptyList(),
                layoutCustomNative = layoutCustomNative,
                callbacks = listOf(adCallback)
            )
            if (result == null) adCallback.onAdFailedToLoad(ApAdError("all native ids failed/timed out"))
        }
    }

    /**
     * Load multiple native ads in one request (AdMob loadAds).
     * Kept as a dedicated path because [NativeAdLoader] models a single ad at a time.
     */
    fun loadNativeAd(
        context: Context,
        id: String,
        layoutCustomNative: Int,
        callback: AdsCallback,
        maxNumberOfAds: Int,
        enabled: Boolean = true,
    ) {
        AdsManager.checkTestId(context, NATIVE_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(context)) {
            callback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val appCtx = context.applicationContext
        val videoOptions = VideoOptions.Builder().setStartMuted(true).build()
        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()
        AdLoadStats.recordRequested(AdType.NATIVE, adUnitId = id)

        val adLoader = AdLoader.Builder(context, id)
            .forNativeAd { nativeAd ->
                AdLoadStats.recordLoaded(AdType.NATIVE, adUnitId = id)
                callback.onNativeAdLoaded(ApNativeAd(layoutCustomNative, nativeAd))
                nativeAd.setOnPaidEventListener { adValue ->
                    AdsManager.logPaidEvent(appCtx, adValue, id, nativeAd.responseInfo, AdType.NATIVE)
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdLoadStats.recordFailed(AdType.NATIVE, adUnitId = id, reason = error.code.toString())
                    AppLogger.e(TAG, "loadNativeAds onAdFailedToLoad: ${error.message}")
                    callback.onAdFailedToLoad(ApAdError(error))
                }

                override fun onAdImpression() {
                    AdsManager.handleAdImpression()
                    AdLoadStats.recordImpression(AdType.NATIVE, adUnitId = id)
                    callback.onAdImpression()
                }

                override fun onAdClicked() {
                    AdsManager.handleAdClick(appCtx, id)
                    callback.onAdClicked()
                }
            })
            .withNativeAdOptions(adOptions)
            .build()

        adLoader.loadAds(AdsManager.getAdRequest(), maxNumberOfAds)
    }

    /** Full-screen native (portrait media, audio on). */
    fun loadNativeFullScreen(
        context: Context,
        id: String,
        enabled: Boolean = true,
        layoutCustomNative: Int,
        callback: AdsCallback,
    ) {
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch (checked inside NativeAdLoader.loadOne).
        if (!enabled) {
            callback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val options = NativeAdOptions.Builder()
            .setMediaAspectRatio(MediaAspectRatio.PORTRAIT)
            .setVideoOptions(VideoOptions.Builder().setStartMuted(false).build())
            .build()
        scope.launch {
            val result = NativeAdLoader.loadOne(
                context = context,
                adUnitId = id,
                options = options,
                layoutCustomNative = layoutCustomNative,
                callbacks = listOf(callback)
            )
            when (result) {
                is NativeAdLoader.LoadResult.Success -> {
                    NativeAdLoader.notifyLoaded(result, layoutCustomNative, listOf(callback))
                }
                is NativeAdLoader.LoadResult.Failure -> {
                    callback.onAdFailedToLoad(result.error?.let { ApAdError(it) } ?: ApAdError("native load failed"))
                }
            }
        }
    }

    fun populateNativeAdView(
        activity: Activity,
        apNativeAd: ApNativeAd,
        adPlaceHolder: FrameLayout?,
        containerShimmerLoading: ShimmerFrameLayout?
    ) {
        if (apNativeAd.admobNativeAd == null && apNativeAd.nativeView == null) {
            containerShimmerLoading?.visibility = View.GONE
            Log.e(TAG, "populateNativeAdView failed: native is not loaded")
            return
        }
        val adView = LayoutInflater.from(activity).inflate(apNativeAd.layoutCustomNative, null) as NativeAdView
        containerShimmerLoading?.stopShimmer()
        containerShimmerLoading?.visibility = View.GONE
        adPlaceHolder?.visibility = View.VISIBLE
        apNativeAd.admobNativeAd?.let { populateUnifiedNativeAdView(it, adView) }
        adPlaceHolder?.removeAllViews()
        adPlaceHolder?.addView(adView)
    }

    fun populateUnifiedNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        adView.mediaView = adView.findViewById(R.id.ad_media)
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)
        adView.priceView = adView.findViewById(R.id.ad_price)
        adView.starRatingView = adView.findViewById(R.id.ad_stars)
        adView.advertiserView = adView.findViewById(R.id.ad_advertiser)

        try { adView.headlineView?.let { (it as TextView).text = nativeAd.headline } } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.body == null) { adView.bodyView?.visibility = View.INVISIBLE }
            else { adView.bodyView?.visibility = View.VISIBLE; (adView.bodyView as? TextView)?.text = nativeAd.body }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.callToAction == null) { adView.callToActionView?.visibility = View.INVISIBLE }
            else { adView.callToActionView?.visibility = View.VISIBLE; (adView.callToActionView as? TextView)?.text = nativeAd.callToAction }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.icon == null) { adView.iconView?.visibility = View.GONE }
            else { adView.iconView?.let { (it as ImageView).setImageDrawable(nativeAd.icon?.drawable); it.visibility = View.VISIBLE } }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.price == null) { adView.priceView?.visibility = View.INVISIBLE }
            else { adView.priceView?.visibility = View.VISIBLE; (adView.priceView as? TextView)?.text = nativeAd.price }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.starRating == null) { adView.starRatingView?.visibility = View.INVISIBLE }
            else { (adView.starRatingView as? RatingBar)?.rating = nativeAd.starRating!!.toFloat(); adView.starRatingView?.visibility = View.VISIBLE }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            if (nativeAd.advertiser == null) { adView.advertiserView?.visibility = View.INVISIBLE }
            else { (adView.advertiserView as? TextView)?.text = nativeAd.advertiser; adView.advertiserView?.visibility = View.VISIBLE }
        } catch (e: Exception) { e.printStackTrace() }

        adView.setNativeAd(nativeAd)
    }
}
