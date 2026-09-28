package com.lib.ads.gma.app.gma.language

import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds

class Language1Activity : LanguageActivity() {
    override val languageScreenType: LanguageScreenType
        get() = LanguageScreenType.Language1

    override fun initialize() {
        super.initialize()
        NativeAds.safePreload(
            NativeAdSpec(
                tag = "ob",
                adUnitIds = listOf(BuildConfig.ad_native_high),
                bufferSize = 4,
            )
        )
    }
}
