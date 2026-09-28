package com.lib.ads.gma.app.gma.splash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdCard
import com.lib.ads.gma.ads.helper.adnative.preload.rememberNativeAdPreload
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.app.ads.view.NativeFullScreenAd
import com.lib.ads.gma.app.ads.view.ShimmerNativeFullScreenAd
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.onClickRipple
import com.lib.ads.gma.app.style.paddingHorizontal
import com.lib.ads.gma.app.style.paddingVertical
import com.lib.ads.gma.app.style.statusBarPadding
import com.lib.ads.gma.app.style.white
import com.sdp.ssp.android.Sdp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class NativeSplashActivity : BaseActivity() {
    override fun initialize() = Unit

    @Composable
    override fun BoxScope.ContentView() {
        BackHandler(enabled = true) {}
        NativeSplashScreen {
            finish()
        }
    }
}

@Composable
private fun NativeSplashScreen(
    onSkip: ()-> Unit = {}
) {
    var isDismissLoading by remember {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()
    var isShowSkip by remember { mutableStateOf(false) }
    val nativeAdHolder = rememberNativeAdPreload("n_spl", adCallback = object : NativeAdListener {
        override fun onImpression(ad: ApNativeAd) {
            super.onImpression(ad)
            scope.launch {
                delay(2000.milliseconds)
                isShowSkip = true
            }
        }

        override fun onFailed(error: ApAdError) {
            super.onFailed(error)
            onSkip()
        }
    })
    LaunchedEffect(nativeAdHolder) {
        delay(1500.milliseconds)
        isDismissLoading = true
        nativeAdHolder.request()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        NativeAdCard(
            holder = nativeAdHolder,
            modifier = Modifier.fillMaxWidth(),
            loading = {
                ShimmerNativeFullScreenAd(isShowShimmer = it)
            },
            nativeView = { ad ->
                NativeFullScreenAd(ad, modifier = Modifier.fillMaxSize())
            },
        )
        if (isShowSkip) {
            Text(
                "Skip",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(vertical = 12.Sdp, horizontal = 12.Sdp)
                    .statusBarPadding()
                    .onClickRipple{
                        onSkip()
                    }
                    .background(white, CircleShape)
                    .border(1.Sdp, ButtonDefaults.buttonColors().containerColor,CircleShape)
                    .paddingHorizontal(12.Sdp)
                    .paddingVertical(8.Sdp)
            )
        }
        if (!isDismissLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(white), contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NativeSplashScreenPreview() {
    AppViewTheme { NativeSplashScreen() }
}
