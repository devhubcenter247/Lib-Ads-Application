package com.lib.ads.gma.ads.helper.adnative.params

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lib.ads.gma.compose.ShimmerState
import com.lib.ads.gma.compose.shimmer

/**
 * Colors of the native loading shimmer: the `Shimmer*` placeholder blocks
 * (`ShimmerIconCircle`, `ShimmerHeadlineRow`, `ShimmerBodyBox`, `ShimmerCta*Button`...) and the
 * container they sit in ([nativeLoadingShimmer]).
 *
 * @param background fill behind the placeholder blocks.
 * @param placeholder the icon/text/button blocks.
 * @param highlight the moving shimmer band.
 */
@Immutable
data class NativeLoadingColors(
    val background: Color = Color.Transparent,
    val placeholder: Color = Color(0xFFF0F0F0),
    val highlight: Color = Color.LightGray.copy(alpha = 0.55f),
)

/**
 * Default colors for the `Shimmer*` blocks below it. Provide it once around a shimmer layout
 * instead of passing colors to every block:
 * `CompositionLocalProvider(LocalNativeLoadingColors provides colors) { ... }`.
 */
val LocalNativeLoadingColors = compositionLocalOf { NativeLoadingColors() }

/** Container background + moving shimmer band for a native loading layout, from [colors]. */
fun Modifier.nativeLoadingShimmer(
    state: ShimmerState,
    visible: Boolean,
    colors: NativeLoadingColors = NativeLoadingColors(),
): Modifier = this
    .background(colors.background)
    .shimmer(state = state, visible = visible, highlightColor = colors.highlight)
