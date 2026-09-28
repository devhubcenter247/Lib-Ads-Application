package com.lib.ads.gma.ads.helper.adnative.core

import android.content.Context
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.lib.ads.gma.ads.ads.AdsCallback
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd
import com.lib.ads.gma.ads.admob.AdsConsentManager
import com.lib.ads.gma.ads.helper.adnative.AdUnitTagger
import com.lib.ads.gma.ads.helper.isOnline
import com.lib.ads.gma.ads.helper.adnative.NativeAdLog
import com.lib.ads.gma.ads.helper.adnative.provider.NativeAdEventRelay
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdLoadStats
import com.lib.ads.gma.ads.util.AdType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single source of truth for loading AdMob native ads in this library.
 *
 * Every native code path — cold display ([com.lib.ads.gma.ads.manager.NativeAdManager]),
 * preload buffer ([com.lib.ads.gma.ads.helper.adnative.NativeAds]) and the Compose
 * holder — funnels through here so cross-cutting concerns are applied **exactly once and
 * consistently**:
 *
 * - purchase / test-id guards ([AdsManager])
 * - paid-event logging ([AdsManager.logPaidEvent])
 * - impression / click bookkeeping ([AdsManager.handleAdImpression] / [AdsManager.handleAdClick])
 * - load counting ([AdLoadStats])
 * - per-ad event fan-out via [NativeAdEventRelay]
 *
 * This replaced three near-identical `loadOne`/`waterfall` implementations.
 */
internal object NativeAdLoader {

    const val DEFAULT_TIMEOUT_MS = 10_000L

    sealed class LoadResult {
        data class Success(
            val ad: NativeAd,
            val adUnitId: String,
            val relay: NativeAdEventRelay
        ) : LoadResult()

        data class Failure(val error: LoadAdError?, val adUnitId: String) : LoadResult()
    }

    /** Standard options: start video muted (display surfaces decide audio). */
    fun defaultOptions(): NativeAdOptions = NativeAdOptions.Builder()
        .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
        .build()

    /**
     * Load a single native ad from [adUnitId].
     *
     * Suspends until the ad either loads or fails. Honours coroutine cancellation.
     *
     * @param layoutCustomNative layout applied to the [ApNativeAd] handed to [callbacks]
     *   (0 when the layout is resolved later, e.g. preload buffer).
     * @param callbacks listeners notified of load/impression/click in addition to [relay].
     * @param relay per-ad relay that travels with the ad to its eventual consumer.
     */
    suspend fun loadOne(
        context: Context,
        adUnitId: String,
        options: NativeAdOptions = defaultOptions(),
        tag: String? = null,
        layoutCustomNative: Int = 0,
        callbacks: List<AdsCallback> = emptyList(),
        relay: NativeAdEventRelay = NativeAdEventRelay()
    ): LoadResult = suspendCancellableCoroutine { cont ->
        if (!AdsManager.canRequestAds) {
            NativeAdLog.d("load", tag, "skip request — ad requests disabled by canRequestAds switch", unit = adUnitId)
            if (cont.isActive) cont.resume(LoadResult.Failure(null, adUnitId))
            return@suspendCancellableCoroutine
        }
        if (!AdsConsentManager.getConsentResult(context)) {
            NativeAdLog.d("load", tag, "skip request — UMP cannot request ads", unit = adUnitId)
            if (cont.isActive) cont.resume(LoadResult.Failure(null, adUnitId))
            return@suspendCancellableCoroutine
        }
        if (AdsManager.isPurchased(context)) {
            NativeAdLog.d("load", tag, "skip request — user is purchased", unit = adUnitId)
            if (cont.isActive) cont.resume(LoadResult.Failure(null, adUnitId))
            return@suspendCancellableCoroutine
        }
        if (!isOnline(context)) {
            NativeAdLog.d("load", tag, "skip request — device is offline", unit = adUnitId)
            if (cont.isActive) cont.resume(LoadResult.Failure(null, adUnitId))
            return@suspendCancellableCoroutine
        }
        AdsManager.checkTestId(context, AD_TYPE_NATIVE_CODE, adUnitId)
        AdLoadStats.recordRequested(AdType.NATIVE, tag, adUnitId)
        NativeAdLog.d("load", tag, "requesting AdMob native…", unit = adUnitId)
        val startedAt = System.currentTimeMillis()

        val appCtx = context.applicationContext
        var acceptedAd = false

        val loader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                if (!cont.isActive) {
                    NativeAdLog.w("load", tag, "late load ignored after timeout/cancel", unit = adUnitId)
                    ad.destroy()
                    return@forNativeAd
                }
                AdUnitTagger.tag(ad, adUnitId)
                ad.mediaContent?.videoController?.videoLifecycleCallbacks =
                    relay.buildVideoLifecycleCallbacks()
                ad.setOnPaidEventListener { adValue ->
                    AdsManager.logPaidEvent(appCtx, adValue, adUnitId, ad.responseInfo, AdType.NATIVE)
                }
                AdLoadStats.recordLoaded(AdType.NATIVE, tag, adUnitId)
                NativeAdLog.d(
                    "load", tag,
                    "loaded in ${System.currentTimeMillis() - startedAt}ms via ${ad.responseInfo?.mediationAdapterClassName ?: "?"}",
                    unit = adUnitId
                )
                acceptedAd = true
                cont.resume(LoadResult.Success(ad, adUnitId, relay))
            }
            .withNativeAdOptions(options)
            .withAdListener(object : AdListener() {
                // Note: failure is reported via the return value, NOT fanned out to
                // [callbacks] — a waterfall must not surface intermediate failures to
                // consumers. Callers map a Failure/null result to onAdFailedToLoad.
                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdLoadStats.recordFailed(AdType.NATIVE, tag, adUnitId, error.code.toString())
                    NativeAdLog.e("load", tag, "failed after ${System.currentTimeMillis() - startedAt}ms code=${error.code} ${error.message}", unit = adUnitId)
                    if (cont.isActive) cont.resume(LoadResult.Failure(error, adUnitId))
                }

                override fun onAdImpression() {
                    if (!acceptedAd) return
                    AdsManager.handleAdImpression()
                    AdLoadStats.recordImpression(AdType.NATIVE, tag, adUnitId)
                    NativeAdLog.d("load", tag, "impression", unit = adUnitId)
                    relay.onAdImpression()
                    callbacks.forEach { it.onAdImpression() }
                }

                override fun onAdClicked() {
                    if (!acceptedAd) return
                    AdsManager.handleAdClick(appCtx, adUnitId)
                    NativeAdLog.d("load", tag, "clicked", unit = adUnitId)
                    relay.onAdClicked()
                    callbacks.forEach { it.onAdClicked() }
                }

                override fun onAdClosed() {
                    if (!acceptedAd) return
                    relay.onAdClosed()
                    callbacks.forEach { it.onAdClosed() }
                }
            })
            .build()

        loader.loadAd(AdsManager.getAdRequest())
    }

    /**
     * Try [adUnitIds] in order, returning the first that loads.
     *
     * @param timeoutsMs per-id timeout (index-aligned); falls back to [DEFAULT_TIMEOUT_MS]
     *   or the first entry for overflow indices. Empty = [DEFAULT_TIMEOUT_MS] for all.
     * @return the first [LoadResult.Success], or null if every id fails/times out.
     */
    suspend fun waterfall(
        context: Context,
        adUnitIds: List<String>,
        timeoutsMs: List<Long> = emptyList(),
        options: NativeAdOptions = defaultOptions(),
        tag: String? = null,
        layoutCustomNative: Int = 0,
        callbacks: List<AdsCallback> = emptyList(),
        relayFor: (adUnitId: String) -> NativeAdEventRelay = { NativeAdEventRelay() }
    ): LoadResult.Success? {
        NativeAdLog.d("load", tag, "waterfall start over ${adUnitIds.size} unit(s): $adUnitIds")
        adUnitIds.forEachIndexed { index, id ->
            val timeout = resolveTimeout(timeoutsMs, index)
            NativeAdLog.d("load", tag, "waterfall step ${index + 1}/${adUnitIds.size} timeout=${timeout}ms", unit = id)
            val result = try {
                withTimeout(timeout.milliseconds) {
                    loadOne(context, id, options, tag, layoutCustomNative, callbacks, relayFor(id))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NativeAdLog.e("load", tag, "waterfall timeout/err: ${e.message}", unit = id)
                AdLoadStats.recordFailed(AdType.NATIVE, tag, id, "timeout")
                null
            }
            if (result is LoadResult.Success) {
                NativeAdLog.d("load", tag, "waterfall succeeded at step ${index + 1}/${adUnitIds.size}", unit = id)
                notifyLoaded(result, layoutCustomNative, callbacks)
                return result
            }
        }
        NativeAdLog.w("load", tag, "waterfall exhausted — all ${adUnitIds.size} unit(s) failed")
        return null
    }

    private fun resolveTimeout(timeoutsMs: List<Long>, index: Int): Long {
        if (timeoutsMs.isEmpty()) return DEFAULT_TIMEOUT_MS
        val t = timeoutsMs.getOrElse(index) { timeoutsMs.first() }
        return if (t > 0) t else DEFAULT_TIMEOUT_MS
    }

    fun notifyLoaded(result: LoadResult.Success, layoutCustomNative: Int = 0, callbacks: List<AdsCallback>) {
        callbacks.forEach {
            it.onAdLoaded()
            it.onNativeAdLoaded(ApNativeAd(layoutCustomNative, result.ad))
        }
    }

    // mirrors the legacy NATIVE_ADS constant used by AdsManager.checkTestId
    private const val AD_TYPE_NATIVE_CODE = 5
}
