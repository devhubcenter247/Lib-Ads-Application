package com.lib.ads.gma.ads.util

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

object ActivityOverlayGuard : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var armed = false
    private val overlays = mutableMapOf<Activity, View>()
    private val destinationWaiters = mutableListOf<DestinationWaiter>()

    private class DestinationWaiter(
        val excluding: Activity,
        val resume: (Activity) -> Unit,
    )
    fun arm(app: Application) {
        if (armed) return
        armed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    fun disarm(app: Application) {
        if (!armed) return
        armed = false
        app.unregisterActivityLifecycleCallbacks(this)
        overlays.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        overlays.clear()
        destinationWaiters.clear()
    }

    /**
     * Waits for the Activity created by the navigation that follows [arm]. The same lifecycle
     * callback that installs the frame-zero cover also resolves this waiter, so no global current
     * Activity registry or polling is needed.
     */
    suspend fun awaitDestination(
        excluding: Activity,
        timeoutMs: Long,
    ): Activity? = withTimeoutOrNull(timeoutMs.milliseconds) {
        suspendCancellableCoroutine { continuation ->
            val waiter = DestinationWaiter(excluding) { activity ->
                if (continuation.isActive) continuation.resumeWith(Result.success(activity))
            }
            destinationWaiters += waiter
            continuation.invokeOnCancellation { destinationWaiters.remove(waiter) }
        }
    }

    fun removeOverlay(activity: Activity) {
        overlays.remove(activity)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!armed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val cover = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        decor.addView(cover)
        overlays[activity] = cover
    }

    override fun onActivityStarted(activity: Activity) {
        if (!armed) return
        val matched = destinationWaiters.filter { it.excluding !== activity }
        if (matched.isEmpty()) return
        destinationWaiters.removeAll(matched)
        matched.forEach { it.resume(activity) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        overlays.remove(activity)
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
