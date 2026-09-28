package com.lib.ads.gma.ads.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.lib.ads.gma.ads.admob.AdsConsentManager
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.helper.params.IAdsParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstract class providing a base implementation for managing advertisements in an Android application.
 *
 * @param C The type of configuration for advertisements, implementing [IAdsConfig].
 * @param P The type of parameters used in the advertisement request, implementing [IAdsParam].
 * @property context The context.
 * @property lifecycleOwner The [LifecycleOwner] associated with the lifecycle of the activity or fragment.
 * @property config The configuration for handling advertisements, implementing [IAdsConfig].
 */
abstract class AdsHelper<C : IAdsConfig, P : IAdsParam>(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner?,
    private val config: C
) {
    private var tag: String = context::class.java.simpleName
    /**
     * Atomic boolean flag indicating whether the ads helper is in an active state.
     */
    internal val flagActive: AtomicBoolean = AtomicBoolean(false)
    internal val lifecycleEventState = MutableStateFlow(Lifecycle.Event.ON_ANY)
    /**
     * Flag indicating whether the user has enabled the ability to reload ads.
     * This flag can be dynamically set to control the reloading behavior.
     */
    var flagUserEnableReload = true
        set(value) {
            field = value
            logZ("setFlagUserEnableReload($field)")
        }

    init {
        // lifecycleOwner is optional: full-screen helpers (interstitial / rewarded /
        // rewarded-interstitial) can be created without one. When absent there is no
        // lifecycle automation and lifecycleEventState simply never updates.
        lifecycleOwner?.lifecycle?.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                lifecycleEventState.update { event }
                when (event) {
                    Lifecycle.Event.ON_DESTROY -> {
                        source.lifecycle.removeObserver(this)
                    }
                    else -> Unit
                }
            }
        })
    }

    /**
     * Determines whether ads can be shown based on purchase status, configuration, and consent.
     *
     * @return True if ads can be shown, false otherwise.
     */
    open fun canShowAds(): Boolean {
        return !AppPurchase.getInstance().isPurchased() && config.canShowAds && AdsConsentManager.getConsentResult(context)
    }

    /**
     * Determines whether ads can be requested based on the ability to show ads and network connectivity.
     *
     * @return True if ads can be requested, false otherwise.
     */
    open fun canRequestAds(): Boolean {
        return canShowAds() && isOnline()
    }
    /**
     * Abstract method to be implemented by subclasses for requesting ads.
     *
     * @param param The parameters for the ad request, implementing [IAdsParam].
     */
    abstract fun requestAds(param: P)

    /**
     * Abstract method to be implemented by subclasses for canceling the ad request.
     */
    abstract fun cancel()

    fun setTagForDebug(tag: String) {
        this.tag = tag
    }

    /**
     * Checks whether the AdsHelper is in an active state.
     *
     * @return True if the AdsHelper is active, false otherwise.
     */
    fun isActiveState(): Boolean {
        return flagActive.get()
    }
    /**
     * Checks whether ad reloading is allowed based on configuration and user preferences.
     *
     * @return True if ad reloading is allowed, false otherwise.
     */
    fun canReloadAd(): Boolean {
        return config.canReloadAds && flagUserEnableReload
    }

    internal fun logZ(message: String) {
        Log.d(this::class.java.simpleName, "${tag}: $message")
    }

    /**
     * Internal method for logging interruptions due to cancellation.
     *
     * @param message The message indicating that the operation was not executed due to cancellation.
     */
    internal fun logInterruptExecute(message: String) {
        logZ("$message not execute because has called cancel()")
    }

    internal fun isOnline(): Boolean = isOnline(context)
}

/** Shared connectivity check reused by [AdsHelper.isOnline] and the legacy ad managers. */
internal fun isOnline(context: Context): Boolean {
    return kotlin.runCatching {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return@runCatching false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}

/**
 * Interface representing the configuration for handling advertisements in the application.
 */
interface IAdsConfig {
    /**
     * Ad unit ids tried in waterfall order (first = primary). Always treated as a waterfall:
     * a single ad unit is simply a one-element list. Should never be empty in practice.
     */
    val listId: List<String>
    /**
     * A boolean flag indicating whether the application is allowed to display advertisements.
     * If set to `true`, the application can show ads; otherwise, it should refrain from displaying them.
     */
    val canShowAds: Boolean
    /**
     * A boolean flag specifying whether the application is permitted to reload or refresh advertisements.
     * If set to `true`, the application can request a reload of ad content as needed.
     */
    val canReloadAds: Boolean

    /**
     * First / representative ad unit id, derived from [listId]. Kept for logging, paid-event
     * reporting and `remember` keys. Loading always iterates [listId] as a waterfall.
     */
    val idAds: String get() = listId.firstOrNull().orEmpty()
}

/**
 * Enum representing the visibility options for advertising elements.
 */
enum class AdOptionVisibility {
    /**
     * The advertising element is not visible and does not occupy any space in the layout.
     */
    GONE,
    /**
     * The advertising element is invisible but still occupies space in the layout.
     */
    INVISIBLE
}

enum class BannerCollapseGravity(val gravity: String = "") {
    None,
    Bottom("bottom"),
    Top("top");

    companion object {
        fun fromGravity(gravity: String?): BannerCollapseGravity {
            return when (gravity?.lowercase()) {
                Bottom.gravity -> Bottom
                Top.gravity -> Top
                else -> None
            }
        }
    }
}
