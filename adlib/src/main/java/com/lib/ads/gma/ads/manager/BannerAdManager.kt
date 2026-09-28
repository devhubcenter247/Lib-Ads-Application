package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApAdError
import com.lib.ads.gma.ads.config.AdCode
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import com.lib.ads.gma.ads.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

object BannerAdManager {
    private const val TAG = "BannerAdManager"
    private const val BANNER_ADS = 2
    private const val DEFAULT_WATERFALL_TIMEOUT_MS = 10_000L

    private val preloadedBanners = ConcurrentHashMap<String, AdManagerAdView>()
    private val preloadingKeys = ConcurrentHashMap.newKeySet<String>()

    /** Preload one banner into a tag-keyed store for a later [takePreloadedBanner] call. */
    fun preloadBanner(
        context: Context,
        tag: String,
        id: String,
        collapsibleGravity: String? = null,
        useInlineAdaptive: Boolean = false,
        maxHeight: Int = 0,
        containerWidthPx: Int = 0,
    ) {
        if (tag.isBlank() || preloadedBanners.containsKey(tag) || !preloadingKeys.add(tag)) return

        requestLoadBannerView(
            context = context,
            id = id,
            collapsibleGravity = collapsibleGravity,
            useInlineAdaptive = useInlineAdaptive,
            maxHeight = maxHeight,
            containerWidthPx = containerWidthPx,
            callback = object : AdsCallback() {
                override fun onBannerLoaded(adView: AdManagerAdView?) {
                    preloadingKeys.remove(tag)
                    if (adView == null) return
                    preloadedBanners.put(tag, adView)?.destroyForWaterfall()
                }

                override fun onAdFailedToLoad(adError: ApAdError?) {
                    preloadingKeys.remove(tag)
                    AppLogger.e(TAG, "preloadBanner failed tag=$tag: ${adError?.message}")
                }
            },
        )
    }

    /** Preload a banner waterfall into the same tag-keyed store. */
    fun preloadBanner(
        context: Context,
        tag: String,
        ids: List<String>,
        collapsibleGravity: String? = null,
        useInlineAdaptive: Boolean = false,
        maxHeight: Int = 0,
        containerWidthPx: Int = 0,
    ) {
        if (tag.isBlank() || ids.isEmpty() || preloadedBanners.containsKey(tag) || !preloadingKeys.add(tag)) return

        loadBannerList(
            context = context,
            listId = ids,
            collapsibleGravity = collapsibleGravity,
            useInlineAdaptive = useInlineAdaptive,
            maxHeight = maxHeight,
            containerWidthPx = containerWidthPx,
            adCallback = object : AdsCallback() {
                override fun onBannerLoaded(adView: AdManagerAdView?) {
                    preloadingKeys.remove(tag)
                    if (adView == null) return
                    preloadedBanners.put(tag, adView)?.destroyForWaterfall()
                }

                override fun onAdFailedToLoad(adError: ApAdError?) {
                    preloadingKeys.remove(tag)
                    AppLogger.e(TAG, "preloadBanner waterfall failed tag=$tag: ${adError?.message}")
                }
            },
        )
    }

    /** Return and remove a loaded banner from the preload store, or null when unavailable. */
    fun takePreloadedBanner(tag: String): AdManagerAdView? = preloadedBanners.remove(tag)

    /**
     * Rebind [adView]'s [AdListener] to [callback]. Required before handing a preloaded banner
     * (from [takePreloadedBanner]) to a real consumer: its current listener only forwards the
     * load result to the preload callback, so click/impression/paid-event tracking and
     * [AdsCallback.onAdClicked]/[AdsCallback.onAdImpression]/[AdsCallback.onAdOpened]/
     * [AdsCallback.onAdClosed] would otherwise silently go nowhere.
     */
    fun rebindListener(adView: AdManagerAdView, context: Context, callback: AdsCallback) {
        val appCtx = context.applicationContext
        adView.adListener = object : AdListener() {
            override fun onAdOpened() {
                super.onAdOpened()
                callback.onAdOpened()
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsManager.handleAdClick(appCtx, adView.adUnitId)
                callback.onAdClicked()
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsManager.handleAdImpression()
                AdLoadStats.recordImpression(AdType.BANNER, adUnitId = adView.adUnitId)
                callback.onAdImpression()
            }

            override fun onAdClosed() {
                super.onAdClosed()
                callback.onAdClosed()
            }
        }
    }

    fun hasPreloadedBanner(tag: String): Boolean = preloadedBanners.containsKey(tag)

    fun clearPreloadedBanner(tag: String) {
        preloadedBanners.remove(tag)?.destroyForWaterfall()
        preloadingKeys.remove(tag)
    }

    fun requestLoadBanner(
        context: Context,
        id: String,
        collapsibleGravity: String?,
        enabled: Boolean = true,
        useInlineAdaptive: Boolean = false,
        maxHeight: Int = 0,
        containerWidthPx: Int = 0,
        callback: AdsCallback,
    ) {
        requestLoadBannerView(context, id, collapsibleGravity, enabled, useInlineAdaptive, maxHeight, containerWidthPx, callback)
    }

    private fun requestLoadBannerView(
        context: Context,
        id: String,
        collapsibleGravity: String?,
        enabled: Boolean = true,
        useInlineAdaptive: Boolean = false,
        maxHeight: Int = 0,
        containerWidthPx: Int = 0,
        callback: AdsCallback,
    ): AdManagerAdView? {
        AdsManager.checkTestId(context, BANNER_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(context)) {
            callback.onAdFailedToLoad(ApAdError(LoadAdError(AdCode.PURCHASED, "App isPurchased", "", null, null)))
            return null
        }
        val appCtx = context.applicationContext
        AdLoadStats.recordRequested(AdType.BANNER, adUnitId = id)

        try {
            val adView = AdManagerAdView(context)
            adView.adUnitId = id
            val adSize = getAdSize(context, useInlineAdaptive, maxHeight, containerWidthPx)
            adView.setAdSize(adSize)
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    AdLoadStats.recordFailed(AdType.BANNER, adUnitId = id, reason = loadAdError.code.toString())
                    callback.onAdFailedToLoad(ApAdError(loadAdError))
                }

                override fun onAdLoaded() {
                    AdLoadStats.recordLoaded(AdType.BANNER, adUnitId = id)
                    adView.responseInfo?.let {
                        Log.d(TAG, "Banner adapter class name: ${it.mediationAdapterClassName}")
                    }
                    callback.onBannerLoaded(adView)
                    adView.setOnPaidEventListener { adValue ->
                        Log.d(TAG, "OnPaidEvent banner: ${adValue.valueMicros}")
                        AdsManager.logPaidEvent(appCtx, adValue, adView.adUnitId, adView.responseInfo, AdType.BANNER)
                    }
                }

                override fun onAdOpened() {
                    super.onAdOpened()
                    callback.onAdOpened()
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsManager.handleAdClick(appCtx, id)
                    callback.onAdClicked()
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsManager.handleAdImpression()
                    AdLoadStats.recordImpression(AdType.BANNER, adUnitId = id)
                    callback.onAdImpression()
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                    callback.onAdClosed()
                }
            }

            if (!collapsibleGravity.isNullOrEmpty()) {
                adView.loadAd(AdsManager.getAdRequestForCollapsibleBanner(collapsibleGravity))
            } else {
                adView.loadAd(AdsManager.getAdRequest())
            }
            return adView
        } catch (e: Exception) {
            e.printStackTrace()
            callback.onAdFailedToLoad(ApAdError(e.message ?: "banner load exception"))
            return null
        }
    }

    fun loadBannerList(
        context: Context,
        listId: List<String>?,
        collapsibleGravity: String?,
        enabled: Boolean = true,
        useInlineAdaptive: Boolean = false,
        maxHeight: Int = 0,
        containerWidthPx: Int = 0,
        adCallback: AdsCallback,
        timeoutPerIdMs: Long = DEFAULT_WATERFALL_TIMEOUT_MS,
    ) {
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled) {
            adCallback.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        if (listId.isNullOrEmpty()) {
            adCallback.onAdFailedToLoad(ApAdError("list id is null or empty"))
            return
        }
        val activity = context as? Activity
        if (activity == null) {
            adCallback.onAdFailedToLoad(ApAdError("Context is not an Activity"))
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val resolvedTimeout = timeoutPerIdMs.takeIf { it > 0 } ?: DEFAULT_WATERFALL_TIMEOUT_MS

        fun loadAt(index: Int) {
            if (activity.isFinishing || activity.isDestroyed) {
                adCallback.onAdFailedToLoad(ApAdError("Activity is finishing or destroyed"))
                return
            }
            if (index >= listId.size) {
                adCallback.onAdFailedToLoad(ApAdError("all banner ids failed/timed out"))
                return
            }

            val adUnitId = listId[index]
            var completed = false
            var accepted = false
            var adView: AdManagerAdView? = null
            lateinit var timeoutRunnable: Runnable

            fun failAndNext(adError: ApAdError?) {
                if (index < listId.size - 1) {
                    AppLogger.d(TAG, "loadBannerList next index=${index + 1} id=${listId[index + 1]}")
                    loadAt(index + 1)
                } else {
                    adCallback.onAdFailedToLoad(adError)
                }
            }

            timeoutRunnable = Runnable {
                if (completed) return@Runnable
                completed = true
                AppLogger.e(TAG, "loadBannerList timeout id=$adUnitId after ${resolvedTimeout}ms")
                adView?.destroyForWaterfall()
                failAndNext(ApAdError("banner timeout: $adUnitId"))
            }

            val callback = object : AdsCallback() {
                override fun onAdFailedToLoad(adError: ApAdError?) {
                    if (completed) return
                    completed = true
                    handler.removeCallbacks(timeoutRunnable)
                    adView?.destroyForWaterfall()
                    AppLogger.e(TAG, "onAdFailedToLoad id=$adUnitId msg=${adError?.message ?: "null"}")
                    failAndNext(adError)
                }

                override fun onBannerLoaded(adView: AdManagerAdView?) {
                    if (completed) {
                        adView?.destroyForWaterfall()
                        return
                    }
                    completed = true
                    accepted = true
                    handler.removeCallbacks(timeoutRunnable)
                    adCallback.onBannerLoaded(adView)
                }

                override fun onAdOpened() {
                    if (!accepted) return
                    adCallback.onAdOpened()
                }

                override fun onAdClicked() {
                    if (!accepted) return
                    adCallback.onAdClicked()
                }

                override fun onAdImpression() {
                    if (!accepted) return
                    adCallback.onAdImpression()
                }

                override fun onAdClosed() {
                    if (!accepted) return
                    adCallback.onAdClosed()
                }
            }

            AppLogger.d(TAG, "loadBannerList index=$index id=$adUnitId timeout=${resolvedTimeout}ms")
            handler.postDelayed(timeoutRunnable, resolvedTimeout)
            adView = requestLoadBannerView(
                context = context,
                id = adUnitId,
                collapsibleGravity = collapsibleGravity,
                useInlineAdaptive = useInlineAdaptive,
                maxHeight = maxHeight,
                containerWidthPx = containerWidthPx,
                callback = callback
            )
        }

        loadAt(0)
    }

    private fun AdManagerAdView.destroyForWaterfall() {
        adListener = object : AdListener() {}
        destroy()
    }

    fun loadBanner(
        activity: Activity,
        id: String,
        enabled: Boolean = true,
        adContainer: FrameLayout,
        containerShimmer: ShimmerFrameLayout,
        callback: AdsCallback?,
        useInlineAdaptive: Boolean,
    ) {
        AdsManager.checkTestId(activity, BANNER_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(activity)) {
            containerShimmer.visibility = View.GONE
            callback?.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val appCtx = activity.applicationContext
        containerShimmer.visibility = View.VISIBLE
        containerShimmer.startShimmer()

        try {
            val adView = AdManagerAdView(activity)
            adView.adUnitId = id
            adContainer.addView(adView)
            val adSize = getAdSize(activity, useInlineAdaptive)
            val adHeight = adSize.height
            containerShimmer.layoutParams.height =
                (adHeight * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
            adView.setAdSize(adSize)
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            AdLoadStats.recordRequested(AdType.BANNER, adUnitId = id)
            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    AdLoadStats.recordFailed(AdType.BANNER, adUnitId = id, reason = loadAdError.code.toString())
                    containerShimmer.stopShimmer()
                    adContainer.visibility = View.GONE
                    containerShimmer.visibility = View.GONE
                    callback?.onAdFailedToLoad(ApAdError(loadAdError))
                }

                override fun onAdLoaded() {
                    AdLoadStats.recordLoaded(AdType.BANNER, adUnitId = id)
                    adView.responseInfo?.let {
                        Log.d(TAG, "Banner adapter class name: ${it.mediationAdapterClassName}")
                    }
                    containerShimmer.stopShimmer()
                    containerShimmer.visibility = View.GONE
                    adContainer.visibility = View.VISIBLE
                    adView.setOnPaidEventListener { adValue ->
                        Log.d(TAG, "OnPaidEvent banner: ${adValue.valueMicros}")
                        AdsManager.logPaidEvent(appCtx, adValue, adView.adUnitId, adView.responseInfo, AdType.BANNER)
                    }
                    callback?.onAdLoaded()
                }

                override fun onAdOpened() {
                    super.onAdOpened()
                    callback?.onAdOpened()
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsManager.handleAdClick(appCtx, id)
                    callback?.onAdClicked()
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsManager.handleAdImpression()
                    AdLoadStats.recordImpression(AdType.BANNER, adUnitId = id)
                    callback?.onAdImpression()
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                    callback?.onAdClosed()
                }
            }

            adView.loadAd(AdsManager.getAdRequest())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadCollapsibleBanner(
        activity: Activity,
        id: String,
        enabled: Boolean = true,
        gravity: String,
        adContainer: FrameLayout,
        containerShimmer: ShimmerFrameLayout,
        callback: AdsCallback?,
    ) {
        AdsManager.checkTestId(activity, BANNER_ADS, id)
        // enabled gates this specific placement, independent of the global
        // AdsManager.canRequestAds switch.
        if (!enabled || !AdsManager.canRequestAds(activity)) {
            containerShimmer.visibility = View.GONE
            callback?.onAdFailedToLoad(ApAdError("ad requests disabled for this placement"))
            return
        }
        val appCtx = activity.applicationContext
        containerShimmer.visibility = View.VISIBLE
        containerShimmer.startShimmer()

        try {
            val adView = AdManagerAdView(activity)
            adView.adUnitId = id
            adContainer.addView(adView)
            val adSize = getAdSize(activity, false)
            containerShimmer.layoutParams.height =
                (adSize.height * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
            adView.setAdSize(adSize)
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            adView.loadAd(AdsManager.getAdRequestForCollapsibleBanner(gravity))
            AdLoadStats.recordRequested(AdType.BANNER, adUnitId = id)

            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    AdLoadStats.recordFailed(AdType.BANNER, adUnitId = id, reason = loadAdError.code.toString())
                    containerShimmer.stopShimmer()
                    adContainer.visibility = View.GONE
                    containerShimmer.visibility = View.GONE
                    callback?.onAdFailedToLoad(ApAdError(loadAdError))
                }

                override fun onAdLoaded() {
                    AdLoadStats.recordLoaded(AdType.BANNER, adUnitId = id)
                    adView.responseInfo?.let {
                        Log.d(TAG, "Banner adapter class name: ${it.mediationAdapterClassName}")
                    }
                    containerShimmer.stopShimmer()
                    containerShimmer.visibility = View.GONE
                    adContainer.visibility = View.VISIBLE
                    adView.setOnPaidEventListener { adValue ->
                        Log.d(TAG, "OnPaidEvent banner: ${adValue.valueMicros}")
                        AdsManager.logPaidEvent(appCtx, adValue, adView.adUnitId, adView.responseInfo, AdType.BANNER)
                    }
                    callback?.onAdLoaded()
                }

                override fun onAdOpened() {
                    super.onAdOpened()
                    callback?.onAdOpened()
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsManager.handleAdClick(appCtx, id)
                    callback?.onAdClicked()
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsManager.handleAdImpression()
                    callback?.onAdImpression()
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                    callback?.onAdClosed()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun populateUnifiedBannerAdView(activity: Activity, adView: AdManagerAdView, adContainer: FrameLayout) {
        AdsManager.checkTestId(activity, BANNER_ADS, adView.adUnitId)
        try {
            adContainer.addView(adView)
            adContainer.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getAdWidthDp(context: Context, containerWidthPx: Int): Int {
        val density = context.resources.displayMetrics.density
        if (containerWidthPx > 0) {
            return (containerWidthPx / density).toInt()
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return (context.resources.displayMetrics.widthPixels / density).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            ((metrics.bounds.width() - insets.left - insets.right) / density).toInt()
        } else {
            @Suppress("DEPRECATION")
            val outMetrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }
            (outMetrics.widthPixels / outMetrics.density).toInt()
        }
    }

    @Suppress("DEPRECATION")
    private fun getAdSize(context: Context, useInlineAdaptive: Boolean, maxHeight: Int = 0, containerWidthPx: Int = 0): AdSize {
        val adWidth = getAdWidthDp(context, containerWidthPx)
        return when {
            useInlineAdaptive && maxHeight > 0 -> AdSize.getInlineAdaptiveBannerAdSize(adWidth, maxHeight)
            useInlineAdaptive -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, adWidth)
            else -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
        }
    }
}
