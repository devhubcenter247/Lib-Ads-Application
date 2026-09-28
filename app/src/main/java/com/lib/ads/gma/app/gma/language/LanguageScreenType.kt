package com.lib.ads.gma.app.gma.language

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class LanguageScreenType : Parcelable {
    @Parcelize
    data object Language1 : LanguageScreenType()
    @Parcelize
    data object Language2 : LanguageScreenType()
    @Parcelize
    data object LanguageLoading : LanguageScreenType()
}