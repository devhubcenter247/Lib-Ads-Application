package com.lib.ads.gma.ads.helper.adnative.api

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import com.lib.ads.gma.ads.helper.adnative.params.LocalNativeLoadingColors
import com.lib.ads.gma.ads.helper.adnative.params.NativeLoadingColors
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView


val LocalNativeAdView = staticCompositionLocalOf<NativeAdView?> { null }
val LocalApNativeAd = staticCompositionLocalOf<ApNativeAd?> { null }
internal val LocalNativeAdMediaViewSetter = staticCompositionLocalOf<(MediaView?) -> Unit> { {} }

private class NativeAdBindingState {
    var boundNativeAd: NativeAd? = null
    var pendingNativeAd: NativeAd? = null
    var mediaView: MediaView? = null
}

@Composable
fun NativeAdView(
    nativeAd: ApNativeAd,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalInspectionMode.current) {
        CompositionLocalProvider(
            LocalApNativeAd provides nativeAd,
            LocalNativeAdView provides null,
        ) {
            Box(modifier = modifier) { content() }
        }
        return
    }

    val currentContent by rememberUpdatedState(content)
    val nativeAdState = remember { mutableStateOf(nativeAd) }
    val bindingState = remember { NativeAdBindingState() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val adView = NativeAdView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val composeView = ComposeView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    CompositionLocalProvider(
                        LocalNativeAdView provides adView,
                        LocalApNativeAd provides nativeAdState.value,
                        LocalNativeAdMediaViewSetter provides { mv -> bindingState.mediaView = mv },
                    ) { currentContent() }
                }
            }
            adView.addView(composeView)
            adView
        },
        update = { adView ->
            if (nativeAdState.value !== nativeAd) {
                nativeAdState.value = nativeAd
            }
            val nativeAd = nativeAd.nativeAd
            if (bindingState.boundNativeAd !== nativeAd
                && bindingState.pendingNativeAd !== nativeAd
            ) {
                bindingState.pendingNativeAd = nativeAd
                adView.post {
                    if (bindingState.pendingNativeAd !== nativeAd) return@post
                    if (!adView.isAttachedToWindow) return@post
                    runCatching {
                        if (nativeAd != null) {
                            // mediaView is nullable on the SDK side — layouts without a
                            // NativeAdMediaView must still register so click/impression tracking
                            // and the other asset views (headline, CTA, ...) get wired up.
                            adView.registerNativeAd(nativeAd, bindingState.mediaView)
                            bindingState.boundNativeAd = nativeAd
                        }
                    }
                }
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            bindingState.boundNativeAd = null
            bindingState.pendingNativeAd = null
            bindingState.mediaView = null
        }
    }
}

@Composable
fun NativeAdAdvertiserView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) { content(ad?.nativeAd?.advertiser ?: "Sample Advertiser") }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.advertiserView = view
            val advertiser = ad?.nativeAd?.advertiser
            if (advertiser != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            advertiser
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdBodyView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) {
            content(
                ad?.nativeAd?.body
                    ?: "This is a sample ad body text that describes the app or service being advertised."
            )
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.bodyView = view
            val body = ad?.nativeAd?.body
            if (body != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            body
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdCallToActionView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) { content(ad?.nativeAd?.callToAction ?: "Install") }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.callToActionView = view
            val cta = ad?.nativeAd?.callToAction
            if (cta != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            cta
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdHeadlineView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) { content(ad?.nativeAd?.headline ?: "Sample Ad Headline") }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.headlineView = view
            val headline = ad?.nativeAd?.headline
            if (headline != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            headline
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdIconView(modifier: Modifier = Modifier, content: @Composable (Drawable) -> Unit) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.LightGray), contentAlignment = Alignment.Center) {
            Text("Icon", fontSize = 8.sp, color = Color.Gray)
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    val drawable = ad?.nativeAd?.icon?.drawable
    if (drawable != null) {
        AndroidView(
            factory = { context -> ComposeView(context) },
            modifier = modifier,
            update = { view ->
                nativeAdView.iconView = view
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            drawable
                        )
                    }
                }
            },
        )
    } else {
        SideEffect { nativeAdView.iconView = null }
    }
}

@Composable
fun NativeAdMediaView(modifier: Modifier = Modifier, scaleType: ImageView.ScaleType? = null) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.Gray), contentAlignment = Alignment.Center) {
            Text("Media View", color = Color.White)
        }
        return
    }
    val setMediaView = LocalNativeAdMediaViewSetter.current
    AndroidView(
        factory = { context ->
            MediaView(context).also { mv -> setMediaView(mv) }
        },
        modifier = modifier,
        update = { view ->
            scaleType?.let { type -> runCatching { view.imageScaleType = type } }
        },
    )
}

@Composable
fun NativeAdPriceView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) { content(ad?.nativeAd?.price ?: "Free") }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.priceView = view
            val price = ad?.nativeAd?.price
            if (price != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            price
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdStarRatingView(modifier: Modifier = Modifier, content: @Composable (Double) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) { content(ad?.nativeAd?.starRating ?: 4.5) }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.starRatingView = view
            val rating = ad?.nativeAd?.starRating
            if (rating != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) {
                        content(
                            rating
                        )
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun AdBadge(
    text: String = "Ad",
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle()
) {
    Text(text = text, modifier = modifier, style = textStyle)
}

@Composable
fun NativeAdAttribution(
    modifier: Modifier = Modifier,
    text: String = "Ad",
    shape: Shape = ButtonDefaults.shape,
    containerColor: Color = ButtonDefaults.buttonColors().containerColor,
    contentColor: Color = ButtonDefaults.buttonColors().contentColor,
    padding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
) {
    Box(
        modifier = modifier
            .background(containerColor, shape)
            .padding(padding)
    ) {
        Text(color = contentColor, text = text)
    }
}

@Composable
fun NativeAdButton(
    text: String,
    modifier: Modifier = Modifier,
    shape: Shape = ButtonDefaults.shape,
    containerBrush: Brush = Brush.linearGradient(),
    contentColor: Color = ButtonDefaults.buttonColors().contentColor,
    padding: PaddingValues = ButtonDefaults.ContentPadding,
    textStyle: TextStyle = TextStyle()
) {
    Box(
        modifier = modifier
            .background(brush = containerBrush, shape)
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Text(color = contentColor, text = text, style = textStyle)
    }
}

@Composable
fun NativeIconContent(modifier: Modifier = Modifier) {
    NativeAdIconView(
        modifier = Modifier
            .size(48.dp)
            .then(modifier)
    ) { drawable ->
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun NativeHeadlineRow(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(),
    badgeColor: Color = Color(0xFF939393),
    badgeText: String = "Ad",
    badgeTextStyle: TextStyle = TextStyle()
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AdBadge(
            text = badgeText,
            modifier = Modifier.background(badgeColor, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp),
            textStyle = badgeTextStyle
        )
        NativeAdHeadlineView(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        ) { headline ->
            Text(
                text = headline,
                style = textStyle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NativeHeadlineRowSmall(
    modifier: Modifier = Modifier, badgeColor: Color = Color.White,
    badgeText: String = "Ad",
    badgeTextStyle: TextStyle = TextStyle()
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AdBadge(
            text = badgeText,
            modifier = Modifier.background(badgeColor, RoundedCornerShape(4.dp)),
            textStyle = badgeTextStyle
        )
        NativeAdHeadlineView(modifier = Modifier.padding(start = 12.dp)) { headline ->
            Text(
                text = headline,
                style = TextStyle(
                    color = Color(0xFF171A1E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NativeBodyText(modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle()) {
    NativeAdBodyView(modifier = modifier) { body ->
        Text(text = body, style = textStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NativeCtaButton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    containerBrush: Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFCDDC39),
            Color(0xFF4CAF50)
        )
    ),
    contentColor: Color = Color.White,
    padding: PaddingValues = PaddingValues(vertical = 12.dp),
    textStyle: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
) {
    val isPreview = LocalInspectionMode.current
    NativeAdCallToActionView(modifier = modifier) { cta ->
        val ctaText = if (isPreview) "Install" else cta
        NativeAdButton(
            text = ctaText,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            containerBrush = containerBrush,
            contentColor = contentColor,
            padding = padding,
            textStyle = textStyle
        )
    }
}

@Composable
fun MetaTextSponsor(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(color = Color(0xFF80828D), fontSize = 11.sp)
) {
    Text(
        text = "Sponsor",
        style = textStyle,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// Placeholder blocks for native loading layouts. Colors default to LocalNativeLoadingColors;
// wrap the layout in Modifier.nativeLoadingShimmer(...) for the background and shimmer band.
@Composable
fun ShimmerIconCircle(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .background(colors.placeholder)
    )
}

@Composable
fun ShimmerHeadlineRow(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(colors.placeholder, RoundedCornerShape(4.dp))
                .size(24.dp, 14.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .fillMaxWidth()
                .height(14.dp)
                .background(colors.placeholder)
        )
    }
}

@Composable
fun ShimmerHeadlineRowSmall(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(colors.placeholder, RoundedCornerShape(4.dp))
                .size(24.dp, 14.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .fillMaxWidth()
                .height(15.dp)
                .background(colors.placeholder)
        )
    }
}

@Composable
fun ShimmerBodyBox(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    Box(
        modifier = modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(24.dp)
            .background(colors.placeholder)
    )
}

@Composable
fun ShimmerCtaRoundedButton(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    NativeAdButton(
        text = "",
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0),
        containerBrush = Brush.linearGradient(
            colors = listOf(
                colors.placeholder,
                colors.placeholder
            )
        ),
        contentColor = Color.White,
        padding = PaddingValues(vertical = 14.dp)
    )
}

@Composable
fun ShimmerCtaCircleButton(
    modifier: Modifier = Modifier,
    colors: NativeLoadingColors = LocalNativeLoadingColors.current,
) {
    NativeAdButton(
        text = "",
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0),
        containerBrush = Brush.linearGradient(
            colors = listOf(
                colors.placeholder,
                colors.placeholder
            )
        ),
        contentColor = Color.White,
        padding = PaddingValues(vertical = 16.dp)
    )
}
