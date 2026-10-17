package com.lib.ads.gma.ads.helper.fullscreen

import android.app.Activity
import android.app.Dialog
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.engine.AdsProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground gate shared by GMA fullscreen helpers. */
internal fun isAppInForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

internal fun interface ForegroundGateHandle {
    fun cancel()
}

private val NoopGateHandle = ForegroundGateHandle { }

internal fun showWaitingAdDialog(activity: Activity): Dialog? = runCatching {
    if (activity.isFinishing || activity.isDestroyed) return null
    PrepareLoadingAdsDialog(activity).apply {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        show()
    }
}.getOrNull()

internal fun Dialog?.dismissSafely() {
    runCatching { this?.takeIf { it.isShowing }?.dismiss() }
}

/**
 * The single loading dialog a singleton manager shows over a full-screen ad. It is dismissed and
 * released when its Activity is destroyed, so the manager never pins a dead Activity (and its
 * window) between two shows. Main thread only.
 */
internal class LoadingDialogSlot {
    private var dialog: Dialog? = null
    private var boundLifecycle: Lifecycle? = null
    private val releaseOnDestroy = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dismiss()
    }

    val current: Dialog? get() = dialog

    fun show(activity: Activity) {
        dismiss()
        val shown = showWaitingAdDialog(activity) ?: return
        dialog = shown
        boundLifecycle = (activity as? LifecycleOwner)?.lifecycle?.also { it.addObserver(releaseOnDestroy) }
    }

    /** Always forgets the dialog, even when dismissing it throws (window already gone). */
    fun dismiss() {
        val shown = dialog
        dialog = null
        boundLifecycle?.removeObserver(releaseOnDestroy)
        boundLifecycle = null
        shown.dismissSafely()
    }
}

/** Runs exactly one branch, immediately or on the next foreground transition. */
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
    AdsProvider.getInstance().setFullScreenAdShowing(true)
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
            AdsProvider.getInstance().setFullScreenAdShowing(false)
            onDropped()
        }
    }
}
