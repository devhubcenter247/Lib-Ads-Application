package com.lib.ads.gma.app.base

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.c014.ai.art.base.KeyboardState
import com.c014.ai.art.base.LocalKeyboardState
import com.c014.ai.art.base.height
import com.c014.ai.art.base.hideSystemBar
import com.c014.ai.art.base.setFullScreen
import com.c014.ai.art.base.visible
import com.lib.ads.gma.app.style.AppViewTheme

abstract class BaseActivity(private val lightStatus: Boolean = true) : ComponentActivity() {
    abstract fun initialize()

    @Composable
    abstract fun BoxScope.ContentView()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        window.setFullScreen()
        window.hideSystemBar()
        initialize()
        setContent { ComposeContent() }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ComposeContent() {
        val focusManager = LocalFocusManager.current
        val imeVisible = WindowInsets.isImeVisible
        val imeHeightPx = WindowInsets.ime.getBottom(LocalDensity.current)
        val keyboardState = remember { KeyboardState() }

        LaunchedEffect(imeVisible, imeHeightPx) {
            keyboardState.visible = imeVisible
            keyboardState.height = imeHeightPx
        }

        CompositionLocalProvider(LocalKeyboardState provides keyboardState) {
            AppViewTheme(lightStatus) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { focusManager.clearFocus(force = true) })
                        }
                ) {
                    ContentView()
                }
            }
        }
    }

    private var activityResultCallback: ((ActivityResult) -> Unit)? = null

    private val resultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        listenerResult(result)
        activityResultCallback?.invoke(result)
        activityResultCallback = null
    }

    fun launchForResult(intent: Intent, callback: (ActivityResult) -> Unit) {
        activityResultCallback = callback
        resultLauncher.launch(intent)
    }

    open fun listenerResult(result: ActivityResult) {}

    inline fun <reified T : Any> launchActivity(vararg params: Pair<String, Any?>) {
        startActivity(createIntent<T>(*params))
    }
    inline fun <reified T : Any> launchAndClearTask(vararg params: Pair<String, Any?>) {
        startActivity(createIntent<T>(*params).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    inline fun <reified T : Any> createIntent(vararg params: Pair<String, Any?>): Intent {
        return Intent(this, T::class.java).apply {
            params.forEach { (key, value) ->
                runCatching { putExtraSmart(key, value) }
                    .onFailure { e ->
                        Log.w(TAG, "Skip extra \"$key\": ${e.message}")
                    }
            }
        }
    }
    companion object {
        const val TAG = "BaseActivity"
    }
}
