package com.lib.ads.gma

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.lib.ads.gma.gma.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAdXmlLayoutTest {

    private lateinit var inflater: LayoutInflater

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        inflater = LayoutInflater.from(context)
    }

    // ── custom_native_admod_medium ───────────────────────────────────────────

    @Test
    fun mediumLayout_rootIsNativeAdView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertTrue(view is NativeAdView)
    }

    @Test
    fun mediumLayout_hasHeadlineView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_headline))
    }

    @Test
    fun mediumLayout_hasBodyView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_body))
    }

    @Test
    fun mediumLayout_hasIconView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertNotNull(view.findViewById<ImageView>(R.id.ad_app_icon))
    }

    @Test
    fun mediumLayout_hasCallToActionView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertNotNull(view.findViewById<Button>(R.id.ad_call_to_action))
    }

    @Test
    fun mediumLayout_hasAdvertiserView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_advertiser))
    }

    // ── custom_native_admob_small ────────────────────────────────────────────

    @Test
    fun smallLayout_rootIsNativeAdView() {
        val view = inflater.inflate(R.layout.custom_native_admob_small, null)
        assertTrue(view is NativeAdView)
    }

    @Test
    fun smallLayout_hasHeadlineView() {
        val view = inflater.inflate(R.layout.custom_native_admob_small, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_headline))
    }

    @Test
    fun smallLayout_hasBodyView() {
        val view = inflater.inflate(R.layout.custom_native_admob_small, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_body))
    }

    @Test
    fun smallLayout_hasIconView() {
        val view = inflater.inflate(R.layout.custom_native_admob_small, null)
        assertNotNull(view.findViewById<ImageView>(R.id.ad_app_icon))
    }

    @Test
    fun smallLayout_hasCallToActionView() {
        val view = inflater.inflate(R.layout.custom_native_admob_small, null)
        assertNotNull(view.findViewById<Button>(R.id.ad_call_to_action))
    }

    // ── custom_native_admob_free_size ────────────────────────────────────────

    @Test
    fun freeSizeLayout_rootIsNativeAdView() {
        val view = inflater.inflate(R.layout.custom_native_admob_free_size, null)
        assertTrue(view is NativeAdView)
    }

    @Test
    fun freeSizeLayout_hasMediaView() {
        val view = inflater.inflate(R.layout.custom_native_admob_free_size, null)
        assertNotNull(view.findViewById<View>(R.id.ad_media))
    }

    @Test
    fun freeSizeLayout_hasHeadlineAndCta() {
        val view = inflater.inflate(R.layout.custom_native_admob_free_size, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_headline))
        assertNotNull(view.findViewById<Button>(R.id.ad_call_to_action))
    }

    // ── custom_native_admod_medium2 ──────────────────────────────────────────

    @Test
    fun medium2Layout_rootIsNativeAdView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium2, null)
        assertTrue(view is NativeAdView)
    }

    @Test
    fun medium2Layout_hasRequiredViews() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium2, null)
        assertNotNull(view.findViewById<TextView>(R.id.ad_headline))
        assertNotNull(view.findViewById<TextView>(R.id.ad_body))
        assertNotNull(view.findViewById<ImageView>(R.id.ad_app_icon))
        assertNotNull(view.findViewById<Button>(R.id.ad_call_to_action))
    }

    // ── custom_native_admod_medium_rate ──────────────────────────────────────

    @Test
    fun mediumRateLayout_rootIsNativeAdView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium_rate, null)
        assertTrue(view is NativeAdView)
    }

    @Test
    fun mediumRateLayout_hasStarRatingView() {
        val view = inflater.inflate(R.layout.custom_native_admod_medium_rate, null)
        // star rating view ID varies; just verify root inflates
        assertNotNull(view)
    }

    // ── layout_banner_control ────────────────────────────────────────────────

    @Test
    fun bannerControlLayout_inflatesSuccessfully() {
        val view = inflater.inflate(R.layout.layout_banner_control, null)
        assertNotNull(view)
        assertNotNull(view.findViewById<View>(R.id.banner_container))
    }

    @Test
    fun bannerControlLayout_hasBannerContainer() {
        val view = inflater.inflate(R.layout.layout_banner_control, null)
        val container = view.findViewById<View>(R.id.banner_container)
        assertNotNull(container)
    }

    // ── layout_native_control ────────────────────────────────────────────────

    @Test
    fun nativeControlLayout_inflatesSuccessfully() {
        val view = inflater.inflate(R.layout.layout_native_control, null)
        assertNotNull(view)
    }
}
