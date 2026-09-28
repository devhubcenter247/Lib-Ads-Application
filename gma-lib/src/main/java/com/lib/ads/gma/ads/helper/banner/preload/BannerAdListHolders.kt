package com.lib.ads.gma.ads.helper.banner.preload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.helper.banner.BannerAds
import com.lib.ads.gma.ads.helper.banner.buildBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.ads.util.AdFormat
import com.lib.ads.gma.ads.util.AdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Banner holders for ad slots inside a LazyColumn/LazyRow, keyed by the slot's item key and owned
 * by the list instead of the item.
 *
 * A holder created per item (`BannerAd` inside `items {}`) dies whenever the item
 * scrolls off screen, so each appearance cold-loads while it is on screen (shimmer, empty-buffer
 * polls) and loads still in flight when the item leaves are wasted. Holders here survive
 * scrolling and each appearance still earns a new impression:
 * - nothing loads while a slot is off screen (the user may never scroll back);
 * - when a slot comes back with an ad that was already counted, it swaps in a buffered ad at once,
 *   or, with an empty buffer, loads one after [BannerAdListItem]'s delay while the old ad stays;
 * - an ad that has not been counted yet stays in its slot until it is seen;
 * - a load started for a slot still lands in that slot after it scrolls away;
 * - [prefetchCount] ads are kept buffered for [tag] so a slot seen for the first time fills fast.
 *
 * Holders not on screen are evicted (least recently shown first) beyond [maxHolders], destroying
 * their banner; all are disposed when the list leaves composition or the lifecycle is destroyed.
 */
@Stable
class BannerAdListHolders internal constructor(
    val tag: String,
    private val scope: CoroutineScope,
    private val options: BannerAdPreloadHolderOptions,
    private val enabled: Boolean,
    private val maxHolders: Int,
    private val prefetchCount: Int,
) {
    private val holders = LinkedHashMap<Any, BannerAdHolder>(16, 0.75f, true)
    private val attachCounts = HashMap<Any, Int>()
    private var disposed = false

    /** The holder for the slot with [key]; the same key always returns the same live holder. */
    fun holder(key: Any): BannerAdHolder {
        holders[key]?.let { return it }
        val holder = buildBannerAdHolder(tag = tag, enabled = !disposed && enabled, scope = scope, options = options)
        holders[key] = holder
        trim(keep = key)
        return holder
    }

    internal fun attach(key: Any) {
        attachCounts[key] = (attachCounts[key] ?: 0) + 1
        val holder = holders[key] ?: return // also touches: most recently shown
        // Back on screen with an ad already counted: swap in a buffered ad now so this appearance
        // earns a new impression. Without a buffered ad, BannerAdListItem loads after its delay.
        if (holder.needsFreshAd() && BannerAds.available(tag) > 0) holder.reload()
    }

    internal fun detach(key: Any) {
        val count = (attachCounts[key] ?: 0) - 1
        if (count > 0) {
            attachCounts[key] = count
            return
        }
        attachCounts.remove(key)
        trim()
    }

    /** Keeps [prefetchCount] ads buffered for [tag]; no-op while the buffer is full or loading. */
    internal fun topUpBuffer() {
        if (disposed || prefetchCount <= 0 || !enabled) return
        if (!BannerAds.isRegistered(tag)) return
        BannerAds.preload(tag, prefetchCount)
    }

    private fun trim(keep: Any? = null) {
        if (holders.size <= maxHolders) return
        val iterator = holders.entries.iterator()
        while (holders.size > maxHolders && iterator.hasNext()) {
            val (key, holder) = iterator.next()
            if (key == keep || key in attachCounts) continue
            iterator.remove()
            AdLogger.d(AdFormat.BANNER, tag, "LIST_EVICT", "key=$key holders=${holders.size}")
            holder.dispose()
        }
    }

    internal fun disposeAll() {
        disposed = true
        holders.values.forEach { it.dispose() }
        holders.clear()
        attachCounts.clear()
    }
}

/**
 * Remembers list-owned banner holders for [tag]. Call it outside the lazy list and pass the
 * result to [BannerAdListItem] for each ad slot.
 */
@Composable
fun rememberBannerAdListHolders(
    tag: String,
    options: BannerAdPreloadHolderOptions = BannerAdPreloadHolderOptions(),
    enabled: Boolean = true,
    maxHolders: Int = 10,
    prefetchCount: Int = 1,
): BannerAdListHolders {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val holders = remember(
        tag,
        options.fallbackAdUnitIds.hashCode(),
        options.size,
        options.collapsibleGravity,
        options.placementId,
        enabled,
        maxHolders,
        prefetchCount,
    ) {
        BannerAdListHolders(tag, scope, options, enabled, maxHolders.coerceAtLeast(1), prefetchCount)
    }
    DisposableEffect(lifecycleOwner, holders) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) holders.disposeAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holders.disposeAll()
        }
    }
    return holders
}

/**
 * One banner slot in a lazy list. [key] identifies the slot (use the same key as the lazy
 * item). A slot that is empty, or shows an ad already counted, loads after staying on screen for
 * [requestDelayMs], so flinging past slots does not fire requests.
 */
@Composable
fun BannerAdListItem(
    holders: BannerAdListHolders,
    key: Any,
    modifier: Modifier = Modifier,
    requestDelayMs: Long = 300L,
    adCallback: BannerAdListener? = null,
    /** Null uses [BannerAdCard]'s default shimmer sized for the banner. */
    loading: (@Composable () -> Unit)? = null,
    error: @Composable (LoadAdError?) -> Unit = {},
) {
    val holder = holders.holder(key)

    DisposableEffect(holders, key) {
        holders.attach(key)
        onDispose { holders.detach(key) }
    }

    DisposableEffect(holder, adCallback) {
        adCallback?.let(holder::registerAdCallback)
        onDispose { adCallback?.let(holder::unregisterAdCallback) }
    }

    LaunchedEffect(holder) {
        if (holder.needsRequest() || holder.needsFreshAd()) {
            if (requestDelayMs > 0) delay(requestDelayMs.milliseconds)
            when {
                holder.needsRequest() -> holder.request()
                holder.needsFreshAd() -> holder.reload()
            }
        }
        holders.topUpBuffer()
    }

    if (loading != null) {
        BannerAdCard(holder = holder, modifier = modifier, loading = loading, error = error)
    } else {
        BannerAdCard(holder = holder, modifier = modifier, error = error)
    }
}

private fun BannerAdHolder.needsFreshAd(): Boolean = isLoaded && isCurrentAdImpressed

private fun BannerAdHolder.needsRequest(): Boolean = when (currentState) {
    is BannerAdDisplayState.Idle, is BannerAdDisplayState.Error -> true
    else -> false
}
