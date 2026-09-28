package com.lib.ads.gma.ads.helper.adnative.api

class NativeAdTagConfig(
    val tag: String,
    var listId: List<String>,
    val canShowAds: Boolean = true,
    val canReloadAds: Boolean = true,
) {
    val idAds: String get() = listId.lastOrNull().orEmpty()

    fun setListId(list: List<String>) = apply { this.listId = list }

    fun getAllAdUnitIds(): List<String> = listId.filter { it.isNotBlank() }

    companion object {
        fun simple(tag: String, adUnitId: String, canShowAds: Boolean = true) =
            NativeAdTagConfig(tag = tag, listId = listOf(adUnitId), canShowAds = canShowAds)

        fun waterfall(tag: String, adUnitIds: List<String>, canShowAds: Boolean = true) =
            NativeAdTagConfig(
                tag = tag,
                listId = adUnitIds,
                canShowAds = canShowAds
            )
    }
}
