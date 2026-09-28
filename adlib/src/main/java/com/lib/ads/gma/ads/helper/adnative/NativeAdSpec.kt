package com.lib.ads.gma.ads.helper.adnative

import android.util.Log
import androidx.annotation.IntRange
import androidx.annotation.LayoutRes
import com.google.android.gms.ads.nativead.NativeAd
import com.lib.ads.gma.ads.helper.IAdsConfig
import java.util.concurrent.TimeUnit

/**
 * The single configuration object for native ads.
 *
 * Replaces the previous three overlapping configs (`NativeAdConfig`,
 * `NativeAdTagConfig`, `NativeAdPreloadConfig`). A spec describes both **how to load**
 * (ad unit ids, per-id timeouts, waterfall) and **how to preload/cache** (buffer size,
 * ttl) for a logical placement identified by [tag].
 *
 * @param tag unique id of this placement / preload buffer.
 * @param adUnitIds ad unit ids tried in waterfall order (first = primary).
 * @param defaultLayoutId layout for XML inflation; 0 for Compose / layout-free use.
 * @param canShowAds gate from remote config / app logic.
 * @param canReloadAds whether the ad may be reloaded on resume.
 * @param bufferSize number of ads to keep preloaded (0 disables the buffer → always cold load).
 * @param ttlMs lifetime of a buffered ad before it is pruned.
 * @param timeoutsMs per-id load timeout (index-aligned with [adUnitIds]).
 */
class NativeAdSpec(
    val tag: String,
    val adUnitIds: List<String>,
    @LayoutRes val defaultLayoutId: Int = 0,
    override val canShowAds: Boolean = true,
    override val canReloadAds: Boolean = true,
    @IntRange(from = 0, to = 10) val bufferSize: Int = 1,
    val ttlMs: Long = TimeUnit.HOURS.toMillis(4),
    val timeoutsMs: List<Long> = emptyList(),
) : IAdsConfig {

    /** [IAdsConfig.listId] is this spec's [adUnitIds]; [IAdsConfig.idAds] is derived from it. */
    override val listId: List<String> get() = adUnitIds

    /** True when a valid XML layout is set; false for Compose/layout-free usage. */
    val hasLayoutId: Boolean get() = defaultLayoutId != 0

    /** True when this placement should pull from / fill the preload buffer. */
    val usePreloadBuffer: Boolean get() = bufferSize > 0

    /** Optional per-mediation layout overrides. */
    var layoutByMediation: List<NativeLayoutMediation> = emptyList()
        private set

    fun setLayoutMediation(vararg layouts: NativeLayoutMediation) = apply {
        layoutByMediation = layouts.toList()
    }

    fun setLayoutMediation(layouts: List<NativeLayoutMediation>) = apply {
        layoutByMediation = layouts
    }

    fun getAllAdUnitIds(): List<String> = adUnitIds.ifEmpty { listOf(idAds) }.filter { it.isNotBlank() }

    /** Resolve the layout for [nativeAd], honouring [layoutByMediation], else [defaultLayoutId]. */
    @LayoutRes
    fun getLayoutIdByMediationNativeAd(nativeAd: NativeAd?): Int {
        if (layoutByMediation.isEmpty() || nativeAd == null) return defaultLayoutId
        val mediation = AdNativeMediation.get(nativeAd)
        return layoutByMediation.find { it.mediationType == mediation }
            ?.also { Log.d("NativeAdSpec", "show with mediation ${it.mediationType.name}") }
            ?.layoutId ?: defaultLayoutId
    }

    companion object {
        /** Single ad unit, preload buffer of [bufferSize] (0 = no preload). */
        fun simple(
            tag: String,
            adUnitId: String,
            @LayoutRes layoutId: Int = 0,
            bufferSize: Int = 1,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ) = NativeAdSpec(
            tag = tag,
            adUnitIds = listOf(adUnitId),
            defaultLayoutId = layoutId,
            bufferSize = bufferSize,
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
        )

        /** Waterfall of ad units, preload buffer of [bufferSize] (0 = no preload). */
        fun waterfall(
            tag: String,
            adUnitIds: List<String>,
            @LayoutRes layoutId: Int = 0,
            bufferSize: Int = 1,
            timeoutsMs: List<Long> = emptyList(),
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true,
        ) = NativeAdSpec(
            tag = tag,
            adUnitIds = adUnitIds,
            defaultLayoutId = layoutId,
            bufferSize = bufferSize,
            timeoutsMs = timeoutsMs,
            canShowAds = canShowAds,
            canReloadAds = canReloadAds,
        )
    }
}
