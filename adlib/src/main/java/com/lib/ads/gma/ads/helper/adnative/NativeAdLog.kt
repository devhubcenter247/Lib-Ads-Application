package com.lib.ads.gma.ads.helper.adnative

import com.lib.ads.gma.ads.util.AppLogger

/**
 * Single, structured logging façade for the whole native-ad pipeline
 * (preload buffer, cold loader, Compose holder, XML helper).
 *
 * Each `scope` maps to its **own logcat tag** (e.g. `NativeAds-Load`,
 * `NativeAds-Request`, `NativeAds-Buffer`) so a single category can be filtered in
 * isolation, while the shared `NativeAds-` prefix still lets you see the whole flow:
 *
 * ```
 * adb logcat -s NativeAds-Request          # only consumer requests
 * adb logcat -s NativeAds-Load             # only AdMob load lifecycle
 * adb logcat | grep NativeAds-             # everything
 * ```
 *
 * In Android Studio Logcat just type `tag:NativeAds-Request` (or `tag:NativeAds-` for all).
 *
 * Each message still carries the structured `tag=…`/`unit=…` keys so a single
 * placement (and a single ad unit inside a waterfall) can be traced.
 *
 * Output is gated by [AppLogger.isEnabled] (set once at app start), so these calls
 * are effectively free in release builds.
 *
 * Scopes / logcat tags:
 * - `register` → `NativeAds-Register` — spec registration / update
 * - `preload`  → `NativeAds-Preload`  — buffer warm-up lifecycle (start / refill / stop)
 * - `buffer`   → `NativeAds-Buffer`   — buffer mutations (added / served / peeked / pruned)
 * - `state`    → `NativeAds-State`    — [PreloadBufferState] transitions of a slot
 * - `load`     → `NativeAds-Load`     — a single AdMob request lifecycle (request/loaded/failed)
 * - `request`  → `NativeAds-Request`  — a consumer asking for an ad (XML helper / Compose holder)
 * - `compose`  → `NativeAds-Compose`  — Compose-specific lifecycle (recompose / resume / dispose)
 */
internal object NativeAdLog {

    const val PREFIX = "NativeAds"

    /**
     * Per-scope logcat tag, e.g. scope "load" -> "NativeAds-Load".
     *
     * `preload` and `request` are intentionally folded into the same `NativeAds-Preload`
     * tag so the whole "warm the buffer + a consumer asks for an ad" timeline of a
     * placement can be followed under one filter.
     */
    private fun tagFor(scope: String): String {
        val normalized = when (scope) {
            "request" -> "preload"
            else -> scope
        }
        return "$PREFIX-${normalized.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
    }

    private fun format(placement: String?, unit: String?, message: String): String {
        val sb = StringBuilder()
        if (!placement.isNullOrBlank()) {
            sb.append("[tag=").append(placement.directionalAdLabel()).append("]")
        }
        if (!unit.isNullOrBlank()) sb.append("[unit=").append(unit).append("]")
        if (sb.isNotEmpty()) sb.append(' ')
        return sb.append(message).toString()
    }

    fun d(scope: String, tag: String?, message: String, unit: String? = null) {
        if (!AppLogger.isEnabled) return
        AppLogger.d(tagFor(scope), format(tag, unit, message))
    }

    fun w(scope: String, tag: String?, message: String, unit: String? = null) {
        if (!AppLogger.isEnabled) return
        AppLogger.w(tagFor(scope), format(tag, unit, message))
    }

    fun e(scope: String, tag: String?, message: String, unit: String? = null) {
        if (!AppLogger.isEnabled) return
        AppLogger.e(tagFor(scope), format(tag, unit, message))
    }
}

/** Compact, human-readable description of a [PreloadBufferState] for logging. */
internal fun PreloadBufferState.describe(): String = when (this) {
    is PreloadBufferState.Idle -> "Idle"
    is PreloadBufferState.Loading -> "Loading(requested=$requested, loaded=$loaded)"
    is PreloadBufferState.ItemLoaded -> "ItemLoaded(unit=$adUnitId, loaded=$loaded/$requested)"
    is PreloadBufferState.Ready -> "Ready(available=$available)"
    is PreloadBufferState.Error -> "Error(${message})"
    is PreloadBufferState.Cancelled -> "Cancelled"
}
