package com.lib.ads.gma.ads.model.wrapper

abstract class ApAdBase(var status: StatusAd = StatusAd.AD_INIT) {
    abstract fun isReady(): Boolean
    fun isNotReady() = !isReady()
    fun isLoading() = status == StatusAd.AD_LOADING
    fun isLoadFail() = status == StatusAd.AD_LOAD_FAIL
}
