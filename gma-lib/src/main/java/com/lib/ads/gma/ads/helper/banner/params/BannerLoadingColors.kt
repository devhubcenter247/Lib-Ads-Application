package com.lib.ads.gma.ads.helper.banner.params

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colors of the banner loading shimmer (`DefaultBannerAdLoading`).
 *
 * @param background fill behind the placeholder blocks.
 * @param placeholder the icon/text/button blocks.
 * @param highlight the moving shimmer band.
 * @param divider the 1dp top line; use [Color.Transparent] to hide it (the 1dp stays so the height
 * still matches the loaded banner).
 */
@Immutable
data class BannerLoadingColors(
    val background: Color = Color.White,
    val placeholder: Color = Color(0xFFE0E0E0),
    val highlight: Color = Color.LightGray.copy(alpha = 0.55f),
    val divider: Color = DefaultDivider,
) {
    companion object {
        /** Divider color shared with the loaded banner. */
        val DefaultDivider = Color(0xFFE1E1E1)
    }
}
