package com.lib.ads.gma.app.style

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
@Composable
fun AppViewTheme(
    lightStatusBar: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = remember(view) { view.context.findActivity()?.window }
        if (window != null) {
            val insetsController = remember(window, view) {
                WindowCompat.getInsetsController(window, view)
            }
            LaunchedEffect(window, view) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            LaunchedEffect(lightStatusBar) {
                insetsController.isAppearanceLightStatusBars = lightStatusBar
            }
        }
    }
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color(0xFF103A0B),
            primaryContainer = Color(0xFF2E7D20),
            onPrimaryContainer = primaryLightColor,
            secondary = secondaryColor,
            onSecondary = white,
            tertiary = accentColor,
            onTertiary = Color(0xFF3A2B00),
            background = Color(0xFF0F170E),
            surface = Color(0xFF0F170E),
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color(0xFF103A0B),
            primaryContainer = primaryLightColor,
            onPrimaryContainer = Color(0xFF103A0B),
            secondary = secondaryColor,
            onSecondary = white,
            tertiary = accentColor,
            onTertiary = Color(0xFF3A2B00),
            background = appBackgroundColor,
            surface = appBackgroundColor,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}


@Composable
fun rememberLifecycleEvent(lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current): Lifecycle.Event {
    var state by remember { mutableStateOf(Lifecycle.Event.ON_ANY) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            state = event
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return state
}

@Composable
fun OnLifecycleEvent(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onCreate: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onDestroy: (() -> Unit)? = null,
) {
    val callbacks = rememberUpdatedState(
        mapOf(
            Lifecycle.Event.ON_CREATE to onCreate,
            Lifecycle.Event.ON_START to onStart,
            Lifecycle.Event.ON_RESUME to onResume,
            Lifecycle.Event.ON_PAUSE to onPause,
            Lifecycle.Event.ON_STOP to onStop,
            Lifecycle.Event.ON_DESTROY to onDestroy,
        )
    )
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            callbacks.value[event]?.invoke()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun rememberCurrentLifecycleState(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
): Lifecycle.State {
    var state by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, _ ->
            state = source.lifecycle.currentState
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
@Composable
fun rememberActivity(): Activity? {
    val context = LocalContext.current
    return remember(context) { context.findActivity() }
}
@Composable
fun rememberComponentActivity(): ComponentActivity? {
    val context = LocalContext.current
    return remember(context) { context.findActivity() as? ComponentActivity }
}
@Composable
fun rememberWindow() = rememberActivity()?.window

@SuppressLint("LocalContextConfigurationRead")
@Composable
fun rememberIsDarkTheme(): Boolean {
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode
    return (uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
@SuppressLint("LocalContextConfigurationRead")
@Composable
fun rememberIsLandscape(): Boolean {
    val context = LocalContext.current
    return context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
}
