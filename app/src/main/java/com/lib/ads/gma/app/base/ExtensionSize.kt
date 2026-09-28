package com.lib.ads.gma.app.style

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
@SuppressLint("ConfigurationScreenWidthHeight")
@Stable
fun getScreenWidthInDp(): Dp {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp.dp
}

@Composable
@SuppressLint("ConfigurationScreenWidthHeight")
@Stable
fun getScreenHeightInDp(): Dp {
    val configuration = LocalConfiguration.current
    return configuration.screenHeightDp.dp
}

@Stable
fun getScreenSizeInInches(context: Context): Double {
    val displayMetrics = context.resources.displayMetrics
    val widthInPixels = displayMetrics.widthPixels
    val heightInPixels = displayMetrics.heightPixels
    val widthInDp = widthInPixels / displayMetrics.density
    val heightInDp = heightInPixels / displayMetrics.density
    val screenWidthInInches = widthInDp / displayMetrics.densityDpi
    val screenHeightInInches = heightInDp / displayMetrics.densityDpi

    return sqrt(
        (screenWidthInInches * screenWidthInInches) +
                (screenHeightInInches * screenHeightInInches).toDouble()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.systemBarPadding(
    insets: WindowInsets,
    side: WindowInsetsSides,
    previewPadding: Modifier.() -> Modifier,
): Modifier =
    if (LocalInspectionMode.current) previewPadding()
    else windowInsetsPadding(insets.only(side))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.statusBarPadding(): Modifier =
    systemBarPadding(
        insets = WindowInsets.statusBarsIgnoringVisibility,
        side = WindowInsetsSides.Top,
        previewPadding = { paddingTop(32.dp) },
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.navigationBarPadding(): Modifier =
    systemBarPadding(
        insets = WindowInsets.navigationBarsIgnoringVisibility,
        side = WindowInsetsSides.Bottom,
        previewPadding = { paddingBottom(32.dp) },
    )

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.widthPercent(percent: Float): Modifier = composed {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    this.then(Modifier.width(screenWidth * (percent / 100f)))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.heightPercent(percent: Float): Modifier = composed {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    this.then(Modifier.height(screenHeight * (percent / 100f)))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.paddingHorizontal(values: Dp): Modifier = composed {
    this.then(Modifier.padding(horizontal = values))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.paddingVertical(values: Dp): Modifier = composed {
    this.then(Modifier.padding(vertical = values))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.paddingTop(values: Dp): Modifier = composed {
    this.then(Modifier.padding(top = values))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.paddingBottom(values: Dp): Modifier = composed {
    this.then(Modifier.padding(bottom = values))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight")
fun Modifier.paddingStart(values: Dp): Modifier = composed {
    this.then(Modifier.padding(start = values))
}

@Stable
@SuppressLint("ConfigurationScreenWidthHeight", "UnnecessaryComposedModifier")
fun Modifier.paddingEnd(values: Dp): Modifier = composed {
    this.then(Modifier.padding(end = values))
}

@Stable
fun Context.getScreenWidthInDp(): Dp {
    return resources.configuration.screenWidthDp.dp
}

@Stable
fun Context.getScreenWidthInPx(): Int {
    return resources.displayMetrics.widthPixels
}

@Stable
fun Context.getScreenHeightInDp(): Dp {
    return resources.configuration.screenHeightDp.dp
}

@Stable
fun Context.getScreenHeightInPx(): Int {
    return resources.displayMetrics.heightPixels
}

@Stable
fun Dp.toPx(density: Density): Float = with(density) { this@toPx.toPx() }



