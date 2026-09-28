package com.lib.ads.gma.ads.helper.banner.preload

import ads_mobile_sdk.nu
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.helper.banner.BannerAds
import com.lib.ads.gma.ads.helper.banner.bindBannerHolderLifecycle
import com.lib.ads.gma.ads.helper.banner.buildBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.params.loadingHeightDp
import com.lib.ads.gma.ads.helper.banner.params.BannerAdConfig
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.banner.params.BannerLoadingColors
import com.lib.ads.gma.ads.helper.banner.params.BannerPreloadState
import com.lib.ads.gma.ads.manager.BannerAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.BannerAdListener
import com.lib.ads.gma.ads.util.AppLogger
import com.lib.ads.gma.compose.AdDebugInfo
import com.lib.ads.gma.compose.AdDebugOverlayCompose
import com.lib.ads.gma.compose.pxToDp
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Remembers a banner holder for [tag], bound to the Compose screen's lifecycle. It consumes the
 * banner buffered for [tag] first; if the tag is not registered by a preload,
 * [BannerAdPreloadHolderOptions.fallbackAdUnitIds] enables a normal load. `lifecycleOwner` in
 * [options] is ignored: the holder binds to `LocalLifecycleOwner.current`.
 */
@Composable
fun rememberBannerAd(
    tag: String,
    options: BannerAdPreloadHolderOptions = BannerAdPreloadHolderOptions(autoRequestOnStart = true),
    enabled: Boolean = true,
    callback: BannerAdListener? = null,
): BannerAdHolder {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val holder = remember(
        tag,
        enabled,
        options.fallbackAdUnitIds.hashCode(),
        options.size,
        options.collapsibleGravity,
        options.placementId,
    ) {
        buildBannerAdHolder(tag = tag, enabled = enabled, scope = scope, options = options)
    }

    DisposableEffect(holder, callback) {
        callback?.let(holder::registerAdCallback)
        onDispose {
            callback?.let(holder::unregisterAdCallback)
        }
    }

    // Same lifecycle rules as createBannerAdHolder (XML), bound to the Compose screen's owner.
    DisposableEffect(lifecycleOwner, holder) {
        val unbind = bindBannerHolderLifecycle(owner = lifecycleOwner, holder = holder, options = options)
        onDispose {
            unbind()
            holder.dispose()
        }
    }

    return holder
}

/** [rememberBannerAd] for a [BannerAdConfig]; its tag is [BannerAdConfig.tag] or derived from it. */
@Composable
fun rememberBannerAd(
    config: BannerAdConfig,
    autoReloadOnResume: Boolean = true,
    callback: BannerAdListener? = null,
): BannerAdHolder = rememberBannerAd(
    tag = config.resolvedTag(),
    options = config.toHolderOptions(autoReloadOnResume),
    enabled = config.canShowAds,
    callback = callback,
)

@Composable
fun BannerAdCard(
    holder: BannerAdHolder?,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = {
        val height = holder?.loadingHeightDp ?: DEFAULT_BANNER_LOADING_HEIGHT_DP
        if (height >= 90) {
            DefaultLargeBannerAdLoading(height)
        } else {
            DefaultBannerAdLoading(height)
        }
    },
    error: @Composable (LoadAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
) {
    if (LocalInspectionMode.current) {
        Box(modifier) { loading() }
        return
    }

    if(holder == null){
        return
    }
    // Use the holder's actual state as the initial value. Starting from Idle causes a one-frame
    // loading flash when a preloaded banner has already been consumed into this holder.
    val state by holder.state.collectAsStateWithLifecycle(holder.currentState)
    val preloadState by holder.tag?.let { BannerAds.stateFlow(it) }
        ?.collectAsStateWithLifecycle(BannerPreloadState.Idle)
        ?: remember { mutableStateOf(BannerPreloadState.Idle) }
    val lastError by holder.lastError.collectAsStateWithLifecycle()
    var loadStartTime by remember { mutableLongStateOf(0L) }
    var loadTimeMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state) {
        onStateChange?.invoke(state)
        when (state) {
            is BannerAdDisplayState.Loading -> loadStartTime = System.currentTimeMillis()
            is BannerAdDisplayState.Loaded -> {
                if (loadStartTime > 0) {
                    loadTimeMs = System.currentTimeMillis() - loadStartTime
                    loadStartTime = 0L
                }
            }

            else -> Unit
        }
    }

    val contentModifier = remember(state) {
        if (state is BannerAdDisplayState.Cancelled || state is BannerAdDisplayState.Error) {
            Modifier.height(0.dp)
        } else {
            Modifier.wrapContentHeight()
        }
    }

    Box(modifier = modifier
        .fillMaxWidth()
        .wrapContentHeight()) {
        Box(contentModifier.fillMaxWidth()) {
            when (val currentState = state) {
                is BannerAdDisplayState.Loading,
                is BannerAdDisplayState.Idle -> loading()

                is BannerAdDisplayState.Error -> error(currentState.error)
                is BannerAdDisplayState.Loaded -> {
                    key(currentState.adView) {
                        BannerAdViewComposable(
                            adView = currentState.adView,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                is BannerAdDisplayState.Cancelled -> Unit
            }
        }

        if (Ads.getInstance().adConfigOrNull?.showMessageForTester == true) {
            val debugInfo = remember(state, preloadState, loadTimeMs, lastError) {
                AdDebugInfo(
                    adType = "Banner",
                    state = state.debugLabel(),
                    adUnitId = (state as? BannerAdDisplayState.Loaded)?.adUnitId,
                    fromPreload = state is BannerAdDisplayState.Loaded,
                    cacheCount = holder.tag?.let(BannerAds::available),
                    requestCount = holder.tag?.let(BannerAds::requestCount),
                    inFlightRequests = holder.tag?.let(BannerAds::inFlightRequests),
                    loadTimeMs = loadTimeMs,
                    tag = holder.tag,
                    error = lastError,
                )
            }
            AdDebugOverlayCompose(info = debugInfo, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

private fun BannerAdDisplayState.debugLabel(): String = when (this) {
    is BannerAdDisplayState.Idle -> "Idle"
    is BannerAdDisplayState.Loading -> "Loading"
    is BannerAdDisplayState.Loaded -> "Loaded"
    is BannerAdDisplayState.Error -> "Error"
    is BannerAdDisplayState.Cancelled -> "Cancelled"
}

/**
 * Classifies whether a [BannerSize] falls into the larger banner category
 * (e.g. LargePortraitAdaptive, adaptive width, or fixed/height with height >= 90dp).
 */
fun BannerSize.isLargeBanner(): Boolean = when (this) {
    is BannerSize.LargePortraitAdaptive -> true
    is BannerSize.Width -> true
    is BannerSize.Height -> heightDp >= 90
    is BannerSize.Fixed -> heightDp >= 90
    is BannerSize.InlineAdaptive -> maxHeightDp >= 90
}

@Composable
fun BannerAd(
    config: BannerAdConfig,
    modifier: Modifier = Modifier,
    autoReloadOnResume: Boolean = true,
    /** Defaults to [DefaultBannerAdLoading] or [DefaultLargeBannerAdLoading] based on [BannerSize]. */
    loading: (@Composable () -> Unit)? = null,
    error: @Composable (LoadAdError?) -> Unit = {},
    onStateChange: ((BannerAdDisplayState) -> Unit)? = null,
    callback: BannerAdListener? = null,
) {
    val holder = rememberBannerAd(
        config = config,
        autoReloadOnResume = autoReloadOnResume,
        callback = callback
    )
    BannerAdCard(
        holder = holder,
        modifier = modifier,
        loading = loading ?: {
            if (config.size.isLargeBanner() || holder.loadingHeightDp >= 90) {
                DefaultLargeBannerAdLoading(holder.loadingHeightDp)
            } else {
                DefaultBannerAdLoading(holder.loadingHeightDp)
            }
        },
        error = error,
        onStateChange = onStateChange
    )
}

@Composable
private fun BannerAdViewComposable(adView: AdView, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color.White)) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BannerLoadingColors.DefaultDivider))
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = {
                (adView.parent as? ViewGroup)?.removeView(adView)
                adView
            },
        )
    }
    DisposableEffect(adView) {
        onDispose { (adView.parent as? ViewGroup)?.removeView(adView) }
    }
}


/**
 * Shimmer placeholder for a banner whose ad area is [heightDp] tall. Laid out exactly like a loaded
 * banner (1dp divider on top of the ad area), so swapping to the real banner does not move layout.
 * Colors come from [colors].
 */
@Composable
fun DefaultBannerAdLoading(
    heightDp: Int = DEFAULT_BANNER_LOADING_HEIGHT_DP,
    colors: BannerLoadingColors = BannerLoadingColors(),
) {
    val shimmer = rememberShimmerState()
    Column(modifier = Modifier.fillMaxWidth().background(colors.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.coerceAtLeast(1).dp)
                .shimmer(
                    state = shimmer,
                    visible = true,
                    shape = RoundedCornerShape(2.dp),
                    highlightColor = colors.highlight,
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.placeholder)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.placeholder)
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.placeholder)

                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(width = 60.dp, height = 30.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.placeholder)
                )
            }
        }
    }
}


@Composable
fun DefaultLargeBannerAdLoading(
    heightDp: Int = 120,
    colors: BannerLoadingColors = BannerLoadingColors(),
) {
    val shimmer = rememberShimmerState()
    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().background(colors.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.coerceAtLeast(1).dp)
                .shimmer(
                    state = shimmer,
                    visible = true,
                    shape = RoundedCornerShape(2.dp),
                    highlightColor = colors.highlight,
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .align(Alignment.Center),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .padding(vertical = 8.dp)
                        .aspectRatio(1f)
                        .background(colors.placeholder)
                        .height(height = heightDp.pxToDp())
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.placeholder)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.placeholder)

                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .align(AbsoluteAlignment.Right)
                            .fillMaxWidth(0.6f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.placeholder)
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun DefaultBannerAdLoadingPreview() {
    DefaultBannerAdLoading()
}

@Preview(showBackground = true)
@Composable
private fun DefaultLargeBannerAdLoadingPreview() {
    DefaultLargeBannerAdLoading()
}
