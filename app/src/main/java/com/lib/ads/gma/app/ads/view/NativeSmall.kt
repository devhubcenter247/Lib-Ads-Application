package com.lib.ads.gma.app.ads.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.c014.ai.art.style.PhonePreview
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdView
import com.lib.ads.gma.ads.helper.adnative.api.NativeHeadlineRow
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.app.style.lightGrey
import com.lib.ads.gma.app.style.white
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer
import com.sdp.ssp.android.Sdp

@Composable
fun NativeSmallCtaBot(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier
) {
    NativeAdView(
        nativeAd = nativeAdView,
        modifier = modifier.background(NativeAdColors.colorBgNativeAds)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 12.Sdp)
            ) {
                NativeIconContent()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.Sdp)
                ) {
                    NativeHeadlineRow(
                        textStyle = TextStyle(
                            color = NativeAdColors.headline,
                        )
                    )
                    NativeBodyText()
                }
            }
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 10.Sdp)
                    .padding(bottom = 12.Sdp)
            )
        }
    }
}

@Composable
fun NativeSmallCtaTop(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier
) {
    NativeAdView(
        nativeAd = nativeAdView,
        modifier = modifier.background(NativeAdColors.colorBgNativeAds)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.Sdp)
        ) {
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(vertical = 12.Sdp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
            ) {
                NativeIconContent()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.Sdp)
                ) {
                    NativeHeadlineRow(
                        textStyle = TextStyle(
                            color = NativeAdColors.headline,
                        )
                    )
                    NativeBodyText()
                }
            }
        }
    }
}

@Composable
fun NativeSmallCtaRight(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier
) {
    NativeAdView(
        nativeAd = nativeAdView,
        modifier = modifier.background(NativeAdColors.colorBgNativeAds)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.Sdp, bottom = 10.Sdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(4.8f)
                    .padding(start = 12.Sdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NativeIconContent()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.Sdp)
                ) {
                    NativeHeadlineRow(
                        textStyle = TextStyle(
                            color = NativeAdColors.headline,
                        )
                    )
                    NativeBodyText()
                }
            }
            NativeCtaButton(
                modifier = Modifier
                    .weight(2.2f)
                    .padding(horizontal = 12.Sdp),
                isMetaLow = false
            )
        }
    }
}

@Composable
fun ShimmerSmallCtaBotView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.Sdp)
                .padding(top = 12.Sdp)
        ) {
            ShimmerIconCircle()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.Sdp)
            ) {
                ShimmerHeadlineRow()
                ShimmerBodyBox()
            }
        }
        ShimmerCtaRoundedButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(top = 10.Sdp)
                .padding(bottom = 12.Sdp)
        )
    }
}

@Composable
fun ShimmerSmallCtaTopView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
    ) {
        ShimmerCtaRoundedButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(vertical = 12.Sdp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.Sdp)
                .padding(bottom = 10.Sdp)
        ) {
            ShimmerIconCircle()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.Sdp)
            ) {
                ShimmerHeadlineRow()
                ShimmerBodyBox()
            }
        }
    }
}

@Composable
fun ShimmerSmallCtaRightView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
            .padding(top = 12.Sdp, bottom = 10.Sdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(4.8f)
                .padding(start = 12.Sdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerIconCircle()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.Sdp)
            ) {
                ShimmerHeadlineRow()
                ShimmerBodyBox()
            }
        }
        Box(
            modifier = Modifier
                .weight(2.2f)
                .padding(horizontal = 12.Sdp)
                .height(38.dp)
                .background(NativeAdColors.shimmer, RoundedCornerShape(0.dp))
        )
    }
}

@PhonePreview
@Composable
fun NativeSmallCtaBotPreview() {
    Column(
        Modifier
            .fillMaxSize()
            .background(lightGrey)
    ) {
        NativeSmallCtaBot(
            nativeAdView = ApNativeAd(), modifier = Modifier.background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeSmallCtaBot(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white)
        )
        Spacer(Modifier.height(12.Sdp))
        NativeSmallCtaBot(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white)
        )
    }
}

@PhonePreview
@Composable
fun NativeSmallCtaTopPreview() {
    Column(
        Modifier
            .fillMaxSize()
            .background(lightGrey)
    ) {
        NativeSmallCtaTop(
            nativeAdView = ApNativeAd(), modifier = Modifier.background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeSmallCtaTop(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white)
        )
        Spacer(Modifier.height(12.Sdp))
        NativeSmallCtaTop(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white)
        )
    }

}

@Preview(showBackground = true)
@Composable
fun NativeSmallCtaRightPreview() {
    NativeSmallCtaRight(nativeAdView = ApNativeAd())
}

@Composable
fun ShimmerSmallCtaBotPreview() {
    ShimmerSmallCtaBotView(isShowShimmer = true)
}

@Preview(showBackground = true)
@Composable
fun ShimmerSmallCtaTopPreview() {
    ShimmerSmallCtaTopView(isShowShimmer = true)
}

@Preview(showBackground = true)
@Composable
fun ShimmerSmallCtaRightPreview() {
    ShimmerSmallCtaRightView(isShowShimmer = true)
}
