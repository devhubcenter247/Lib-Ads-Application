package com.lib.ads.gma.ads.helper

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.lib.ads.gma.ads.manager.NativeAdManager
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import androidx.core.view.contains

/** View-only operations shared by stateful ad helpers. */
object AdViewRenderer {
    fun native(activity: android.app.Activity, ad: ApNativeAd, content: FrameLayout, shimmer: ShimmerFrameLayout?, listener: NativeAdListener? = null) {
        NativeAdManager.populateNativeAdView(activity, ad, content, shimmer, listener)
    }

    fun banner(content: FrameLayout, adView: AdView, collapsibleGravity: String?, dividerHeight: Int) {
        val container = content as ViewGroup
        if (container.contains(adView)) return
        content.setBackgroundColor(0)
        if (!collapsibleGravity.isNullOrEmpty()) {
            (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<AdView>().firstOrNull()?.let { old -> old.destroy(); container.removeView(old) }
        }
        val oldHeight = adView.height
        val placeholder = View(content.context)
        val divider = View(content.context).apply { setBackgroundColor(-1973791) }
        content.removeAllViews()
        content.addView(placeholder, 0, oldHeight)
        (adView.parent as? ViewGroup)?.removeView(adView)
        content.addView(adView, -1, ViewGroup.LayoutParams.WRAP_CONTENT)
        val params = adView.layoutParams as FrameLayout.LayoutParams
        params.setMargins(0, dividerHeight, 0, 0)
        params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        adView.layoutParams = params
        content.addView(divider, -1, dividerHeight)
    }
}
