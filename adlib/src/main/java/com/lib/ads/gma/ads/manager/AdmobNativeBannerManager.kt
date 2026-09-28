package com.lib.ads.gma.ads.manager

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.lib.adlib.R

object AdmobNativeBannerManager {
    private const val TAG = "AdmobNativeBannerManager"

    /**
     * Inflates [layoutId] into [container] and populates it with [nativeAd] assets.
     *
     * The layout's root must be a [NativeAdView]. Required view IDs:
     *   - `R.id.ad_app_icon`   (ImageView)  — app icon
     *   - `R.id.ad_headline`   (TextView)   — headline
     *   - `R.id.ad_body`       (TextView)   — body text (optional, hidden when empty)
     *   - `R.id.ad_call_to_action` (Button/TextView) — CTA (optional, hidden when empty)
     */
    fun inflate(
        context: Context,
        nativeAd: NativeAd,
        container: FrameLayout,
        layoutId: Int = R.layout.layout_admob_native_banner_default,
    ) {
        val adView = LayoutInflater.from(context).inflate(layoutId, container, false) as NativeAdView

        val iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
        val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
        val bodyView = adView.findViewById<TextView>(R.id.ad_body)
        val ctaView = adView.findViewById<Button>(R.id.ad_call_to_action)

        adView.iconView = iconView
        adView.headlineView = headlineView
        adView.bodyView = bodyView
        adView.callToActionView = ctaView

        // Icon
        if (nativeAd.icon == null) {
            iconView?.visibility = View.GONE
        } else {
            iconView?.setImageDrawable(nativeAd.icon?.drawable)
            iconView?.visibility = View.VISIBLE
        }

        // Headline
        headlineView?.text = nativeAd.headline

        // Body
        if (nativeAd.body.isNullOrEmpty()) {
            bodyView?.visibility = View.GONE
        } else {
            bodyView?.visibility = View.VISIBLE
            bodyView?.text = nativeAd.body
        }

        // CTA
        if (nativeAd.callToAction.isNullOrEmpty()) {
            ctaView?.visibility = View.INVISIBLE
        } else {
            ctaView?.visibility = View.VISIBLE
            ctaView?.text = nativeAd.callToAction
        }

        adView.setNativeAd(nativeAd)

        container.removeAllViews()
        container.addView(adView)
        Log.d(TAG, "inflate: displayed successfully")
    }
}
