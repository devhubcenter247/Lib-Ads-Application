package com.lib.ads.gma.ads.util

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object AppUtil {
    /**
     * Fails closed: a consumer that never applies an [com.lib.ads.gma.ads.config.AdSdkConfig]
     * must not get the fake dev purchase sheet in production.
     */
    @JvmField
    var VARIANT_DEV: Boolean = false

    @Deprecated("Not read by the SDK any more; the 0.01 bucket lives in SharePreferenceUtils (micros, thread-safe).")
    @JvmField
    var currentTotalRevenue001Ad: Float = 0f

    @JvmStatic
    fun countCharInStr(str: String, targetChar: Char): Int = str.count { it == targetChar }
}

fun Window.setFullScreen() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        this.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
    }
    val layoutParams = this.attributes
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
        layoutParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    this.attributes = layoutParams
}

fun Window.hideSystemBar() {
    val windowInsetsController = WindowCompat.getInsetsController(this, this.decorView)
    windowInsetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
}