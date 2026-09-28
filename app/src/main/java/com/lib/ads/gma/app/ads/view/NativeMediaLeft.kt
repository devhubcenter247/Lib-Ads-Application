package com.lib.ads.gma.app.ads.view

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c014.ai.art.style.PhonePreview
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdBodyView
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdMediaView
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdView
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.app.style.lightGrey
import com.lib.ads.gma.app.style.paddingBottom
import com.lib.ads.gma.app.style.paddingTop
import com.lib.ads.gma.app.style.white
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer
import com.sdp.ssp.android.Sdp

@Composable
fun NativeMediaLeftCtaBot(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier,
) {
    NativeAdView(nativeAd = nativeAdView, modifier = modifier.background(NativeAdColors.colorBgNativeAds)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .paddingBottom(12.Sdp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                NativeAdMediaView(
                    modifier = Modifier. padding(1.Sdp)
                        .size(130.dp),
                    scaleType = ImageView.ScaleType.CENTER_CROP
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.Sdp)
                        .padding(top = 10.Sdp)
                ) {
                    NativeHeadlineRowSmall()

                    NativeAdBodyView(
                        modifier = Modifier
                            .padding(top = 8.Sdp)
                            .weight(1f, fill = false)
                    ) { body ->
                        Text(
                            text = body,
                            style = TextStyle(color = NativeAdColors.body, fontSize = 11.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 12.Sdp),
                isMetaLow = false
            )
        }
    }
}

@Composable
fun NativeMediaLeftCtaTop(
    nativeAdView: ApNativeAd,
    isMeta: Boolean = false,
    isMetaLow: Boolean = false,
    modifier: Modifier = Modifier
) {
    NativeAdView(nativeAd = nativeAdView, modifier = modifier.background(NativeAdColors.colorBgNativeAds)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 12.Sdp),
                isMetaLow = isMetaLow && isMeta
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingTop(12.Sdp)
            ) {
                NativeAdMediaView(
                    modifier =Modifier. padding(1.Sdp)
                        .size(130.dp),
                    scaleType = ImageView.ScaleType.CENTER_CROP
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.Sdp)
                        .padding(top = 10.Sdp)
                ) {
                    NativeHeadlineRowSmall()
                    if (isMeta) {
                        MetaTextSponsor()
                    }
                    NativeAdBodyView(
                        modifier = Modifier
                            .padding(top = 8.Sdp)
                            .weight(1f, fill = false)
                    ) { body ->
                        Text(
                            text = body,
                            style = TextStyle(color = NativeAdColors.body, fontSize = 11.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NativeMediumMediaLeftCtaRight(
    nativeAdView: ApNativeAd,
    isMeta: Boolean = false,
    isMetaLow: Boolean = false,
    modifier: Modifier = Modifier
) {
    NativeAdView(nativeAd = nativeAdView, modifier = modifier.wrapContentHeight().background(
        NativeAdColors.colorBgNativeAds)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(1.Sdp)
        ) {
            NativeAdMediaView(
                modifier = Modifier.size(142.dp),
                scaleType = ImageView.ScaleType.CENTER_CROP
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.Sdp)
                    .wrapContentHeight()
                    .height(142.dp)
                    .paddingTop(3.dp)
            ) {
                NativeHeadlineRow()
                NativeBodyText()
                if (isMeta) {
                    MetaTextSponsor()
                }
                Spacer(Modifier.weight(1f))
                NativeCtaButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.Sdp, bottom = 6.Sdp)
                        .height(36.Sdp),
                    isMetaLow = isMetaLow && isMeta
                )
            }
        }
    }
}

@Composable
fun ShimmerMediaLeftComposeView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
            .padding(bottom = 12.Sdp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.padding(1.Sdp)
                    .size(130.dp)
                    .background(NativeAdColors.shimmer)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.Sdp)
                    .padding(top = 10.Sdp)
            ) {
                ShimmerHeadlineRowSmall()
                ShimmerBodyBox()
            }
        }
        ShimmerCtaCircleButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(top = 12.Sdp)
        )
    }
}

@Composable
fun ShimmerMediaLeftCtaTopView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
    ) {
        ShimmerCtaCircleButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(top = 12.Sdp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paddingTop(12.Sdp)
        ) {
            Box(
                modifier =Modifier. padding(1.Sdp)
                    .size(130.dp)
                    .background(NativeAdColors.shimmer)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.Sdp)
                    .padding(top = 10.Sdp)
            ) {
                ShimmerHeadlineRowSmall()
                ShimmerBodyBox()
            }
        }
    }
}

@Composable
fun ShimmerMediaLeftCtaRightView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(1.Sdp)
        ) {
            Box(
                modifier = Modifier
                    .size(142.dp)
                    .background(NativeAdColors.shimmer)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(142.dp)
                    .padding(horizontal = 10.Sdp)
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.Sdp)
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(NativeAdColors.shimmer)
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.Sdp)
                        .background(NativeAdColors.shimmer, RoundedCornerShape(5.dp))
                        .size(24.dp, 14.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.Sdp)
                        .fillMaxWidth()
                        .weight(1f)
                        .background(NativeAdColors.shimmer)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.Sdp)
                        .height(36.Sdp)
                        .background(NativeAdColors.shimmer, RoundedCornerShape(8.Sdp))
                )
            }
        }
    }
}

@PhonePreview
@Composable
fun NativeMediaLeftCtaBotPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(lightGrey)
    ) {
        NativeMediaLeftCtaBot(
            nativeAdView = ApNativeAd(), modifier =Modifier. background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediaLeftCtaTop(
            nativeAdView = ApNativeAd(), modifier =Modifier. background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediumMediaLeftCtaRight(
            nativeAdView = ApNativeAd(),
            modifier = Modifier. background(white)
        )
    }

}

@Preview(showBackground = true)
@Composable
fun ShimmerMediaLeftComposePreview() {
    ShimmerMediaLeftComposeView(isShowShimmer = true)
}

@Preview(showBackground = true)
@Composable
fun ShimmerMediaLeftCtaTopPreview() {
    ShimmerMediaLeftCtaTopView(isShowShimmer = true)
}

@Preview(showBackground = true)
@Composable
fun ShimmerMediaLeftCtaRightPreview() {
    ShimmerMediaLeftCtaRightView(isShowShimmer = true)
}
