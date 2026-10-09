package com.lib.ads.gma.ads.helper.appopen.preload

import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.engine.AdsProvider
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.helper.interstitial.preload.InterstitialPreloadStrategy
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.AppOpenAdListener
import com.lib.ads.gma.ads.util.AdConfigKeyBlocklist
import java.util.concurrent.atomic.AtomicInteger

object AppOpenAdPreload {
    private const val MAX_PARALLEL_INFLIGHT = 4
    private val store = FullScreenAdStore<AppOpenAd>(
        ttlMs = 4 * 60 * 60 * 1000L,
        dispose = { it.destroy() },
    )

    @JvmStatic
    fun preload(
        adUnits: List<WeightedAdUnit>,
        strategy: InterstitialPreloadStrategy = InterstitialPreloadStrategy.WATERFALL,
        listener: AppOpenAdListener? = null,
        placementId: Long? = null,
        configKey: String = "",
    ) {
        AdConfigKeyBlocklist.observeKey(configKey)
        if (AdConfigKeyBlocklist.isBlocked(configKey)) {
            AdConfigKeyBlocklist.logBlockedOnce(configKey, "app-open preload")
            return
        }
        val units = FullScreenAdStore.dedupeMaxWeight(adUnits).sortedByDescending { it.weight }
        if (units.isEmpty()) {
            listener?.onFailed(ApAdError("App-open preload list is empty"))
            return
        }
        whenAdsReady {
            runOnMain {
                when (strategy) {
                    InterstitialPreloadStrategy.WATERFALL -> preloadWaterfall(units, 0, listener, placementId)
                    InterstitialPreloadStrategy.PARALLEL_ALL -> preloadParallel(units, listener, placementId)
                }
            }
        }
    }

    @JvmStatic
    fun pollAppOpen(adUnits: List<WeightedAdUnit>, fallbackAny: Boolean = false): AppOpenAd? =
        store.pollBest(adUnits)?.ad ?: if (fallbackAny) store.pollBestAny()?.ad else null

    @JvmStatic
    suspend fun pollAppOpenAwait(
        adUnits: List<WeightedAdUnit>,
        timeoutMs: Long,
        fallbackAny: Boolean = false,
    ): AppOpenAd? =
        store.pollBestAwait(adUnits, timeoutMs)?.ad ?: if (fallbackAny) store.pollBestAny()?.ad else null

    @JvmStatic
    fun hasReady(adUnits: List<WeightedAdUnit>): Boolean = store.hasReady(adUnits)

    @JvmStatic
    fun hasAnyReady(): Boolean = store.hasAnyReady()

    @JvmStatic
    fun release(adUnitId: String) = store.release(adUnitId)

    @JvmStatic
    fun releaseAll() = store.releaseAll()

    @JvmStatic
    fun toWeightedUnits(adUnitIds: List<String>): List<WeightedAdUnit> {
        val size = adUnitIds.size
        return adUnitIds.mapIndexedNotNull { index, id ->
            val normalized = id.trim()
            if (normalized.isEmpty()) null else WeightedAdUnit(normalized, (size - index).toFloat())
        }.let(FullScreenAdStore.Companion::dedupeMaxWeight)
    }

    private fun preloadWaterfall(
        units: List<WeightedAdUnit>,
        index: Int,
        listener: AppOpenAdListener?,
        placementId: Long?,
    ) {
        if (index >= units.size) {
            listener?.onFailed(ApAdError("All app-open preload requests failed"))
            return
        }
        val unit = units[index]
        when (val start = store.tryStartLoading(unit.adUnitId)) {
            FullScreenAdStore.StartLoad.AlreadyReady -> listener?.onReady()
            FullScreenAdStore.StartLoad.Loading -> preloadWaterfall(units, index + 1, listener, placementId)
            is FullScreenAdStore.StartLoad.Started -> loadOne(unit.adUnitId, placementId, object : AppOpenLoadCallback {
                override fun onLoaded(ad: AppOpenAd) {
                    store.putIfCurrent(start.token, unit.weight, ad)
                    listener?.onLoaded()
                }

                override fun onFailed(error: ApAdError) {
                    store.completeIfCurrent(start.token)
                    preloadWaterfall(units, index + 1, listener, placementId)
                }
            })
        }
    }

    private fun preloadParallel(
        units: List<WeightedAdUnit>,
        listener: AppOpenAdListener?,
        placementId: Long?,
    ) {
        val selected = units.take(MAX_PARALLEL_INFLIGHT)
        val pending = AtomicInteger(selected.size)
        val loaded = AtomicInteger(0)
        selected.forEach { unit ->
            when (val start = store.tryStartLoading(unit.adUnitId)) {
                FullScreenAdStore.StartLoad.AlreadyReady -> {
                    loaded.incrementAndGet()
                    listener?.onReady()
                    completeParallelSlot(pending, loaded, listener)
                }
                FullScreenAdStore.StartLoad.Loading -> completeParallelSlot(pending, loaded, listener)
                is FullScreenAdStore.StartLoad.Started -> loadOne(unit.adUnitId, placementId, object : AppOpenLoadCallback {
                    override fun onLoaded(ad: AppOpenAd) {
                        if (store.putIfCurrent(start.token, unit.weight, ad)) {
                            loaded.incrementAndGet()
                            listener?.onLoaded()
                        }
                        completeParallelSlot(pending, loaded, listener)
                    }

                    override fun onFailed(error: ApAdError) {
                        store.completeIfCurrent(start.token)
                        completeParallelSlot(pending, loaded, listener)
                    }
                })
            }
        }
    }

    private fun loadOne(adUnitId: String, placementId: Long?, callback: AppOpenLoadCallback) {
        AppOpenAd.load(
            AdsManager.getAdRequest(adUnitId, placementId),
            object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) = callback.onLoaded(ad)
                override fun onAdFailedToLoad(adError: LoadAdError) =
                    callback.onFailed(ApAdError(adError))
            },
        )
    }

    private fun completeParallelSlot(
        pending: AtomicInteger,
        loaded: AtomicInteger,
        listener: AppOpenAdListener?,
    ) {
        if (pending.decrementAndGet() == 0 && loaded.get() == 0) {
            listener?.onFailed(ApAdError("All app-open preload requests failed"))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else AdsProvider.MAIN.post(block)
    }

    private interface AppOpenLoadCallback {
        fun onLoaded(ad: AppOpenAd)
        fun onFailed(error: ApAdError)
    }
}
