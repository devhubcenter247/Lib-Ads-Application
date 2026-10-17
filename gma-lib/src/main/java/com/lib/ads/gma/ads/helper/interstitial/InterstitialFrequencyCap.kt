package com.lib.ads.gma.ads.helper.interstitial

import android.os.SystemClock

/**
 * When an interstitial last went full screen — shared by every [InterstitialAdHelper] and
 * [com.lib.ads.gma.ads.manager.InterstitialAdManager]. A per-helper timestamp let each screen's
 * helper show an interstitial right after another screen's one. Monotonic clock, so changing
 * the device time neither blocks ads for hours nor lifts the cap.
 */
internal object InterstitialFrequencyCap {
    @Volatile
    private var lastShownAt: Long = NEVER

    fun markShown() {
        lastShownAt = SystemClock.elapsedRealtime()
    }

    fun isCoolingDown(intervalMs: Long): Boolean =
        isCoolingDown(SystemClock.elapsedRealtime(), lastShownAt, intervalMs)

    /** Pure rule, unit-tested. */
    fun isCoolingDown(now: Long, lastShownAt: Long, intervalMs: Long): Boolean =
        intervalMs > 0L && lastShownAt != NEVER && now - lastShownAt < intervalMs

    const val NEVER = -1L
}
