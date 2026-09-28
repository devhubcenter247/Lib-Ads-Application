package com.lib.ads.gma.app.gma.language

import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds

class LanguageLoadingActivity : LanguageActivity(){
    override val languageScreenType: LanguageScreenType
        get() = LanguageScreenType.LanguageLoading

    override fun initialize() {
        super.initialize()
        NativeAds.safePreload(
            spec = NativeAdSpec(
                tag = "lang2",
                adUnitIds = listOf(BuildConfig.ad_native_high),
            )
        )
    }
}
