package com.lib.ads.gma.app.gma.feature

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import com.lib.ads.gma.R

@Stable
data class FeatureModel(
    var index: Int = 0,
    @DrawableRes var image: Int = 0,
    @StringRes var content: Int = 0,
    var isSelect: Boolean = false
)

val listFeatureModel
    get() = listOf(
        FeatureModel(0, 0, R.string.feature_content_1),
        FeatureModel(1, 0, R.string.feature_content_2),
        FeatureModel(2, 0, R.string.feature_content_3),
        FeatureModel(3, 0, R.string.feature_content_4),
        FeatureModel(4, 0, R.string.feature_content_5),
    )
