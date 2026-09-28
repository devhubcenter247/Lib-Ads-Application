package com.lib.ads.gma.compose

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Stable
class ShimmerState internal constructor(private val animatedProgress: State<Float>) {
    val progress: Float get() = animatedProgress.value
}

@Composable
fun rememberShimmerState(durationMillis: Int = 1200): ShimmerState {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val anim = transition.animateFloat(
        initialValue = -1f, targetValue = 2f, animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "offset"
    )
    return remember { ShimmerState(anim) }
}

@get:RequiresApi(33)
private val SHIMMER_AGSL_SRC = """
    uniform float2 resolution;
    uniform float  progress;
    uniform float4 baseColor;
    uniform float4 highColor;
    uniform float  band;
    uniform float  angleDx;
    uniform float  angleDy;

    half4 main(float2 coord) {
        float totalTravel = resolution.x + band * 2.0;
        float startX      = -band + progress * totalTravel;
        float2 start      = float2(startX * angleDx, startX * angleDy);
        float2 dir        = float2(angleDx, angleDy);
        float t           = dot(coord - start, dir) / band;
        float factor      = 1.0 - abs(clamp(t, 0.0, 1.0) - 0.5) * 2.0;
        return half4(mix(baseColor, highColor, factor));
    }
""".trimIndent()

fun Modifier.shimmer(
    state: ShimmerState,
    visible: Boolean,
    alpha: Float = 1f,
    shape: Shape = RectangleShape,
    baseColor: Color = Color.LightGray.copy(alpha = 0f),
    highlightColor: Color = Color.LightGray.copy(alpha = 0.55f),
    angleDegrees: Float = 20f,
    bandWidthFraction: Float = 0.45f
): Modifier = this.then(
    if (!visible) Modifier
    else if (android.os.Build.VERSION.SDK_INT >= 33) {
        Modifier.shimmerAGSL(
            state,
            alpha,
            shape,
            baseColor,
            highlightColor,
            angleDegrees,
            bandWidthFraction
        )
    } else {
        Modifier.shimmerLegacy(
            state, alpha, shape, baseColor, highlightColor, angleDegrees, bandWidthFraction
        )
    }
)

@SuppressLint("ModifierFactoryUnreferencedReceiver")
@RequiresApi(33)
private fun Modifier.shimmerAGSL(
    state: ShimmerState,
    alpha: Float,
    shape: Shape,
    baseColor: Color,
    highlightColor: Color,
    angleDegrees: Float,
    bandWidthFraction: Float
): Modifier = Modifier.drawWithCache {
    if (size.minDimension <= 0f || alpha <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(angleRad).toFloat()
        val dy = sin(angleRad).toFloat()
        val band = bandWidthFraction.coerceIn(0.1f, 0.5f) * size.width
        val runtimeShader = RuntimeShader(SHIMMER_AGSL_SRC).apply {
            setFloatUniform("resolution", size.width, size.height)
            setFloatUniform("band", band)
            setFloatUniform("angleDx", dx)
            setFloatUniform("angleDy", dy)
            setFloatUniform(
                "baseColor", baseColor.red, baseColor.green, baseColor.blue, baseColor.alpha * alpha
            )
            setFloatUniform(
                "highColor",
                highlightColor.red,
                highlightColor.green,
                highlightColor.blue,
                highlightColor.alpha * alpha
            )
        }

        val paint = Paint().apply {
            isAntiAlias = true
            shader = runtimeShader
        }

        val cachedPath = shape.toCachedPath(size, layoutDirection)

        onDrawWithContent {
            drawContent()
            runtimeShader.setFloatUniform("progress", state.progress)
            drawIntoCanvas { canvas ->
                if (cachedPath != null) canvas.drawPath(cachedPath, paint)
                else canvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
}

@SuppressLint("ModifierFactoryUnreferencedReceiver")
private fun Modifier.shimmerLegacy(
    state: ShimmerState,
    alpha: Float,
    shape: Shape,
    baseColor: Color,
    highlightColor: Color,
    angleDegrees: Float,
    bandWidthFraction: Float
): Modifier = Modifier.drawWithCache {
    if (size.minDimension <= 0f || alpha <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(angleRad).toFloat()
        val dy = sin(angleRad).toFloat()
        val band = bandWidthFraction.coerceIn(0.1f, 0.5f) * size.width
        val totalTravel = size.width + band * 2f
        val originOffset = -band

        val baseA = baseColor.copy(alpha = baseColor.alpha * alpha)
        val hiA = highlightColor.copy(alpha = highlightColor.alpha * alpha)
        val colors = listOf(baseA, hiA, baseA)

        val paint = Paint().apply { isAntiAlias = true }
        val cachedPath = shape.toCachedPath(size, layoutDirection)

        onDrawWithContent {
            drawContent()
            val startX = originOffset + state.progress * totalTravel
            val start = Offset(startX * dx, startX * dy)
            val end = Offset(start.x + band * dx, start.y + band * dy)
            paint.shader = LinearGradientShader(
                from = start, to = end, colors = colors, tileMode = TileMode.Clamp
            )
            drawIntoCanvas { canvas ->
                if (cachedPath != null) canvas.drawPath(cachedPath, paint)
                else canvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
}

private fun Shape.toCachedPath(
    size: Size, layoutDirection: LayoutDirection
): Path? {
    return when (val outline =
        createOutline(size, layoutDirection, androidx.compose.ui.unit.Density(1f))) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> null
    }
}
@Composable
private inline fun Modifier.drawEdgeLine(
    width: Dp,
    color: Color?,
    brush: Brush?,
    crossinline computeStartEnd: DrawScope.(strokePx: Float) -> Pair<Offset, Offset>
): Modifier = drawWithContent {
    drawContent()
    val strokePx = width.toPx()
    if (strokePx <= 0f) return@drawWithContent
    val (start, end) = computeStartEnd(strokePx)
    when {
        brush != null -> drawLine(
            brush = brush,
            start = start,
            end = end,
            strokeWidth = strokePx
        )
        color != null && color != Color.Unspecified -> drawLine(
            color = color,
            start = start,
            end = end,
            strokeWidth = strokePx
        )
    }
}
@Composable
fun Modifier.borderTop(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = composed {
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val y = strokePx / 2f
            Offset(0f, y) to Offset(size.width, y)
        }
    )
}
@Composable
fun Modifier.borderBottom(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = composed {
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val y = size.height - strokePx / 2f
            Offset(0f, y) to Offset(size.width, y)
        }
    )
}
@Composable
fun Modifier.borderStart(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = composed {
    val layoutDir = LocalLayoutDirection.current
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val x =
                if (layoutDir == LayoutDirection.Ltr) strokePx / 2f else size.width - strokePx / 2f
            Offset(x, 0f) to Offset(x, size.height)
        }
    )
}
@Composable
fun Modifier.borderEnd(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = composed {
    val layoutDir = LocalLayoutDirection.current
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val x =
                if (layoutDir == LayoutDirection.Ltr) size.width - strokePx / 2f else strokePx / 2f
            Offset(x, 0f) to Offset(x, size.height)
        }
    )
}
@Composable
fun Modifier.borderHorizontal(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = this
    .borderTop(width, color, brush)
    .borderBottom(width, color, brush)
@Composable
fun Modifier.borderVertical(
    width: Dp = 1.dp,
    color: Color? = Color.Black,
    brush: Brush? = null
): Modifier = this
    .borderStart(width, color, brush)
    .borderEnd(width, color, brush)
