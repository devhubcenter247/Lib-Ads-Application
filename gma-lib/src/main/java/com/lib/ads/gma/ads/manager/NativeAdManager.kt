package com.lib.ads.gma.ads.manager

import com.lib.ads.gma.ads.util.AppLogger
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.adnative.NativeAdRequestOptions
import com.lib.ads.gma.ads.helper.adnative.toNativeAdRequest
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.ads.model.wrapper.shortAdUnit
import com.lib.ads.gma.ads.model.wrapper.withCauses
import com.lib.ads.gma.gma.R
import java.util.concurrent.atomic.AtomicBoolean

/** Owns native ad loading & view binding — matches adlib's NativeAdManager. */
object NativeAdManager {
    private const val TAG = "NativeAdManager"

    fun loadNativeList(context: Context, listId: List<String>?, layoutCustomNative: Int, callback: NativeAdListener, options: NativeAdRequestOptions = NativeAdRequestOptions()) {
        whenAdsReady {
            if (listId.isNullOrEmpty()) callback.onFailed(ApAdError("list id is null or empty"))
            else loadNativeNextAd(context.applicationContext, listId, 0, layoutCustomNative, callback, AtomicBoolean(false), options)
        }
    }

    private fun loadNativeNextAd(context: Context, ids: List<String>, pos: Int, layout: Int, callback: NativeAdListener, done: AtomicBoolean, options: NativeAdRequestOptions) {
        loadNativeAdResultCallback(context, ids[pos], layout, callback = object : NativeAdListener {
            override fun onLoaded(ad: ApNativeAd) { if (done.compareAndSet(false, true)) callback.onLoaded(ad) }
            override fun onFailed(error: ApAdError) { if (pos + 1 < ids.size && !done.get()) loadNativeNextAd(context, ids, pos + 1, layout, callback, done, options) else callback.onFailed(error) }
        }, options = options)
    }

    /**
     * Loads [listId] as a waterfall with a timeout per id. An ad that arrives after the waterfall
     * already has a result is handed to [onLateAd] (e.g. to keep it in a placement buffer) or
     * destroyed when no handler is given — never silently dropped.
     */
    fun loadNativeListTimeOut(
        context: Context,
        listId: List<String>?,
        timeOutPerId: List<Long>?,
        layoutCustomNative: Int,
        callback: NativeAdListener,
        options: NativeAdRequestOptions = NativeAdRequestOptions(),
        onLateAd: ((ApNativeAd) -> Unit)? = null,
    ) {
        whenAdsReady {
            if (listId.isNullOrEmpty()) callback.onFailed(ApAdError("list id is null or empty"))
            else loadNativeNextAdWithTimeout(context.applicationContext, listId, timeOutPerId, 0, layoutCustomNative, callback, AtomicBoolean(false), options, onLateAd, mutableListOf())
        }
    }

    private fun loadNativeNextAdWithTimeout(context: Context, ids: List<String>, timeouts: List<Long>?, pos: Int, layout: Int, callback: NativeAdListener, done: AtomicBoolean, options: NativeAdRequestOptions, onLateAd: ((ApNativeAd) -> Unit)?, failures: MutableList<String>) {
        if (done.get()) return
        val timeout = resolveTimeoutMs(timeouts, pos)
        val started = SystemClock.uptimeMillis()
        val abandoned = AtomicBoolean(false)
        val timeoutTask = Runnable {
            if (abandoned.compareAndSet(false, true) && !done.get()) {
                failures += "${shortAdUnit(ids[pos])}: timeout ${SystemClock.uptimeMillis() - started}ms"
                if (pos + 1 < ids.size) loadNativeNextAdWithTimeout(context, ids, timeouts, pos + 1, layout, callback, done, options, onLateAd, failures)
                else if (done.compareAndSet(false, true)) {
                    callback.onFailed(ApAdError("timeout after ${SystemClock.uptimeMillis() - started}ms").withCauses(failures))
                }
            }
        }
        Ads.MAIN.postDelayed(timeoutTask, timeout)
        loadNativeAdResultCallback(context, ids[pos], layout, callback = object : NativeAdListener {
            override fun onLoaded(ad: ApNativeAd) {
                    Ads.MAIN.removeCallbacks(timeoutTask)
                    // An id that already timed out may still answer before the waterfall has a
                    // winner: show it rather than waste a matched request. Anything after the
                    // winner is a late ad.
                    if (done.compareAndSet(false, true)) callback.onLoaded(ad)
                    else handleLateNativeAd(ad, ids[pos], onLateAd)
            }
            override fun onFailed(error: ApAdError) {
                    if (abandoned.get() || done.get()) return
                    Ads.MAIN.removeCallbacks(timeoutTask)
                    failures += "${shortAdUnit(ids[pos])}: ${error.shortDescription()}"
                    if (pos + 1 < ids.size) loadNativeNextAdWithTimeout(context, ids, timeouts, pos + 1, layout, callback, done, options, onLateAd, failures)
                    else if (done.compareAndSet(false, true)) callback.onFailed(error.withCauses(failures))
            }
        }, options = options)
    }

    private fun handleLateNativeAd(ad: ApNativeAd, id: String, onLateAd: ((ApNativeAd) -> Unit)?) {
        if (onLateAd != null) {
            onLateAd(ad)
        } else {
            AppLogger.d(TAG, "late native ad destroyed id=$id")
            runCatching { ad.nativeAd?.destroy() }
        }
    }

    fun resolveTimeoutMs(timeOutPerId: List<Long>?, pos: Int): Long {
        val timeout = timeOutPerId?.getOrNull(pos) ?: timeOutPerId?.firstOrNull() ?: 5000L
        return timeout.takeIf { it > 0 } ?: 5000L
    }

    fun loadNativeAd(activity: Activity, id: String, layoutCustomNative: Int, adPlaceHolder: FrameLayout, containerShimmerLoading: ShimmerFrameLayout, callback: NativeAdListener) {
        loadNativeAdResultCallback(context = activity, id = id, layoutCustomNative = layoutCustomNative, callback = object : NativeAdListener {
            override fun onLoaded(ad: ApNativeAd) { callback.onLoaded(ad); populateNativeAdView(activity, ad, adPlaceHolder, containerShimmerLoading, callback) }
            override fun onFailed(error: ApAdError) { callback.onFailed(error) }
        })
    }

    fun loadNativeAdResultCallback(context: Context, id: String, layoutCustomNative: Int, callback: NativeAdListener, options: NativeAdRequestOptions = NativeAdRequestOptions()) {
        whenAdsReady { loadNativeAdRaw(context.applicationContext, id, callback, maxNumberOfAds = null, options = options, layoutCustomNative = layoutCustomNative) }
    }

    fun loadNativeAdResultCallback(activity: Activity, id: String, layoutCustomNative: Int, callback: NativeAdListener, maxNumberOfAds: Int, options: NativeAdRequestOptions = NativeAdRequestOptions()) {
        whenAdsReady { loadNativeAdRaw(activity, id, callback, maxNumberOfAds = maxNumberOfAds, options = options, layoutCustomNative = layoutCustomNative) }
    }

    /** Loads a native ad straight from the SDK (test-id alert + purchase gate + request build). */
    fun loadNativeAdRaw(
        context: Context,
        id: String,
        callback: NativeAdListener,
        maxNumberOfAds: Int?,
        options: NativeAdRequestOptions,
        layoutCustomNative: Int = 0
    ) {
        if (AppPurchase.getInstance().isPurchased()) {
            callback.onFailed(ApAdError("App is purchased"))
            return
        }
        if (context.resources.getStringArray(R.array.list_id_test).contains(id)) AdsManager.showTestIdAlert(context, AdsManager.NATIVE_ADS, id)

        val request = options.toNativeAdRequest(id)
        val loaderCallback = object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) {
                callback.onLoaded(ApNativeAd(layoutCustomNative, nativeAd))
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                callback.onFailed(ApAdError(adError))
            }
        }
        if (maxNumberOfAds != null) {
            NativeAdLoader.load(request, maxNumberOfAds, loaderCallback)
        } else {
            NativeAdLoader.load(request, loaderCallback)
        }
    }

    /** Native ad request tuned for full-screen (interstitial-style) placements. Currently unused internally; kept for callers that need it. */
    fun loadNativeFullScreenRaw(context: Context, id: String, callback: NativeAdListener) {
        loadNativeAdRaw(
            context = context,
            id = id,
            callback = callback,
            maxNumberOfAds = null,
            options = NativeAdRequestOptions().apply {
                setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.PORTRAIT)
                setVideoOptions(
                    com.google.android.libraries.ads.mobile.sdk.common.VideoOptions.Builder()
                        .setStartMuted(false)
                        .build()
                )
            }
        )
    }

    fun populateNativeAdView(activity: Activity, ad: ApNativeAd, placeholder: FrameLayout, shimmer: ShimmerFrameLayout?, listener: NativeAdListener? = null) {
        if (ad.nativeAd == null && ad.nativeView == null) {
            shimmer?.visibility = android.view.View.GONE
            Log.e(TAG, "populateNativeAdView failed: native is not loaded")
            return
        }
        val adView = LayoutInflater.from(activity).inflate(ad.layoutCustomNative, null) as NativeAdView
        shimmer?.stopShimmer(); shimmer?.visibility = android.view.View.GONE
        placeholder.removeAllViews(); placeholder.addView(adView); placeholder.visibility = android.view.View.VISIBLE
        ad.nativeAd?.let { bindNativeAdView(it, adView, listener) }
    }

    /** Binds a loaded native object to a view supplied by the helper, and wires click/impression/paid-event analytics. */
    fun bindNativeAdView(nativeAd: NativeAd, adView: NativeAdView, listener: NativeAdListener? = null) {
        adView.advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser)
        adView.bodyView = adView.findViewById<TextView>(R.id.ad_body)
        adView.callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action)
        adView.headlineView = adView.findViewById<TextView>(R.id.ad_headline)
        adView.iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
        adView.priceView = adView.findViewById<TextView>(R.id.ad_price)
        adView.starRatingView = adView.findViewById<RatingBar>(R.id.ad_stars)
        val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
        adView.registerNativeAd(nativeAd, mediaView)
        bindNativeAdEventCallback(adView.context, nativeAd, listener)
    }

    /**
     * Wires click/impression/paid-event analytics + [listener] forwarding onto [nativeAd].
     *
     * This is the part of [bindNativeAdView] that has nothing to do with XML view lookup — split
     * out so Compose renderers (which bind views their own way via `NativeAdView.registerNativeAd`)
     * can still get event tracking, which [bindNativeAdView] alone doesn't give them.
     */
    fun bindNativeAdEventCallback(context: Context, nativeAd: NativeAd, listener: NativeAdListener? = null) {
        val wrappedAd = ApNativeAd(0, nativeAd)
        val adUnitId = nativeAd.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
        nativeAd.adEventCallback = object : NativeAdEventCallback {
            override fun onAdClicked() {
                AdsLogEventManager.logClickAdsEvent(context, adUnitId)
                listener?.onClicked(wrappedAd)
            }
            override fun onAdImpression() {
                AdsLogEventManager.onTrackImpression(context)
                listener?.onImpression(wrappedAd)
            }
            override fun onAdPaid(value: AdValue) {
                AdsLogEventManager.logPaidAdImpression(context, value, nativeAd.getResponseInfo(), AdType.NATIVE)
                listener?.onPaid(value)
            }
            override fun onAdShowedFullScreenContent() {
                listener?.onShownFullScreenContent(wrappedAd)
            }
            override fun onAdDismissedFullScreenContent() {
                listener?.onDismissedFullScreenContent(wrappedAd)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError) {
                listener?.onFailedToShowFullScreenContent(wrappedAd, ApAdError(fullScreenContentError))
            }
            override fun onAdSwipeGestureClicked() {
                listener?.onSwipeGestureClicked(wrappedAd)
            }
        }
    }
}
