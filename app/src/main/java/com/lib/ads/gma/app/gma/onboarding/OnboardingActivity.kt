package com.lib.ads.gma.app.gma.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.helper.utils.AdOptionVisibility
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdCard
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdHolderConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.createNativeAdHolder
import com.lib.ads.gma.app.ads.view.NativeMediaLeftCtaBot
import com.lib.ads.gma.app.ads.view.ShimmerMediaLeftComposeView
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.app.gma.feature.FeatureActivity
import com.lib.ads.gma.app.gma.feature.FeatureScreenType
import com.lib.ads.gma.app.gma.headline400
import com.lib.ads.gma.app.gma.headline500
import com.lib.ads.gma.app.gma.headline600
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.backgroundResource
import com.lib.ads.gma.app.style.paddingHorizontal
import com.lib.ads.gma.app.style.paddingVertical
import com.lib.ads.gma.app.style.white
import com.sdp.ssp.android.Sdp
import com.sdp.ssp.android.Ssp
import kotlinx.coroutines.launch

class OnboardingActivity : BaseActivity() {
    override fun initialize() {
        NativeAds.safePreload(
            NativeAdSpec.simple("welcome", BuildConfig.ad_native_high, bufferSize = 2)
        )
    }

    val nativeAdHolder: NativeAdHolderConfig by createNativeAdHolder(
            enabled = BuildConfig.ad_native.isNotBlank(),
            tag = "ob",
            options = NativeAdPreloadHolderOptions(
                adOptionVisibility = AdOptionVisibility.INVISIBLE,
                lifecycleOwner = this@OnboardingActivity,
                autoReloadOnResume = false,
                cancelOnPause = true,
            ),
        )

    @Composable
    override fun BoxScope.ContentView() {
        val pages = setupOnboardingPage().filterIsInstance<OnboardingPage.Slide>()
        OnboardingScreen(pages = pages, nativeAdHolder, onBack = { finish() }) { isLastPage ->
            if (isLastPage) {
                FeatureActivity.start(
                    this@OnboardingActivity,
                    FeatureScreenType.Feature1
                )
                finish()
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    pages: List<OnboardingPage.Slide>,
    nativeAdHolder: NativeAdHolderConfig? = null,
    onBack: () -> Unit,
    onContinue: (isLastPage: Boolean) -> Unit,
) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val item = pages[pagerState.currentPage]

    LaunchedEffect(pagerState.currentPage, nativeAdHolder) {

        if (pagerState.currentPage == 0 || pagerState.currentPage == 2) {
            nativeAdHolder?.cancel()
        } else {
            nativeAdHolder?.reload()
        }
    }
    BackHandler {
        if (pagerState.currentPage == 0) onBack() else scope.launch {
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .backgroundResource(R.drawable.img_bg_splash)
        ) { page ->
            val pageItem = pages[page]

            Image(
                painter = painterResource(pageItem.image),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(1f),
            )

        }
        Column(
            modifier = Modifier
                .wrapContentHeight()

                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(item.title),
                style = headline600(),
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(16.Sdp)
                    .padding(top = 16.dp)
            )
            Text(
                stringResource(item.content),
                style = headline400(),
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(16.Sdp)
                    .padding(top = 8.dp)
            )
            Button(
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) {
                        onContinue(true)
                    } else {
                        onContinue(false)
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(16.Sdp)
                    .padding(bottom = 16.dp)
                    .padding(top = 24.dp),
            ) {
                Text(
                    stringResource(if (pagerState.currentPage == pages.lastIndex) R.string.continues else R.string.next),
                    modifier = Modifier.paddingVertical(8.Sdp),
                    style = headline500(white),
                    color = white,
                    fontSize = 12.Ssp
                )
            }
            NativeAdCard(
                holder = nativeAdHolder,
                modifier = Modifier
                    .padding(top = 12.Sdp)
                    .fillMaxWidth(),
                loading = {
                    ShimmerMediaLeftComposeView(isShowShimmer = it)
                },
                nativeView = { ad ->
                    NativeMediaLeftCtaBot(
                        nativeAdView = ad,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

        }
    }
}

@Preview(showBackground = true, showSystemUi = false)
@Composable
private fun OnboardingScreenPreview() {
    AppViewTheme {
        OnboardingScreen(
            pages = setupOnboardingPage().filterIsInstance<OnboardingPage.Slide>(),
            onBack = {},
        ) {}
    }
}
