package com.lib.ads.gma.ads.helper.banner.params

sealed class BannerPreloadState {
    data object Idle : BannerPreloadState()
    data object Loading : BannerPreloadState()
    data class ItemLoaded(val loaded: Int, val requested: Int) : BannerPreloadState()
    data class Ready(val available: Int) : BannerPreloadState()
    data object Empty : BannerPreloadState()
    data object Cancelled : BannerPreloadState()
    data object Stopped : BannerPreloadState()
}