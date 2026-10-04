package com.lib.ads.gma.ads.helper.interstitial.preload

import android.os.Looper
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.manager.InterstitialAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener
import com.lib.ads.gma.ads.util.AdConfigKeyBlocklist
import java.util.concurrent.atomic.AtomicInteger

enum class InterstitialPreloadStrategy {
    WATERFALL,
    PARALLEL_ALL,
}

object InterstitialAdPreload {
    private const val MAX_PARALLEL_INFLIGHT = 6

    private val store = FullScreenAdStore<ApInterstitialAd>()

    @JvmStatic
    fun preload(
        adUnits: List<WeightedAdUnit>,
        strategy: InterstitialPreloadStrategy = InterstitialPreloadStrategy.WATERFALL,
        listener: InterstitialAdListener? = null,
        placementId: Long? = null,
        configKey: String = "",
    ) {
        AdConfigKeyBlocklist.observeKey(configKey)
        if (AdConfigKeyBlocklist.isBlocked(configKey)) {
            AdConfigKeyBlocklist.logBlockedOnce(configKey, "interstitial preload")
            return
        }
        val units = FullScreenAdStore.dedupeMaxWeight(adUnits)
            .sortedByDescending { it.weight }
        if (units.isEmpty()) {
            listener?.onFailed(ApAdError("Interstitial preload list is empty"))
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
    fun preloadIds(
        adUnitIds: List<String>,
        listener: InterstitialAdListener? = null,
        placementId: Long? = null,
    ) = preload(toWeightedUnits(adUnitIds), InterstitialPreloadStrategy.WATERFALL, listener, placementId)

    @JvmStatic
    fun pollInterstitial(adUnits: List<WeightedAdUnit>): ApInterstitialAd? =
        store.pollBest(adUnits)?.ad

    @JvmStatic
    fun pollInterstitial(adUnits: List<WeightedAdUnit>, fallbackAny: Boolean): ApInterstitialAd? =
        store.pollBest(adUnits)?.ad ?: if (fallbackAny) store.pollBestAny()?.ad else null

    @JvmStatic
    suspend fun pollInterstitialAwait(
        adUnits: List<WeightedAdUnit>,
        timeoutMs: Long,
        fallbackAny: Boolean = false,
    ): ApInterstitialAd? =
        store.pollBestAwait(adUnits, timeoutMs)?.ad ?: if (fallbackAny) store.pollBestAny()?.ad else null

    @JvmStatic
    fun pollInterstitialAny(): ApInterstitialAd? =
        store.pollBestAny()?.ad

    @JvmStatic
    fun hasReadyPreload(adUnits: List<WeightedAdUnit>): Boolean =
        store.hasReady(adUnits)

    @JvmStatic
    fun hasAnyPreload(): Boolean = store.hasAnyReady()

    @JvmStatic
    fun isBlocked(configKey: String): Boolean = AdConfigKeyBlocklist.isBlocked(configKey)

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
        listener: InterstitialAdListener?,
        placementId: Long?,
    ) {
        if (index >= units.size) {
            listener?.onFailed(ApAdError("All interstitial preload requests failed"))
            return
        }

        val unit = units[index]
        when (val start = store.tryStartLoading(unit.adUnitId)) {
            FullScreenAdStore.StartLoad.AlreadyReady -> listener?.onReady()
            FullScreenAdStore.StartLoad.Loading -> preloadWaterfall(units, index + 1, listener, placementId)
            is FullScreenAdStore.StartLoad.Started -> {
                InterstitialAdManager.loadInterstitialAdRaw(
                    unit.adUnitId,
                    object : InterstitialAdListener {
                        override fun onLoaded(ad: ApInterstitialAd) {
                            store.putIfCurrent(start.token, unit.weight, ad)
                            listener?.onLoaded(ad)
                        }

                        override fun onFailed(error: ApAdError) {
                            store.completeIfCurrent(start.token)
                            preloadWaterfall(units, index + 1, listener, placementId)
                        }
                    },
                    placementId,
                )
            }
        }
    }

    private fun preloadParallel(
        units: List<WeightedAdUnit>,
        listener: InterstitialAdListener?,
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
                is FullScreenAdStore.StartLoad.Started -> {
                    InterstitialAdManager.loadInterstitialAdRaw(
                        unit.adUnitId,
                        object : InterstitialAdListener {
                            override fun onLoaded(ad: ApInterstitialAd) {
                                if (store.putIfCurrent(start.token, unit.weight, ad)) {
                                    loaded.incrementAndGet()
                                    listener?.onLoaded(ad)
                                }
                                completeParallelSlot(pending, loaded, listener)
                            }

                            override fun onFailed(error: ApAdError) {
                                store.completeIfCurrent(start.token)
                                completeParallelSlot(pending, loaded, listener)
                            }
                        },
                        placementId,
                    )
                }
            }
        }
    }

    private fun completeParallelSlot(
        pending: AtomicInteger,
        loaded: AtomicInteger,
        listener: InterstitialAdListener?,
    ) {
        if (pending.decrementAndGet() == 0 && loaded.get() == 0) {
            listener?.onFailed(ApAdError("All interstitial preload requests failed"))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else Ads.MAIN.post(block)
    }
}
