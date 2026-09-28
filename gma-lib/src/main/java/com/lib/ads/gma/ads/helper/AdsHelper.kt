package com.lib.ads.gma.ads.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.helper.params.IAdsParam
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

abstract class AdsHelper<C : IAdsConfig, P : IAdsParam>(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    protected open val config: C
) {
    private var tag: String = context.javaClass.simpleName
    internal val flagActive = AtomicBoolean(false)
    internal val lifecycleEventState: MutableStateFlow<Lifecycle.Event> = MutableStateFlow(Lifecycle.Event.ON_ANY)
    var flagUserEnableReload: Boolean = true
        set(value) {
            field = value
            logZ("setFlagUserEnableReload($flagUserEnableReload")
        }

    init {
        lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                lifecycleEventState.value = event
                if (event == Lifecycle.Event.ON_DESTROY) {
                    lifecycleOwner.lifecycle.removeObserver(this)
                }
            }
        })
    }

    open fun canShowAds(): Boolean =
        !AppPurchase.getInstance().isPurchased() && config.canShowAds && ConsentManager.getConsentResult(context)

    open fun canRequestAds(): Boolean = canShowAds() && isOnline()

    abstract fun requestAds(param: P)

    abstract fun cancel()

    fun setTagForDebug(tag: String) { this.tag = tag }

    fun isActiveState(): Boolean = flagActive.get()

    fun canReloadAd(): Boolean = config.canReloadAds && flagUserEnableReload

    internal fun logZ(message: String) {
        Log.d(javaClass.simpleName, "$tag: $message")
    }

    internal fun logInterruptExecute(message: String) {
        logZ("$message not execute because has called cancel()")
    }

    internal fun isOnline(): Boolean = isOnline(context)
}

/** Shared connectivity check reused by [AdsHelper.isOnline] and the standalone full-screen helpers. */
internal fun isOnline(context: Context): Boolean {
    return runCatching {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return@runCatching false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}

/**
 * Shared gate for the standalone full-screen helpers (interstitial / rewarded / app-open), which
 * don't extend [AdsHelper]: true when the placement is enabled, the app isn't purchased, UMP
 * consent has been resolved, and the device is online.
 */
internal fun canRequestFullScreenAds(context: Context, canShowAds: Boolean): Boolean {
    return canShowAds &&
        !AppPurchase.getInstance().isPurchased() &&
        ConsentManager.getConsentResult(context) &&
        isOnline(context)
}
