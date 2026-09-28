package com.c014.ai.art.base

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Stable
data class KeyboardState(
    val heightState: MutableIntState = mutableIntStateOf(0),
    val visibleState: MutableState<Boolean> = mutableStateOf(false),
)

var KeyboardState.visible: Boolean
    get() = visibleState.value
    set(v) { visibleState.value = v }

var KeyboardState.height: Int
    get() = heightState.intValue
    set(v) { heightState.intValue = v }
val LocalKeyboardState = staticCompositionLocalOf {
    KeyboardState(heightState = mutableIntStateOf(0), visibleState = mutableStateOf(false))
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

fun Window.lightStatusBar(isLightStatusBar: Boolean) {
    val controller = WindowInsetsControllerCompat(this, this.decorView)
    controller.isAppearanceLightStatusBars = isLightStatusBar
}
