package com.lib.ads.gma.app.gma

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.createNativeAdHolder
import com.lib.ads.gma.ads.helper.adnative.preload.observeAd
import com.lib.ads.gma.ads.helper.banner.bindToContainer
import com.lib.ads.gma.ads.helper.banner.createBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.observeAdView
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdDisplayState
import com.lib.ads.gma.ads.helper.banner.reservedHeightPx
import com.lib.ads.gma.ads.helper.nativebanner.NativeBannerConfig
import com.lib.ads.gma.ads.helper.nativebanner.NativeBannerHelper
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdDisplayState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * View-based (XML) demo of the gma-lib APIs for apps that build their own ad UI:
 * native via `observeAd` + `bindTo`, native with the default layout (layoutId = 0),
 * and banners whose container keeps the same height while loading and after the fill.
 * Each status line lists the container heights seen so far, so a layout jump shows up as
 * two different numbers.
 */
class XmlAdsActivity : AppCompatActivity() {

    private val customNative by createNativeAdHolder(
        enabled = BuildConfig.ad_native_high.isNotBlank(),
        tag = "xml_native_custom",
        options = NativeAdPreloadHolderOptions(
            fallbackAdUnitIds = listOf(BuildConfig.ad_native_high),
            lifecycleOwner = this,
            autoRequestOnStart = true,
            autoReloadOnResume = false,
        ),
    )

    private val libBanner by createBannerAdHolder(
        tag = "xml_banner_lib",
        enabled = BuildConfig.ad_banner.isNotBlank(),
        options = BannerAdPreloadHolderOptions(
            fallbackAdUnitIds = listOf(BuildConfig.ad_banner),
            lifecycleOwner = this,
            autoRequestOnStart = true,
            size = BannerSize.LargePortraitAdaptive,
        ),
    )

    private val customBanner by createBannerAdHolder(
        tag = "xml_banner_custom",
        enabled = BuildConfig.ad_banner.isNotBlank(),
        options = BannerAdPreloadHolderOptions(
            fallbackAdUnitIds = listOf(BuildConfig.ad_banner),
            lifecycleOwner = this,
            autoRequestOnStart = true,
            size = BannerSize.LargePortraitAdaptive,
        ),
    )

    private var defaultNative: NativeBannerHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_ads)
        setUpCustomNative()
        setUpDefaultLayoutNative()
        setUpLibBanner()
        setUpCustomBanner()
    }

    /** 1. The app inflates its own NativeAdView; the holder fills and tracks it. */
    private fun setUpCustomNative() {
        val frame = findViewById<FrameLayout>(R.id.native_custom_frame)
        val status = findViewById<TextView>(R.id.native_custom_status)
        status.text = "loading…"
        customNative.observeAd(this) { ad ->
            frame.removeAllViews()
            if (ad == null) {
                frame.isVisible = false
                status.text = "no ad: ${customNative.lastError.value ?: "cancelled"}"
                return@observeAd
            }
            val adView = layoutInflater.inflate(R.layout.item_xml_native_custom, frame, false) as NativeAdView
            val bound = customNative.bindTo(adView)
            frame.addView(adView)
            frame.isVisible = true
            status.text = "loaded · bindTo=$bound · headline=\"${ad.nativeAd?.headline}\""
        }
    }

    /** 2. layoutId = 0 used to crash on inflate(0); it now falls back to the library layout. */
    private fun setUpDefaultLayoutNative() {
        val frame = findViewById<FrameLayout>(R.id.native_default_frame)
        val status = findViewById<TextView>(R.id.native_default_status)
        status.text = "loading…"
        val helper = NativeBannerHelper(
            context = this,
            lifecycleOwner = this,
            config = NativeBannerConfig(
                listId = listOf(BuildConfig.ad_native),
                canShowAds = BuildConfig.ad_native.isNotBlank(),
                canReloadAds = false,
                layoutId = 0,
                tag = "xml_native_default",
            ),
        ).setContainer(frame)
        defaultNative = helper
        lifecycleScope.launch {
            helper.getAdState().collect { state ->
                status.text = when (state) {
                    is NativeAdDisplayState.Success -> "loaded with default layout · children=${frame.childCount}"
                    is NativeAdDisplayState.Error -> "failed"
                    else -> state::class.simpleName.orEmpty()
                }
            }
        }
        helper.requestAds()
    }

    /** 4. Bottom banner through bindToContainer, which reserves the height up front. */
    private fun setUpLibBanner() {
        val container = findViewById<FrameLayout>(R.id.banner_lib_container)
        val status = findViewById<TextView>(R.id.banner_lib_status)
        libBanner.bindToContainer(this, container)
        trackHeights(container, status, "bindToContainer reserved=${container.minimumHeight}px")
        lifecycleScope.launch {
            libBanner.state.collect { if (it is BannerAdDisplayState.Error) status.text = "banner failed: ${libBanner.lastError.value}" }
        }
    }

    /** 3. The app owns the banner container; it reserves reservedHeightPx before the ad arrives. */
    private fun setUpCustomBanner() {
        val frame = findViewById<FrameLayout>(R.id.banner_custom_frame)
        val status = findViewById<TextView>(R.id.banner_custom_status)
        frame.minimumHeight = customBanner.reservedHeightPx(this)
        trackHeights(frame, status, "reservedHeightPx=${frame.minimumHeight}px")
        customBanner.observeAdView(this) { adView ->
            frame.removeAllViews()
            if (adView == null) {
                frame.isVisible = false
                status.text = "no banner: ${customBanner.lastError.value ?: "cancelled"}"
                return@observeAdView
            }
            (adView.parent as? ViewGroup)?.removeView(adView)
            frame.addView(
                adView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                ),
            )
            frame.isVisible = true
        }
    }

    /** Appends every distinct laid-out height of [view] to [status]: "a → a" means no jump. */
    private fun trackHeights(view: View, status: TextView, prefix: String) {
        val heights = mutableListOf<Int>()
        status.text = prefix
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > 0 && heights.lastOrNull() != height) {
                heights += height
                status.text = "$prefix · heights=${heights.joinToString(" → ")}px"
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, XmlAdsActivity::class.java))
        }
    }
}
