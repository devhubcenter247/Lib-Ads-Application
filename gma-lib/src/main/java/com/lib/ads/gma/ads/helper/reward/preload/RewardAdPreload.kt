package com.lib.ads.gma.ads.helper.reward.preload

import android.os.Looper
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.engine.whenAdsReady
import com.lib.ads.gma.ads.helper.fullscreen.preload.FullScreenAdStore
import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.helper.interstitial.preload.InterstitialPreloadStrategy
import com.lib.ads.gma.ads.manager.RewardAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApRewardAd
import com.lib.ads.gma.ads.model.wrapper.RewardAdListener
import com.lib.ads.gma.ads.util.AdConfigKeyBlocklist
import java.util.concurrent.atomic.AtomicInteger

object RewardAdPreload {
    private const val MAX_PARALLEL_INFLIGHT = 6

    private val rewardStore = FullScreenAdStore<ApRewardAd>()
    private val rewardInterstitialStore = FullScreenAdStore<ApRewardAd>()

    @JvmStatic
    fun preloadReward(
        adUnits: List<WeightedAdUnit>,
        strategy: InterstitialPreloadStrategy = InterstitialPreloadStrategy.WATERFALL,
        listener: RewardAdListener? = null,
        placementId: Long? = null,
        configKey: String = "",
    ) {
        preload(
            store = rewardStore,
            adUnits = adUnits,
            strategy = strategy,
            listener = listener,
            configKey = configKey,
            where = "reward preload",
            load = { id, callback -> RewardAdManager.loadRewardAdRaw(id, callback, placementId) },
        )
    }

    @JvmStatic
    fun preloadRewardInterstitial(
        adUnits: List<WeightedAdUnit>,
        strategy: InterstitialPreloadStrategy = InterstitialPreloadStrategy.WATERFALL,
        listener: RewardAdListener? = null,
        placementId: Long? = null,
        ssvCustomData: String? = null,
        configKey: String = "",
    ) {
        preload(
            store = rewardInterstitialStore,
            adUnits = adUnits,
            strategy = strategy,
            listener = listener,
            configKey = configKey,
            where = "reward-interstitial preload",
            load = { id, callback ->
                RewardAdManager.loadRewardInterstitialAdRaw(id, callback, placementId, ssvCustomData)
            },
        )
    }

    @JvmStatic
    fun pollReward(adUnits: List<WeightedAdUnit>, fallbackAny: Boolean = false): ApRewardAd? =
        rewardStore.pollBest(adUnits)?.ad ?: if (fallbackAny) rewardStore.pollBestAny()?.ad else null

    @JvmStatic
    fun pollRewardInterstitial(adUnits: List<WeightedAdUnit>, fallbackAny: Boolean = false): ApRewardAd? =
        rewardInterstitialStore.pollBest(adUnits)?.ad
            ?: if (fallbackAny) rewardInterstitialStore.pollBestAny()?.ad else null

    @JvmStatic
    suspend fun pollRewardAwait(
        adUnits: List<WeightedAdUnit>,
        timeoutMs: Long,
        fallbackAny: Boolean = false,
    ): ApRewardAd? =
        rewardStore.pollBestAwait(adUnits, timeoutMs)?.ad
            ?: if (fallbackAny) rewardStore.pollBestAny()?.ad else null

    @JvmStatic
    fun hasReadyReward(adUnits: List<WeightedAdUnit>): Boolean = rewardStore.hasReady(adUnits)

    @JvmStatic
    fun hasReadyRewardInterstitial(adUnits: List<WeightedAdUnit>): Boolean =
        rewardInterstitialStore.hasReady(adUnits)

    @JvmStatic
    fun hasAnyRewardReady(): Boolean = rewardStore.hasAnyReady()

    @JvmStatic
    fun hasAnyRewardInterstitialReady(): Boolean = rewardInterstitialStore.hasAnyReady()

    @JvmStatic
    fun releaseReward(adUnitId: String) = rewardStore.release(adUnitId)

    @JvmStatic
    fun releaseRewardInterstitial(adUnitId: String) = rewardInterstitialStore.release(adUnitId)

    @JvmStatic
    fun releaseAll() {
        rewardStore.releaseAll()
        rewardInterstitialStore.releaseAll()
    }

    @JvmStatic
    fun toWeightedUnits(adUnitIds: List<String>): List<WeightedAdUnit> =
        InterstitialAdPreloadCompat.toWeightedUnits(adUnitIds)

    private fun preload(
        store: FullScreenAdStore<ApRewardAd>,
        adUnits: List<WeightedAdUnit>,
        strategy: InterstitialPreloadStrategy,
        listener: RewardAdListener?,
        configKey: String,
        where: String,
        load: (String, RewardAdListener) -> Unit,
    ) {
        AdConfigKeyBlocklist.observeKey(configKey)
        if (AdConfigKeyBlocklist.isBlocked(configKey)) {
            AdConfigKeyBlocklist.logBlockedOnce(configKey, where)
            return
        }
        val units = FullScreenAdStore.dedupeMaxWeight(adUnits).sortedByDescending { it.weight }
        if (units.isEmpty()) {
            listener?.onFailed(ApAdError("Reward preload list is empty"))
            return
        }
        whenAdsReady {
            runOnMain {
                when (strategy) {
                    InterstitialPreloadStrategy.WATERFALL -> preloadWaterfall(store, units, 0, listener, load)
                    InterstitialPreloadStrategy.PARALLEL_ALL -> preloadParallel(store, units, listener, load)
                }
            }
        }
    }

    private fun preloadWaterfall(
        store: FullScreenAdStore<ApRewardAd>,
        units: List<WeightedAdUnit>,
        index: Int,
        listener: RewardAdListener?,
        load: (String, RewardAdListener) -> Unit,
    ) {
        if (index >= units.size) {
            listener?.onFailed(ApAdError("All reward preload requests failed"))
            return
        }
        val unit = units[index]
        when (val start = store.tryStartLoading(unit.adUnitId)) {
            FullScreenAdStore.StartLoad.AlreadyReady -> Unit
            FullScreenAdStore.StartLoad.Loading -> preloadWaterfall(store, units, index + 1, listener, load)
            is FullScreenAdStore.StartLoad.Started -> load(unit.adUnitId, object : RewardAdListener {
                override fun onLoaded(ad: ApRewardAd) {
                    store.putIfCurrent(start.token, unit.weight, ad)
                    listener?.onLoaded(ad)
                }

                override fun onFailed(error: ApAdError) {
                    store.completeIfCurrent(start.token)
                    preloadWaterfall(store, units, index + 1, listener, load)
                }
            })
        }
    }

    private fun preloadParallel(
        store: FullScreenAdStore<ApRewardAd>,
        units: List<WeightedAdUnit>,
        listener: RewardAdListener?,
        load: (String, RewardAdListener) -> Unit,
    ) {
        val selected = units.take(MAX_PARALLEL_INFLIGHT)
        val pending = AtomicInteger(selected.size)
        val loaded = AtomicInteger(0)
        selected.forEach { unit ->
            when (val start = store.tryStartLoading(unit.adUnitId)) {
                FullScreenAdStore.StartLoad.AlreadyReady -> {
                    loaded.incrementAndGet()
                    completeParallelSlot(pending, loaded, listener)
                }
                FullScreenAdStore.StartLoad.Loading -> completeParallelSlot(pending, loaded, listener)
                is FullScreenAdStore.StartLoad.Started -> load(unit.adUnitId, object : RewardAdListener {
                    override fun onLoaded(ad: ApRewardAd) {
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
                })
            }
        }
    }

    private fun completeParallelSlot(
        pending: AtomicInteger,
        loaded: AtomicInteger,
        listener: RewardAdListener?,
    ) {
        if (pending.decrementAndGet() == 0 && loaded.get() == 0) {
            listener?.onFailed(ApAdError("All reward preload requests failed"))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else Ads.MAIN.post(block)
    }
}

private object InterstitialAdPreloadCompat {
    fun toWeightedUnits(adUnitIds: List<String>): List<WeightedAdUnit> {
        val size = adUnitIds.size
        return adUnitIds.mapIndexedNotNull { index, id ->
            val normalized = id.trim()
            if (normalized.isEmpty()) null else WeightedAdUnit(normalized, (size - index).toFloat())
        }.let(FullScreenAdStore.Companion::dedupeMaxWeight)
    }
}
