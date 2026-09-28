package com.lib.ads.gma.ads.helper.fullscreen

import android.app.Activity
import android.app.Dialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.manager.AdsManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared helpers for the `waitLoadAndShow` flow used by the full-screen formats
 * (interstitial / rewarded / rewarded-interstitial / app-open).
 *
 * A full-screen ad must only be shown while the app is in the foreground — calling `show()` from
 * the background makes the SDK fire `onAdFailedToShowFullScreenContent` and risks a policy
 * violation. These utilities gate the show on the process foreground state.
 */

/** True when the app process is at least STARTED (i.e. visible / foreground). */
internal fun isAppInForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

/**
 * Handle for a pending foreground-gated show. Holding code (the ad helper) keeps it and calls
 * [cancel] on teardown so a deferred show that can no longer run does not leak its
 * `ProcessLifecycleOwner` observer and still completes its flow (runs the dropped/abort path).
 */
internal fun interface ForegroundGateHandle {
    fun cancel()
}

private val NoopGateHandle = ForegroundGateHandle { }

/**
 * Run [onForeground] immediately when the app is already in the foreground. Otherwise, when
 * [deferToForeground] is true, run it **once** the app next returns to the foreground; when false,
 * run [onDropped] instead.
 *
 * Exactly one of [onForeground] / [onDropped] runs (guarded), so the caller's flow always
 * completes once. Returns a [ForegroundGateHandle]; calling [ForegroundGateHandle.cancel] before
 * the app returns removes the observer and runs [onDropped] (used on helper teardown so a
 * destroyed helper does not strand a deferred show).
 *
 * While a show is deferred, [AdsManager.setFullScreenAdShowing] is set so an app-open *resume* ad
 * does not also fire on the same return event; it is cleared if the deferred show is dropped /
 * aborted (the show path clears it itself once the ad is dismissed).
 *
 * Must be called on the main thread.
 */
internal fun runWhenAppForeground(
    deferToForeground: Boolean,
    onForeground: (wasDeferred: Boolean) -> Unit,
    onDropped: () -> Unit,
): ForegroundGateHandle {
    val done = AtomicBoolean(false)

    if (isAppInForeground()) {
        if (done.compareAndSet(false, true)) onForeground(false)
        return NoopGateHandle
    }
    if (!deferToForeground) {
        if (done.compareAndSet(false, true)) onDropped()
        return NoopGateHandle
    }

    // Deferring: suppress an app-open resume ad so it does not stack on the return event.
    AdsManager.setFullScreenAdShowing(true)
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (done.compareAndSet(false, true)) onForeground(true)
        }
    }
    lifecycle.addObserver(observer)

    return ForegroundGateHandle {
        lifecycle.removeObserver(observer)
        if (done.compareAndSet(false, true)) {
            AdsManager.setFullScreenAdShowing(false)
            onDropped()
        }
    }
}

/**
 * Show a non-cancelable loading dialog on [activity] while the ad loads. Returns null when the
 * activity can no longer host a dialog (finishing / destroyed).
 */
internal fun showWaitingAdDialog(activity: Activity): Dialog? = runCatching {
    if (activity.isFinishing || activity.isDestroyed) return null
    PrepareLoadingAdsDialog(activity).apply {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        show()
    }
}.getOrNull()

/** Dismiss a dialog if it is currently showing, swallowing any window-state exception. */
internal fun Dialog?.dismissSafely() {
    runCatching { this?.takeIf { it.isShowing }?.dismiss() }
}
