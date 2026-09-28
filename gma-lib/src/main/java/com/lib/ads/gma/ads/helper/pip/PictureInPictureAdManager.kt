package com.lib.ads.gma.ads.helper.pip

import android.app.Activity
import androidx.annotation.MainThread
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAd
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdOptions
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdPosition
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdPresentationScope
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdRequest

/**
 * App-level holder for a PiP ad. Use APPLICATION scope when the ad should survive
 * navigation between activities; call [destroy] when the ad is no longer needed.
 */
@OptIn(ExperimentalApi::class)
object PictureInPictureAdManager {
    private var ad: PictureInPictureAd? = null
    private var eventCallback: PictureInPictureAdEventCallback? = null
    private var currentPresentationScope: PictureInPictureAdPresentationScope? = null

    @MainThread
    fun load(
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailedToLoad: ((LoadAdError) -> Unit)? = null,
    ) {
        PictureInPictureAd.load(
            PictureInPictureAdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<PictureInPictureAd> {
                override fun onAdLoaded(loadedAd: PictureInPictureAd) {
                    ad = loadedAd
                    loadedAd.adEventCallback = eventCallback
                    onLoaded?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    onFailedToLoad?.invoke(error)
                }
            },
        )
    }

    @MainThread
    fun setAdEventCallback(callback: PictureInPictureAdEventCallback?) {
        eventCallback = callback
        ad?.adEventCallback = callback
    }

    @MainThread
    fun show(
        activity: Activity,
        position: PictureInPictureAdPosition = PictureInPictureAdPosition.DEFAULT,
        presentationScope: PictureInPictureAdPresentationScope =
            PictureInPictureAdPresentationScope.APPLICATION,
    ): Boolean {
        val loadedAd = ad ?: return false
        currentPresentationScope = presentationScope
        loadedAd.show(
            activity,
            PictureInPictureAdOptions.Builder()
                .setPosition(position)
                .setPresentationScope(presentationScope)
                .build(),
        )
        return true
    }

    @MainThread
    fun hide() {
        ad?.hide()
    }

    @MainThread
    fun destroy() {
        ad?.destroy()
        ad = null
        currentPresentationScope = null
        eventCallback = null
    }

    /** Scope used for the currently loaded/shown ad, if one has been shown. */
    @MainThread
    fun presentationScope(): PictureInPictureAdPresentationScope? = currentPresentationScope

    val isLoaded: Boolean
        @MainThread get() = ad != null
}
