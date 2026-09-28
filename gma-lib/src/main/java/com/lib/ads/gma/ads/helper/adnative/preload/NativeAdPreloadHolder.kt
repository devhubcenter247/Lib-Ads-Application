@file:JvmName("NativeAdPreloadHolderKt")

package com.lib.ads.gma.ads.helper.adnative.preload

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.helper.adnative.NativeAdRequestOptions
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class NativeAdPreloadHolderOptions(
    val fallbackAdUnitIds: List<String> = emptyList(),
    val timeoutPerIdMs: Long = 10_000L,
    val adOptionVisibility: AdOptionVisibility = AdOptionVisibility.GONE,
    val lifecycleOwner: LifecycleOwner? = null,
    val autoRequestOnStart: Boolean = false,
    val autoReloadOnResume: Boolean = true,
    val cancelOnPause: Boolean = false,
    val destroyOnDispose: Boolean = true,
    val requestOptions: NativeAdRequestOptions = NativeAdRequestOptions(),
    val canShowAds: Boolean = true,
    val canReloadAds: Boolean = true,
)

/**
 * Creates a native holder that consumes the cached ad registered under [tag]. If the tag has not
 * been registered, [NativeAdPreloadHolderOptions.fallbackAdUnitIds] enables a normal cold load.
 * No Activity is needed: loading uses the application context and the holder hands out an
 * [com.lib.ads.gma.ads.model.wrapper.ApNativeAd] for the caller to bind into its own view.
 *
 * For XML/Activity screens only — [options]'s `lifecycleOwner` binds the holder's request/reload
 * to that owner's lifecycle for as long as the holder lives. In Compose, use
 * [rememberNativeAdPreload] or [NativeAdWithPreload] instead: they bind to
 * `LocalLifecycleOwner.current`, i.e. the Compose screen's own lifecycle. Creating a holder here
 * with the Activity and passing it down into Compose ties the ad's lifetime to the Activity even
 * if the Compose screen is swapped out (e.g. by Navigation) while the Activity stays alive.
 */
fun createNativeAdHolder(
    enabled: Boolean = true,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    tag: String,
    options: NativeAdPreloadHolderOptions = NativeAdPreloadHolderOptions(),
): Lazy<NativeAdHolderConfig> = lazy {
    buildNativeAdHolder(tag, enabled, scope, options).also { holder ->
        if (holder.currentState !is NativeAdDisplayState.Cancelled) {
            options.lifecycleOwner?.let { bindNativeHolderLifecycle(it, holder, options) }
        }
    }
}

/** Builds the holder + request controller. Shared by [createNativeAdHolder] and Compose. */
internal fun buildNativeAdHolder(
    tag: String,
    enabled: Boolean,
    scope: CoroutineScope,
    options: NativeAdPreloadHolderOptions,
): NativeAdHolderConfig {
    val state = MutableStateFlow<NativeAdDisplayState>(NativeAdDisplayState.Idle)
    val lastError = MutableStateFlow<String?>(null)
    lateinit var controller: NativePreloadRequestController

    val holder = NativeAdHolderConfig(
        tag = tag,
        _state = state,
        job = null,
        restartBlock = { controller.request() },
        adOptionVisibility = options.adOptionVisibility,
        bindCallback = { ad -> controller.bindAdCallback(ad) },
        onCancelLoad = { controller.cancel() },
        _lastError = lastError,
        onDispose = { impressed ->
            controller.cancel()
            val shownAd = (state.value as? NativeAdDisplayState.Success)?.ad
            if (options.destroyOnDispose && shownAd != null) {
                // A list item scrolled away before its ad was counted: hand the ad to the next
                // holder of this tag instead of destroying a fill that was never seen.
                if (impressed) runCatching { shownAd.nativeAd?.destroy() }
                else NativeAds.offer(tag, shownAd)
            }
        },
    )
    controller = NativePreloadRequestController(tag, enabled, scope, options, state, holder.callbackRelay, lastError)

    if (!enabled || !options.canShowAds) {
        state.value = NativeAdDisplayState.Cancelled(options.adOptionVisibility)
    }
    return holder
}

/**
 * Binds request/reload/pause/dispose to [owner]. Shared by [createNativeAdHolder] and Compose.
 * Returns a function that detaches the observer early (Compose leaving composition).
 */
internal fun bindNativeHolderLifecycle(
    owner: LifecycleOwner,
    holder: NativeAdHolderConfig,
    options: NativeAdPreloadHolderOptions,
): () -> Unit {
    var pauseJob: Job? = null
    var hasResumed = false
    lateinit var observer: LifecycleEventObserver
    observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                pauseJob?.cancel()
                pauseJob = null
                val isFirstResume = !hasResumed
                hasResumed = true
                when {
                    isFirstResume && options.autoRequestOnStart &&
                        holder.currentState is NativeAdDisplayState.Idle -> holder.request()
                    !isFirstResume && options.autoReloadOnResume && options.canReloadAds ->
                        holder.reload()
                }
            }
            Lifecycle.Event.ON_PAUSE -> {
                if (options.cancelOnPause) {
                    pauseJob?.cancel()
                    pauseJob = owner.lifecycleScope.launch {
                        delay(500.milliseconds)
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            holder.pauseLoad()
                        }
                    }
                }
            }
            Lifecycle.Event.ON_DESTROY -> {
                pauseJob?.cancel()
                owner.lifecycle.removeObserver(observer)
                holder.dispose()
            }
            else -> Unit
        }
    }
    // addObserver replays ON_CREATE..ON_RESUME for an owner that is already resumed, so the first
    // request fires from the observer itself; requesting again here would consume a second ad.
    owner.lifecycle.addObserver(observer)
    return {
        pauseJob?.cancel()
        owner.lifecycle.removeObserver(observer)
    }
}
