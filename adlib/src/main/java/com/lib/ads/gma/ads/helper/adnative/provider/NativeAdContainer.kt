package com.lib.ads.gma.ads.helper.adnative.provider

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isGone
import com.google.android.gms.ads.nativead.AdChoicesView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.lib.ads.gma.ads.ads.wrapper.ApNativeAd

// --- compose ads
internal val LocalNativeAdView = staticCompositionLocalOf<NativeAdView?> { null }
val LocalApNativeAd = staticCompositionLocalOf<ApNativeAd?> { null }

private class NativeAdBindingState {
    var boundAdmobNativeAd: NativeAd? = null
    var pendingAdmobNativeAd: NativeAd? = null
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
            Box(modifier = modifier) {
                content()
            }
        }
        return
    }

    val currentContent by rememberUpdatedState(content)
    val nativeAdState = remember {
        mutableStateOf(nativeAd)
    }
    val bindingState = remember {
        NativeAdBindingState()
    }
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
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                setContent {
                    CompositionLocalProvider(
                        LocalNativeAdView provides adView,
                        LocalApNativeAd provides nativeAdState.value,
                    ) {
                        currentContent()
                    }
                }
            }
            adView.addView(composeView)
            adView
        },
        update = { adView ->
            if (nativeAdState.value !== nativeAd) {
                nativeAdState.value = nativeAd
            }
            val admobNativeAd = nativeAd.admobNativeAd
            if (bindingState.boundAdmobNativeAd !== admobNativeAd
                && bindingState.pendingAdmobNativeAd !== admobNativeAd
            ) {
                bindingState.pendingAdmobNativeAd = admobNativeAd

                val bindAd = {
                    if (bindingState.pendingAdmobNativeAd !== admobNativeAd) {
                        Unit
                    } else {
                        runCatching {
                            if (admobNativeAd != null) {
                                adView.setNativeAd(admobNativeAd)
                                bindingState.boundAdmobNativeAd = admobNativeAd
                            } else {
                                adView.removeAllViews()
                                adView.isGone = true
                            }
                        }
                        bindingState.pendingAdmobNativeAd = null
                    }
                }

                if (adView.isAttachedToWindow) {
                    adView.post { bindAd() }
                } else {
                    adView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            view.removeOnAttachStateChangeListener(this)
                            adView.post { bindAd() }
                        }

                        override fun onViewDetachedFromWindow(view: View) = Unit
                    })
                }
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            bindingState.boundAdmobNativeAd = null
            bindingState.pendingAdmobNativeAd = null
        }
    }
}

@Composable
fun NativeAdAdvertiserView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.advertiser ?: "Sample Advertiser")
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.advertiserView = view
            val advertiser = ad?.admobNativeAd?.advertiser
            if (advertiser != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(advertiser) }
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
                ad?.admobNativeAd?.body
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
            val body = ad?.admobNativeAd?.body
            if (body != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(body) }
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
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.callToAction ?: "Install")
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.callToActionView = view
            val cta = ad?.admobNativeAd?.callToAction
            if (cta != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(cta) }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdChoicesView(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .size(15.dp)
                .background(Color.LightGray)
        )
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context ->
            AdChoicesView(context).apply {
                minimumWidth = 15
                minimumHeight = 15
            }
        },
        modifier = modifier,
        update = { view -> nativeAdView.adChoicesView = view },
    )
}

@Composable
fun NativeAdHeadlineView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.headline ?: "Sample Ad Headline")
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.headlineView = view
            val headline = ad?.admobNativeAd?.headline
            if (headline != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(headline) }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdIconView(modifier: Modifier = Modifier, content: @Composable (NativeAd.Image) -> Unit) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.LightGray), contentAlignment = Alignment.Center) {
            Text("Icon", fontSize = 8.sp, color = Color.Gray)
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    val icon = ad?.admobNativeAd?.icon
    if (icon != null) {
        AndroidView(
            factory = { context -> ComposeView(context) },
            modifier = modifier,
            update = { view ->
                nativeAdView.iconView = view
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(icon) }
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
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> MediaView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.mediaView = view
            scaleType?.let { type -> view.setImageScaleType(type) }
        },
    )
}

@Composable
fun AdBadge(
    text: String = "Ad",
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle()
) {
    Text(
        text = text,
        modifier = modifier,
        style = textStyle
    )
}

@Composable
fun NativeAdPriceView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.price ?: "Free")
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.priceView = view
            val price = ad?.admobNativeAd?.price
            if (price != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(price) }
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
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.starRating ?: 4.5)
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.starRatingView = view
            val rating = ad?.admobNativeAd?.starRating
            if (rating != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(rating) }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
}

@Composable
fun NativeAdStoreView(modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (LocalInspectionMode.current) {
        val ad = LocalApNativeAd.current
        Box(modifier = modifier) {
            content(ad?.admobNativeAd?.store ?: "Google Play")
        }
        return
    }
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    val ad = LocalApNativeAd.current
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.storeView = view
            val store = ad?.admobNativeAd?.store
            if (store != null) {
                view.visibility = View.VISIBLE
                view.setContent {
                    CompositionLocalProvider(LocalApNativeAd provides ad) { content(store) }
                }
            } else {
                view.visibility = View.GONE
            }
        },
    )
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
    containerColor: Color = ButtonDefaults.buttonColors().containerColor,
    contentColor: Color = ButtonDefaults.buttonColors().contentColor,
    padding: PaddingValues = ButtonDefaults.ContentPadding,
    textStyle: TextStyle = TextStyle()
) {
    Box(
        modifier = modifier
            .background(containerColor, shape)
            .padding(padding), contentAlignment = Alignment.Center
    ) {
        Text(color = contentColor, text = text, style = textStyle)
    }
}

@Composable
fun NativeIconContent(modifier: Modifier = Modifier) {
    NativeAdIconView(modifier = Modifier.size(48.dp).then(modifier)) { icon ->
        AndroidView(
            factory = { context ->
                ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            },
            update = { it.setImageDrawable(icon.drawable) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Headline rows ─────────────────────────────────────────────────────────────

/** AdBadge + headline at _14Ssp — used by Small and Medium variants */
@Composable
fun NativeHeadlineRow(modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle()) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AdBadge()
        NativeAdHeadlineView(
            modifier = Modifier
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

/** AdBadge + headline at _11Ssp — used by MediaLeft variants */
@Composable
fun NativeHeadlineRowSmall(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AdBadge()
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

// ── Body ──────────────────────────────────────────────────────────────────────

@Composable
fun NativeBodyText(modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle()) {
    NativeAdBodyView(modifier = modifier) { body ->
        Text(
            text = body,
            style = textStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── CTA buttons ───────────────────────────────────────────────────────────────

/** Full-width rounded CTA button (Small / Medium layouts) */
@Composable
fun NativeCtaButton(
    modifier: Modifier = Modifier, isMetaLow: Boolean = false,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = Color(0xFFDFE1FE),
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
            containerColor = containerColor,
            contentColor = contentColor,
            padding = padding,
            textStyle = textStyle
        )
    }
}

@Composable
fun MetaTextSponsor(modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle(
    color = Color(0xFF80828D), fontSize = 11.sp
)) {
    Text(
        text = "Sponsor",
        style = textStyle,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// ── Shimmer placeholders ──────────────────────────────────────────────────────

@Composable
fun ShimmerIconCircle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .background(Color(0xFFF0F0F0))
    )
}

@Composable
fun ShimmerHeadlineRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))
                .size(24.dp, 14.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .fillMaxWidth()
                .height(14.dp)
                .background(Color(0xFFF0F0F0))
        )
    }
}

/** Shimmer headline row with 10.dp gap — used by MediaLeft shimmer variants */
@Composable
fun ShimmerHeadlineRowSmall(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))
                .size(24.dp, 14.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .fillMaxWidth()
                .height(15.dp)
                .background(Color(0xFFF0F0F0))
        )
    }
}

@Composable
fun ShimmerBodyBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0xFFF0F0F0))
    )
}

@Composable
fun ShimmerCtaRoundedButton(modifier: Modifier = Modifier) {
    NativeAdButton(
        text = "",
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0),
        containerColor = Color(0xFFF0F0F0),
        contentColor = Color.White,
        padding = PaddingValues(vertical = 14.dp)
    )
}

@Composable
fun ShimmerCtaCircleButton(modifier: Modifier = Modifier) {
    NativeAdButton(
        text = "",
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0),
        containerColor = Color(0xFFF0F0F0),
        contentColor = Color.White,
        padding = PaddingValues(vertical = 16.dp)
    )
}
