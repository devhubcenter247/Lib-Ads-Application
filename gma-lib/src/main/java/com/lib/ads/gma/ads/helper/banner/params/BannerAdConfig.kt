package com.lib.ads.gma.ads.helper.banner.params

import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity

open class BannerAdConfig(
    var listId: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean,
    override val placementId: Long? = null,
) : IAdsConfig {
    constructor(idAds: String, canShowAds: Boolean, canReloadAds: Boolean) : this(listOf(idAds), canShowAds, canReloadAds)

    override val idAds: String
        get() = listId.lastOrNull().orEmpty()

    var collapsibleGravity: String? = null
    var size: BannerSize = BannerSize.LargePortraitAdaptive

    /**
     * Placement tag in [com.lib.ads.gma.ads.helper.banner.BannerAds]. Null derives one from the ids,
     * size, collapsible gravity and placement id, so different configs never share a buffer.
     */
    var tag: String? = null

    fun setTag(tag: String?): BannerAdConfig = apply { this.tag = tag }

    internal fun resolvedTag(): String =
        tag ?: "banner:${listId.joinToString(",")}:$size:${collapsibleGravity.orEmpty()}:${placementId ?: ""}"

    internal fun toHolderOptions(autoReloadOnResume: Boolean) = BannerAdPreloadHolderOptions(
        fallbackAdUnitIds = listId,
        autoRequestOnStart = true,
        autoReloadOnResume = autoReloadOnResume && canReloadAds,
        size = size,
        collapsibleGravity = BannerCollapseGravity.entries.firstOrNull { it.value == collapsibleGravity },
        placementId = placementId,
    )

    fun setCollapsibleGravity(gravity: String?): BannerAdConfig = apply {
        collapsibleGravity = gravity
    }

    fun setListId(list: List<String>): BannerAdConfig {
        listId = list
        return this
    }

    fun setSize(size: BannerSize): BannerAdConfig = apply {
        this.size = size
    }

    fun asInlineBanner(maxHeightDp: Int = 50): BannerAdConfig = apply {
        size = BannerSize.InlineAdaptive(maxHeightDp)
    }

    fun asFixedSize(widthDp: Int, heightDp: Int): BannerAdConfig = apply {
        size = BannerSize.Fixed(widthDp, heightDp)
    }

    fun asWidth(widthDp: Int): BannerAdConfig = apply {
        size = BannerSize.Width(widthDp)
    }

    fun asHeight(heightDp: Int): BannerAdConfig = apply {
        size = BannerSize.Height(heightDp)
    }
}