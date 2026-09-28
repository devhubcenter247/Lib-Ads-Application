package com.lib.ads.gma.ads.helper.adnative.provider

import com.google.android.gms.ads.VideoController
import com.lib.ads.gma.ads.ads.AdsCallback
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-ad event relay that travels with each ad from load time through
 * the buffer to the consumer. Fires alongside (not instead of) the
 * NativeAds slot callbacks.
 */
class NativeAdEventRelay {

    private val listeners = CopyOnWriteArrayList<AdsCallback>()

    fun addListener(callback: AdsCallback) {
        listeners.addIfAbsent(callback)
    }

    fun removeListener(callback: AdsCallback) {
        listeners.remove(callback)
    }

    fun clearListeners() {
        listeners.clear()
    }

    internal fun onAdImpression() {
        listeners.forEach { it.onAdImpression() }
    }

    internal fun onAdClicked() {
        listeners.forEach { it.onAdClicked() }
    }

    internal fun onAdClosed() {
        listeners.forEach { it.onAdClosed() }
    }

    fun buildVideoLifecycleCallbacks(): VideoController.VideoLifecycleCallbacks =
        object : VideoController.VideoLifecycleCallbacks() {
            override fun onVideoStart() { listeners.forEach { it.onNativeVideoStart() } }
            override fun onVideoPlay() { listeners.forEach { it.onNativeVideoPlay() } }
            override fun onVideoPause() { listeners.forEach { it.onNativeVideoPause() } }
            override fun onVideoEnd() { listeners.forEach { it.onNativeVideoEnd() } }
            override fun onVideoMute(isMuted: Boolean) { listeners.forEach { it.onNativeVideoMute(isMuted) } }
        }
}
