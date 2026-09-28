package com.lib.ads.gma.app.gma.feature

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class FeatureScreenType : Parcelable {

    data object Feature1 : FeatureScreenType()
    data object Feature2 : FeatureScreenType()
}
