package com.lib.ads.gma.ads.helper.adnative

import androidx.annotation.LayoutRes
import com.lib.ads.gma.ads.helper.adnative.params.NativeLayoutMediation
import java.util.concurrent.TimeUnit

class NativeAdSpec(
    val tag: String,
    val adUnitIds: List<String>,
    @LayoutRes val defaultLayoutId: Int = 0,
    val canShowAds: Boolean = true,
    val canReloadAds: Boolean = true,
    val bufferSize: Int = 1,
    val ttlMs: Long = TimeUnit.HOURS.toMillis(4),
    val timeoutsMs: List<Long> = emptyList(),
    /** Request settings (AdChoices, media ratio, video, ad types...) used by this tag's preloads. */
    val requestOptions: NativeAdRequestOptions = NativeAdRequestOptions(),
) {
    val listId: List<String> get() = adUnitIds
    val idAds: String get() = adUnitIds.lastOrNull().orEmpty()
    val usePreloadBuffer: Boolean get() = bufferSize > 0
    var layoutByMediation: List<NativeLayoutMediation> = emptyList()
    fun setLayoutMediation(vararg layouts: NativeLayoutMediation) =
        apply { layoutByMediation = layouts.toList() }

    fun setLayoutMediation(layouts: List<NativeLayoutMediation>) =
        apply { layoutByMediation = layouts }

    companion object {
        fun simple(
            tag: String,
            adUnitId: String,
            bufferSize: Int = 1,
            @LayoutRes layoutId: Int = 0,
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true
        ) = NativeAdSpec(tag, listOf(adUnitId), layoutId, canShowAds, canReloadAds, bufferSize)

        fun waterfall(
            tag: String,
            adUnitIds: List<String>,
            bufferSize: Int = 1,
            @LayoutRes layoutId: Int = 0,
            timeoutsMs: List<Long> = emptyList(),
            canShowAds: Boolean = true,
            canReloadAds: Boolean = true
        ) = NativeAdSpec(
            tag,
            adUnitIds,
            layoutId,
            canShowAds,
            canReloadAds,
            bufferSize,
            timeoutsMs = timeoutsMs
        )
    }
}

sealed class PreloadBufferState {
    data object Idle : PreloadBufferState();
    data class Loading(val loaded: Int, val requested: Int) : PreloadBufferState()
    data class ItemLoaded(val loaded: Int, val requested: Int) : PreloadBufferState()
    data class Ready(val available: Int) : PreloadBufferState();
    data class Error(val message: String) : PreloadBufferState();
    data object Cancelled : PreloadBufferState()
}
