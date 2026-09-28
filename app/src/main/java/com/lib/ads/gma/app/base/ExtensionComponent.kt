package com.lib.ads.gma.app.style

import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Stable
fun Modifier.onClick(enable: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enable,
        onClick = onClick
    )
}
@Stable
fun Modifier.onClickRipple(
    shape: Shape = RoundedCornerShape(16.dp),
    enabled: Boolean = true,
    bounded: Boolean = true,
    onClick: () -> Unit
): Modifier = this
    .clip(shape)
    .clickable(
        interactionSource = null,
        indication = ripple(bounded = bounded),
        enabled = enabled,
        onClick = onClick
    )

@OptIn(ExperimentalFoundationApi::class)
@Stable
fun Modifier.onLongClick(onLongClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this.combinedClickable(
        onClick = {}, onLongClick = {
            onLongClick()
        }, indication = null, interactionSource = interactionSource
    )
}

enum class GradientType {
    Linear, Horizontal, Vertical, Radial, Sweep
}

@Stable
fun Modifier.backgroundResource(
    @DrawableRes id: Int,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f
): Modifier = composed {
    val painter = painterResource(id)
    val layoutDirection = LocalLayoutDirection.current
    this.drawWithContent {
        val intrinsicSize = painter.intrinsicSize
        if (size.width > 0f && size.height > 0f &&
            intrinsicSize.isSpecified && intrinsicSize.width > 0f && intrinsicSize.height > 0f
        ) {
            val scaleFactor = contentScale.computeScaleFactor(
                srcSize = intrinsicSize,
                dstSize = size
            )
            val scaledSize = Size(
                intrinsicSize.width * scaleFactor.scaleX,
                intrinsicSize.height * scaleFactor.scaleY
            )
            val offset = alignment.align(
                IntSize(scaledSize.width.roundToInt(), scaledSize.height.roundToInt()),
                IntSize(size.width.roundToInt(), size.height.roundToInt()),
                layoutDirection
            )
            clipRect {
                translate(offset.x.toFloat(), offset.y.toFloat()) {
                    with(painter) { draw(size = scaledSize, alpha = alpha) }
                }
            }
        }
        drawContent()
    }
}

@Composable
fun Modifier.dashedBorder(
    strokeWidth: Dp,
    color: Color,
    cornerRadius: Dp = 0.dp,
    dashLength: Float = 10f,
    gapLength: Float = 10f
): Modifier = this.then(
    Modifier.drawBehind {
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
        )
        val width = size.width
        val height = size.height
        val corner = cornerRadius.toPx()

        drawRoundRect(
            color = color,
            size = Size(width, height),
            style = stroke,
            cornerRadius = CornerRadius(corner, corner)
        )
    })

@Stable
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
            brush = brush, start = start, end = end, strokeWidth = strokePx
        )

        color != null && color != Color.Unspecified -> drawLine(
            color = color, start = start, end = end, strokeWidth = strokePx
        )
    }
}

@Stable
fun Modifier.borderTop(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier = this.then(
    Modifier.drawEdgeLine(width, color, brush) { strokePx ->
        val y = strokePx / 2f
        Offset(0f, y) to Offset(size.width, y)
    })


@Stable
fun Modifier.borderBottom(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier =   this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val y = size.height - strokePx / 2f
            Offset(0f, y) to Offset(size.width, y)
        })

@SuppressLint("UnnecessaryComposedModifier")
@Stable
fun Modifier.borderStart(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier = composed {
    val layoutDir = LocalLayoutDirection.current
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val x =
                if (layoutDir == LayoutDirection.Ltr) strokePx / 2f else size.width - strokePx / 2f
            Offset(x, 0f) to Offset(x, size.height)
        })
}

@Composable
fun Modifier.borderEnd(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier = composed {
    val layoutDir = LocalLayoutDirection.current
    this.then(
        Modifier.drawEdgeLine(width, color, brush) { strokePx ->
            val x =
                if (layoutDir == LayoutDirection.Ltr) size.width - strokePx / 2f else strokePx / 2f
            Offset(x, 0f) to Offset(x, size.height)
        })
}

@Composable
fun Modifier.borderHorizontal(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier = this
    .borderTop(width, color, brush)
    .borderBottom(width, color, brush)

@Composable
fun Modifier.borderVertical(
    width: Dp = 1.5.dp, color: Color? = Color.Black, brush: Brush? = null
): Modifier = this
    .borderStart(width, color, brush)
    .borderEnd(width, color, brush)