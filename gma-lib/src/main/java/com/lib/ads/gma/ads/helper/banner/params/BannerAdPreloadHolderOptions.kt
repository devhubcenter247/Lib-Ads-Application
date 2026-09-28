package com.lib.ads.gma.ads.helper.banner.params

import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity

data class BannerAdPreloadHolderOptions(
    val fallbackAdUnitIds: List<String> = emptyList(),
    val lifecycleOwner: LifecycleOwner? = null,
    val autoRequestOnStart: Boolean = false,
    val autoReloadOnResume: Boolean = true,
    val cancelOnPause: Boolean = false,
    val size: BannerSize = BannerSize.LargePortraitAdaptive,
    val collapsibleGravity: BannerCollapseGravity? = null,
    /** Ad placement id for cold loads when the tag is not registered by a preload. */
    val placementId: Long? = null,
)
