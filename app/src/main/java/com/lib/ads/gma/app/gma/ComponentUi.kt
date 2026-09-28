package com.lib.ads.gma.app.gma

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.lib.ads.gma.R
import com.lib.ads.gma.app.style.black
import com.lib.ads.gma.app.style.grdEnd
import com.lib.ads.gma.app.style.grdStart


enum class GradientOrientation {
    Horizontal,
    Vertical,
    TL_BR,
    TR_BL,
    BL_TR,
    BR_TL,
    RadialCenter,
    SweepCenter,
}

private val INF = Float.POSITIVE_INFINITY
fun gradientBrush(
    orientation: GradientOrientation = GradientOrientation.Horizontal,
    colors: List<Color> = listOf(grdStart, grdEnd),
    isRevertColor: Boolean = false,
): Brush {
    val c = if (isRevertColor) colors.reversed() else colors
    return when (orientation) {
        GradientOrientation.Horizontal -> Brush.horizontalGradient(c)
        GradientOrientation.Vertical -> Brush.verticalGradient(c)
        GradientOrientation.TL_BR -> Brush.linearGradient(
            c,
            start = Offset(0f, 0f),
            end = Offset(INF, INF)
        )

        GradientOrientation.TR_BL -> Brush.linearGradient(
            c,
            start = Offset(INF, 0f),
            end = Offset(0f, INF)
        )

        GradientOrientation.BL_TR -> Brush.linearGradient(
            c,
            start = Offset(0f, INF),
            end = Offset(INF, 0f)
        )

        GradientOrientation.BR_TL -> Brush.linearGradient(
            c,
            start = Offset(INF, INF),
            end = Offset(0f, 0f)
        )

        GradientOrientation.RadialCenter -> Brush.radialGradient(c)
        GradientOrientation.SweepCenter -> Brush.sweepGradient(c)
    }
}

@Stable
fun headline400(color: Color = black, fontSize: TextUnit = 16.sp) = TextStyle(
    fontFamily = FontFamily(Font(R.font.rb_400)),
    fontSize = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    color = color
)

@Stable
fun headline500(color: Color = black, fontSize: TextUnit = 16.sp) = TextStyle(
    fontFamily = FontFamily(Font(R.font.rb_500)),
    fontSize = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    color = color
)

@Stable
fun headline600(color: Color = black, fontSize: TextUnit = 16.sp) = TextStyle(
    fontFamily = FontFamily(Font(R.font.rb_600)),
    fontSize = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    color = color
)

@Stable
fun headline700(color: Color = black, fontSize: TextUnit = 16.sp) = TextStyle(
    fontFamily = FontFamily(Font(R.font.rb_700)),
    fontSize = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    color = color
)

@Stable
fun headline800(color: Color = black, fontSize: TextUnit = 16.sp) = TextStyle(
    fontFamily = FontFamily(Font(R.font.rb_800)),
    fontSize = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    color = color
)
