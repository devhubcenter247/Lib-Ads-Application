package com.lib.ads.gma.ads.manager

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.view.View
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.helper.extension.extractAdUnitIdOrNull
import com.lib.ads.gma.ads.helper.banner.BannerHeightConfig
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.model.AdType
import com.lib.ads.gma.ads.model.wrapper.*
import com.lib.ads.gma.ads.util.AppLogger
import kotlinx.coroutines.flow.StateFlow

/** Owns banner ad loading; buffering per placement lives in [com.lib.ads.gma.ads.helper.banner.BannerAds]. */
object BannerAdManager {
    private const val TAG = "BannerAdManager"

    /** Wires click/impression/paid-event analytics for a loaded [BannerAd], scoped to [context]. */
    internal fun bindBannerAdListener(context: Context, ad: BannerAd, callback: BannerAdListener = object : BannerAdListener {}) {
        val adUnitId = ad.getResponseInfo().extractAdUnitIdOrNull().orEmpty()
        ad.adEventCallback = object : BannerAdEventCallback {
            override fun onAdClicked() {
                AdsLogEventManager.logClickAdsEvent(context, adUnitId)
                callback.onClicked(null)
            }
            override fun onAdImpression() {
                AdsLogEventManager.onTrackImpression(context)
                callback.onImpression(null)
            }
            override fun onAdPaid(value: AdValue) {
                AdsLogEventManager.logPaidAdImpression(context, value, ad.getResponseInfo(), AdType.BANNER)
                callback.onPaid(value)
            }
            override fun onAppEvent(name: String, data: String?) = callback.onAppEvent(name, data)
            override fun onAdShowedFullScreenContent() = callback.onShownFullScreenContent()
            override fun onAdDismissedFullScreenContent() = callback.onDismissedFullScreenContent()
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError) = callback.onFailedToShowFullScreenContent(ApAdError(fullScreenContentError))
        }
        ad.bannerAdRefreshCallback = object : com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback {
            override fun onAdRefreshed() = callback.onRefreshed()
            override fun onAdFailedToRefresh(adError: LoadAdError) = callback.onFailedToRefresh(ApAdError(adError))
        }
    }

    fun loadBanner(context: Context, id: String, callback: BannerAdListener? = null) {
        whenAdsReady { requestLoadBannerRaw(context, id, null, false, 0, callback ?: object : BannerAdListener {}) }
    }

    fun requestLoadBanner(
        context: Context,
        idBannerAd: String,
        collapsibleGravity: String?,
        adCallback: BannerAdListener,
        size: BannerSize = BannerSize.LargePortraitAdaptive,
        placementId: Long? = null,
    ) {
        whenAdsReady {
            val params = bannerSizeParams(size)
            requestLoadBannerRaw(
                context = context,
                id = idBannerAd,
                collapsibleGravity = collapsibleGravity,
                useInlineAdaptive = params.useInlineAdaptive,
                maxHeight = params.maxHeight,
                callback = adCallback,
                placementId = placementId,
                widthDp = params.widthDp,
                heightConfig = params.heightConfig,
                customWidthDp = params.customWidthDp,
                customHeightDp = params.customHeightDp,
            )
        }
    }

    fun loadBannerList(
        context: Context,
        listId: List<String>?,
        collapsibleGravity: String?,
        adCallback: BannerAdListener,
        size: BannerSize = BannerSize.LargePortraitAdaptive,
        placementId: Long? = null,
    ) {
        whenAdsReady {
            val ids = listId.orEmpty()
            if (ids.isEmpty()) {
                adCallback.onFailed(ApAdError("Banner ad unit list is empty"))
                return@whenAdsReady
            }
            val params = bannerSizeParams(size)

            fun loadAt(index: Int) {
                if (index >= ids.size) {
                    adCallback.onFailed(ApAdError("All banner ad units failed"))
                    return
                }
                AppLogger.d(TAG, "loadBannerList index=$index id=${ids[index]}")
                // Delegate everything (click/impression/paid/refresh) to the caller; only a
                // failure is intercepted to fall through to the next id.
                val callback = object : BannerAdListener by adCallback {
                    override fun onFailed(error: ApAdError) {
                        if (index + 1 < ids.size) loadAt(index + 1) else adCallback.onFailed(error)
                    }
                }
                requestLoadBannerRaw(
                    context = context,
                    id = ids[index],
                    collapsibleGravity = collapsibleGravity,
                    useInlineAdaptive = params.useInlineAdaptive,
                    maxHeight = params.maxHeight,
                    callback = callback,
                    placementId = placementId,
                    widthDp = params.widthDp,
                    heightConfig = params.heightConfig,
                    customWidthDp = params.customWidthDp,
                    customHeightDp = params.customHeightDp,
                )
            }
            loadAt(0)
        }
    }

    /** Maps [BannerSize] to the raw params [requestLoadBannerRaw] understands. */
    private class BannerSizeParams(
        val useInlineAdaptive: Boolean,
        val maxHeight: Int,
        val widthDp: Int?,
        val heightConfig: BannerHeightConfig,
        val customWidthDp: Int?,
        val customHeightDp: Int?,
    )

    private fun bannerSizeParams(size: BannerSize): BannerSizeParams = when (size) {
        BannerSize.LargePortraitAdaptive -> BannerSizeParams(
            useInlineAdaptive = false,
            maxHeight = 0,
            widthDp = null,
            heightConfig = BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE,
            customWidthDp = null,
            customHeightDp = null,
        )
        is BannerSize.InlineAdaptive -> BannerSizeParams(
            useInlineAdaptive = true,
            maxHeight = size.maxHeightDp.coerceAtLeast(1),
            widthDp = null,
            heightConfig = BannerHeightConfig.INLINE_ADAPTIVE,
            customWidthDp = null,
            customHeightDp = null,
        )
        is BannerSize.Width -> BannerSizeParams(
            useInlineAdaptive = false,
            maxHeight = 0,
            widthDp = size.widthDp,
            heightConfig = BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE,
            customWidthDp = null,
            customHeightDp = null,
        )
        is BannerSize.Height -> BannerSizeParams(
            useInlineAdaptive = false,
            maxHeight = 0,
            widthDp = null,
            heightConfig = BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE,
            customWidthDp = null,
            customHeightDp = size.heightDp,
        )
        is BannerSize.Fixed -> BannerSizeParams(
            useInlineAdaptive = false,
            maxHeight = 0,
            widthDp = size.widthDp,
            heightConfig = BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE,
            customWidthDp = size.widthDp,
            customHeightDp = size.heightDp,
        )
    }

    /** Loads a fresh banner straight from the SDK into a new [AdView] (purchase gate + load call). */
    fun requestLoadBannerRaw(
        context: Context,
        id: String,
        collapsibleGravity: String?,
        useInlineAdaptive: Boolean,
        maxHeight: Int,
        callback: BannerAdListener,
        placementId: Long? = null,
        onLoadedAd: ((BannerAd) -> Unit)? = null,
        widthDp: Int? = null,
        heightConfig: BannerHeightConfig = if (useInlineAdaptive) BannerHeightConfig.INLINE_ADAPTIVE else BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE,
        customWidthDp: Int? = null,
        customHeightDp: Int? = null,
    ) {
        if (AppPurchase.getInstance().isPurchased()) {
            callback.onFailed(ApAdError("App is purchased"))
            return
        }
        val appContext = context.applicationContext
        val adView = AdView(appContext)
        adView.setLayerType(View.LAYER_TYPE_HARDWARE, null as Paint?)
        val adSize = getAdSize(appContext, useInlineAdaptive, maxHeight, widthDp, heightConfig, customWidthDp, customHeightDp)
        val request = getAdRequestForCollapsibleBanner(id, adSize, collapsibleGravity, placementId)
        adView.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) {
                onLoadedAd?.invoke(ad)
                bindBannerAdListener(appContext, ad, callback)
                callback.onLoaded(adView)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                adView.destroy()
                callback.onFailed(ApAdError(adError))
            }
        })
    }

    private fun getAdSize(context: Context, useInlineAdaptive: Boolean, maxHeight: Int, widthDp: Int? = null, heightConfig: BannerHeightConfig = if (useInlineAdaptive) BannerHeightConfig.INLINE_ADAPTIVE else BannerHeightConfig.LARGE_PORTRAIT_ADAPTIVE, customWidthDp: Int? = null, customHeightDp: Int? = null): AdSize {
        if (customWidthDp != null && customHeightDp != null && customWidthDp > 0 && customHeightDp > 0) {
            return AdSize(customWidthDp, customHeightDp)
        }
        val density = context.resources.displayMetrics.density
        val resolvedWidthDp = (widthDp ?: (context.resources.displayMetrics.widthPixels / density).toInt()).coerceAtLeast(1)
        if (customHeightDp != null && customHeightDp > 0) {
            return AdSize(resolvedWidthDp, customHeightDp)
        }
        return if (heightConfig == BannerHeightConfig.INLINE_ADAPTIVE) {
            AdSize.getInlineAdaptiveBannerAdSize(resolvedWidthDp, maxHeight.coerceAtLeast(1))
        } else {
            AdSize.getLargePortraitAnchoredAdaptiveBannerAdSize(context, resolvedWidthDp)
        }
    }

    fun getAdRequestForCollapsibleBanner(
        adUnitId: String,
        adSize: AdSize,
        gravity: String?,
        placementId: Long? = null,
    ): BannerAdRequest {
        val request = BannerAdRequest.Builder(adUnitId, adSize)
        if (!gravity.isNullOrEmpty()) {
            request.setGoogleExtrasBundle(android.os.Bundle().apply {
                putString("collapsible", gravity)
            })
        }
        placementId?.let(request::setPlacementId)
        return request.build()
    }
}
