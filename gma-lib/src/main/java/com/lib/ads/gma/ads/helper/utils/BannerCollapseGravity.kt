package com.lib.ads.gma.ads.helper.utils

enum class BannerCollapseGravity(val value: String?) {
    NONE(null), TOP("top"), BOTTOM("bottom");
    companion object {
        val Top: BannerCollapseGravity = TOP
        val Bottom: BannerCollapseGravity = BOTTOM
    }
}