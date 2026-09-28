package com.lib.ads.gma.compose

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AdDebugInfo(
    val adType: String,
    val state: String,
    val adUnitId: String? = null,
    val fromPreload: Boolean? = null,
    val cacheCount: Int? = null,
    val loadTimeMs: Long? = null,
    val tag: String? = null,
    val requestCount: Int? = null,
    val inFlightRequests: Int? = null,
    /** Why the last load failed; one line per ad unit tried for a waterfall. */
    val error: String? = null,
)

private const val ERROR_COLOR = 0xFFFF6E6E.toInt()

private fun displayAdUnitId(id: String): String =
    id.removePrefix("ca-app-pub-")

@Composable
fun AdDebugOverlayCompose(info: AdDebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 6.dp))
            .background(androidx.compose.ui.graphics.Color(0xAA000000))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = "[${info.adType}] ${info.state}",
            color = stateColor(info.state),
            fontSize = 8.sp,
            lineHeight = 10.sp
        )
        if (info.adUnitId != null) {
            Text(
                text = "ID: ${displayAdUnitId(info.adUnitId)}",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
        if (info.tag != null) {
            val parts = mutableListOf<String>()
            parts.add("Tag: ${info.tag}")
            if (info.fromPreload != null) parts.add("Preload: ${info.fromPreload}")
            if (info.cacheCount != null) parts.add("Cache: ${info.cacheCount}")
            if (info.requestCount != null) {
                val inFlight = info.inFlightRequests ?: 0
                parts.add("Req: ${info.requestCount} (+$inFlight)")
            }
            Text(
                text = parts.joinToString(" | "),
                color = androidx.compose.ui.graphics.Color(0xFFBBBBBB),
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
        if (info.loadTimeMs != null) {
            Text(
                text = "Load: ${info.loadTimeMs}ms",
                color = androidx.compose.ui.graphics.Color(0xFFBBBBBB),
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
        if (!info.error.isNullOrBlank()) {
            Text(
                text = "Err: ${info.error}",
                color = androidx.compose.ui.graphics.Color(ERROR_COLOR),
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
    }
}

@Composable
private fun stateColor(state: String): androidx.compose.ui.graphics.Color = when {
    state.startsWith("Loaded") -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    state.startsWith("Loading") -> androidx.compose.ui.graphics.Color(0xFFFFC107)
    state.startsWith("Error") -> androidx.compose.ui.graphics.Color(0xFFF44336)
    state.startsWith("Cancelled") -> androidx.compose.ui.graphics.Color(0xFFFF9800)
    else -> androidx.compose.ui.graphics.Color.White
}

fun createDebugOverlayView(context: Context, info: AdDebugInfo): TextView {
    return TextView(context).apply {
        val lines = mutableListOf("[${info.adType}] ${info.state}")
        if (info.adUnitId != null) lines.add("ID: ${displayAdUnitId(info.adUnitId)}")
        if (info.tag != null) {
            val parts = mutableListOf("Tag: ${info.tag}")
            if (info.fromPreload != null) parts.add("Preload: ${info.fromPreload}")
            if (info.cacheCount != null) parts.add("Cache: ${info.cacheCount}")
            if (info.requestCount != null) {
                val inFlight = info.inFlightRequests ?: 0
                parts.add("Req: ${info.requestCount} (+$inFlight)")
            }
            lines.add(parts.joinToString(" | "))
        }
        if (info.loadTimeMs != null) lines.add("Load: ${info.loadTimeMs}ms")
        text = SpannableStringBuilder(lines.joinToString("\n")).apply {
            if (!info.error.isNullOrBlank()) {
                val start = length
                append("\nErr: ").append(info.error)
                setSpan(ForegroundColorSpan(ERROR_COLOR), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        setTypeface(null, Typeface.BOLD)
        setBackgroundColor(0xAA000000.toInt())
        setPadding(12, 8, 12, 8)
        tag = "ad_debug_overlay"
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        elevation = 10f
    }
}

fun addDebugOverlayToView(container: FrameLayout, context: Context, info: AdDebugInfo) {
    container.findViewWithTag<View>("ad_debug_overlay")?.let { container.removeView(it) }
    container.addView(createDebugOverlayView(context, info))
}
