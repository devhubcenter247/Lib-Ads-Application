package com.lib.ads.gma.app.ads.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.c014.ai.art.style.PhonePreview
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdMediaView
import com.lib.ads.gma.ads.helper.adnative.api.NativeAdView
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.app.style.lightGrey
import com.lib.ads.gma.app.style.paddingBottom
import com.lib.ads.gma.app.style.white
import com.lib.ads.gma.compose.rememberShimmerState
import com.lib.ads.gma.compose.shimmer
import com.sdp.ssp.android.Sdp


@Composable
fun ShimmerNativeFullScreenAd(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Box(
        modifier = modifier
            .fillMaxSize()
            .shimmer(state = shimmerState, visible = isShowShimmer)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(white)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.Sdp, bottom = 10.Sdp)
                .align(Alignment.BottomCenter), verticalAlignment = Alignment.CenterVertically
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
                    .height(40.dp)
                    .background(lightGrey, RoundedCornerShape(28.dp))
            )
        }
    }
}

@Composable
fun NativeFullScreenAd(
    nativeAdView: ApNativeAd, modifier: Modifier = Modifier
) {
    NativeAdView(
        nativeAd = nativeAdView, modifier = modifier.background(NativeAdColors.backgroundFull)
    ) {
        NativeAdMediaView(
            modifier = Modifier.fillMaxSize()
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
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
                    NativeHeadlineRow()
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
fun NativeMediumCtaTop(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier
) {
    NativeAdView(
        nativeAd = nativeAdView, modifier = modifier.background(NativeAdColors.colorBgNativeAds)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .paddingBottom(10.Sdp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 14.Sdp)
            )
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
                    NativeHeadlineRow()
                    NativeBodyText()
                }
            }
            NativeAdMediaView(
                modifier = Modifier
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 8.Sdp)
                    .wrapContentWidth()
                    .height(130.dp)
                    .defaultMinSize(minWidth = 130.dp, minHeight = 130.dp),
            )
        }
    }
}

@Composable
fun NativeMediumCtaBottom(
    nativeAdView: ApNativeAd,
    modifier: Modifier = Modifier,
) {
    NativeAdView(
        nativeAd = nativeAdView, modifier = modifier.background(NativeAdColors.colorBgNativeAds)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .paddingBottom(12.Sdp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                    NativeHeadlineRow()
                    NativeBodyText()
                }
            }
            NativeAdMediaView(
                modifier = Modifier
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 10.Sdp)
                    .wrapContentWidth()
                    .height(130.dp)
                    .defaultMinSize(minWidth = 130.dp, minHeight = 130.dp),
            )
            NativeCtaButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.Sdp)
                    .padding(top = 10.Sdp)
            )
        }
    }
}

@Composable
fun ShimmerMediumCtaTopView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
            .padding(bottom = 10.Sdp)
    ) {
        ShimmerCtaRoundedButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(top = 12.Sdp)
        )
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 12.Sdp)
                .padding(top = 10.Sdp)
                .background(NativeAdColors.shimmer)
        )
    }
}

@Composable
fun ShimmerMediumCtaBottomView(isShowShimmer: Boolean, modifier: Modifier = Modifier) {
    val shimmerState = rememberShimmerState(900)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(state = shimmerState, visible = isShowShimmer)
            .padding(bottom = 12.Sdp)
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(horizontal = 12.Sdp)
                .padding(top = 10.Sdp)
                .background(NativeAdColors.shimmer)
        )
        ShimmerCtaRoundedButton(
            modifier = Modifier
                .padding(horizontal = 12.Sdp)
                .padding(top = 10.Sdp)
        )
    }
}

@PhonePreview
@Composable
fun NativeMediumCtaTopPreview() {
    Column(
        Modifier
            .fillMaxSize()
            .background(lightGrey)
    ) {
        NativeMediumCtaTop(
            nativeAdView = ApNativeAd(), modifier = Modifier.background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediumCtaTop(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white),
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediumCtaTop(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white),
        )
    }

}

@PhonePreview
@Composable
fun NativeMediumCtaBottomPreview() {
    Column(
        Modifier
            .fillMaxSize(1f)
            .background(lightGrey)
    ) {
        NativeMediumCtaBottom(
            nativeAdView = ApNativeAd(), modifier = Modifier.background(
                white
            )
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediumCtaBottom(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white),
        )
        Spacer(Modifier.height(12.Sdp))
        NativeMediumCtaBottom(
            nativeAdView = ApNativeAd(),
            modifier = Modifier.background(white),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ShimmerMediumCtaTopPreview() {
    ShimmerMediumCtaTopView(isShowShimmer = true)
}

@Preview(showBackground = true)
@Composable
fun ShimmerMediumCtaBottomPreview() {
    ShimmerMediumCtaBottomView(isShowShimmer = true)
}

@PhonePreview
@Composable
fun NativeFullScreenAdPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(white)
    ) {
        NativeFullScreenAd(nativeAdView = ApNativeAd())
    }
}

@PhonePreview
@Composable
fun ShimmerNativeFullScreenAdPreview() {
    Box(
        Modifier
            .fillMaxSize()
            .background(white)
    ) {
        ShimmerNativeFullScreenAd(isShowShimmer = true)
    }
}
