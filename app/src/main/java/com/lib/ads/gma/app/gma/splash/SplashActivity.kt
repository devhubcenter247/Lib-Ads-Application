package com.lib.ads.gma.app.gma.splash

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.helper.ConsentManager
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdCard
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdHolderConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.createNativeAdHolder
import com.lib.ads.gma.ads.helper.banner.BannerAdSpec
import com.lib.ads.gma.ads.helper.banner.BannerAds
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.utils.BannerCollapseGravity
import com.lib.ads.gma.ads.manager.AppOpenAdManager
import com.lib.ads.gma.ads.manager.InterstitialAdManager
import com.lib.ads.gma.ads.model.wrapper.ApAdError
import com.lib.ads.gma.ads.model.wrapper.ApNativeAd
import com.lib.ads.gma.ads.model.wrapper.AppOpenAdListener
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener
import com.lib.ads.gma.ads.model.wrapper.NativeAdListener
import com.lib.ads.gma.app.ads.view.NativeMediumCtaTop
import com.lib.ads.gma.app.ads.view.ShimmerMediumCtaTopView
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.app.gma.MainActivity
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.backgroundResource
import com.lib.ads.gma.app.style.opacity
import com.lib.ads.gma.app.style.paddingBottom
import com.lib.ads.gma.app.style.primaryColor
import com.lib.ads.gma.app.style.widthPercent
import com.sdp.ssp.android.Sdp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SplashActivity : BaseActivity() {
    private val consentManager by lazy {
        ConsentManager(this)
    }
    val nativeAdHolder: NativeAdHolderConfig by lazy {
        val holder = createNativeAdHolder(
            enabled = BuildConfig.ad_native.isNotBlank(),
            tag = "splash_native",
            options = NativeAdPreloadHolderOptions(
                fallbackAdUnitIds = listOf(BuildConfig.ad_native),
                lifecycleOwner = this@SplashActivity,
                autoReloadOnResume = false,
                cancelOnPause = true,
            ),
        ).value
        holder.registerAdCallback(object : NativeAdListener {
            override fun onLoaded(ad: ApNativeAd) {
                Log.d("SplashNativeAd", "onLoaded")
            }

            override fun onImpression(ad: ApNativeAd) {
                Log.d("SplashNativeAd", "onImpression")
                /*   lifecycleScope.launch {
                       delay(2500.milliseconds)
                       onNextAction(true)
                   }*/
            }

            override fun onFailed(error: ApAdError) {
                Log.d("SplashNativeAd", "onFailed: ${error.message}")
                // onNextAction(true)
            }

            override fun onClicked(ad: ApNativeAd) {
                Log.d("SplashNativeAd", "onClicked")
            }

            override fun onPaid(adValue: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                Log.d("SplashNativeAd", "onPaid: ${adValue.valueMicros}")
            }
        })
        holder
    }

    override fun initialize() {
        lifecycleScope.launch(Dispatchers.Main) {
            val canRequest = requestUmp()
            if (canRequest) {
                Log.d("SplashRequest", "Waiting for GMA SDK initialized in Application.onCreate")
                Ads.getInstance().awaitReady { isSuccess ->
                    if (isSuccess) {
                        Log.d("SplashRequest", "GMA SDK ready; starting native splash request")
                        initAds()
                    } else {
                        Log.e("SplashRequest", "GMA SDK initialization timeout")
                        onNextAction(true)
                    }
                }
            } else {
                Log.d("SplashRequest", "Launch onNext")
                onNextAction(true)
            }
        }
    }

    /**
     * Warms the SDK-managed native/banner buffers so the next screen can consume an ad instantly.
     *
     * Both go through a placement-tag layer ([NativeAds]/[NativeAdSpec] and [BannerAds]/[BannerAdSpec])
     * instead of calling `NativeAdManager`/`BannerAdManager` directly: they track buffer state per
     * placement tag ([TAG_NEXT_SCREEN_NATIVE], [TAG_NEXT_SCREEN_BANNER]) rather than per raw ad unit
     * id, so a screen that consumes this preload can inspect its own tag's state
     * (`NativeAds.stateFlow`/`readyAdUnitId`, `BannerAds.stateFlow`/`readyAdUnitId`) without that
     * getting mixed up with any other placement that happens to reuse the same ad unit id(s).
     */
    private fun preloadForNextScreens() {
        if (BuildConfig.ad_native.isNotBlank()) {
            NativeAds.preload(
                NativeAdSpec(
                    tag = TAG_NEXT_SCREEN_NATIVE,
                    adUnitIds = listOf(BuildConfig.ad_native),
                    bufferSize = 1,
                ),
            )
        }

        if (BuildConfig.ad_banner.isNotBlank()) {
            BannerAds.preload(
                BannerAdSpec(
                    tag = TAG_NEXT_SCREEN_BANNER,
                    adUnitIds = listOf(BuildConfig.ad_banner),
                    size = BannerSize.LargePortraitAdaptive,
                    collapsibleGravity = BannerCollapseGravity.BOTTOM,
                ),
            )
        }
    }

    fun preloadInterSplashConfig(
        context: Context, onInterReady: () -> Unit = {},
        onNextAction: () -> Unit = {}
    ) {
        InterstitialAdManager.loadSplashListAds(
            listId = listOf(BuildConfig.ad_interstitial_splash),
            timeOut = 20000,
            timeDelay = 1000,
            listener = object : InterstitialAdListener {
                override fun onReady() {
                    super.onReady()
                    Log.d("ta", "loadInterSplash - onAdSplashReady")
                    onInterReady()
                }

                // loadSplashListAds calls this directly (not onFailed/onFailedToShow) when
                // canRequestFullScreenAds() is false (offline / no consent / purchased) or when
                // the timeout Runnable fires with no ad ready — without this override it silently
                // no-ops on the interface default, leaving the splash screen stuck forever.
                override fun onNextAction() {
                    super.onNextAction()
                    Log.d("ta", "loadInterSplash - onNextAction (no ad / not requestable)")
                    onNextAction()
                }

                override fun onFailed(error: ApAdError) {
                    super.onFailed(error)
                    Log.e("zzzzzz", "loadInterSplash - onAdFailedToLoad: ${error?.message}")
                    onNextAction()
                }

                override fun onFailedToShow(error: ApAdError) {
                    super.onFailedToShow(error)
                    Log.e("zzzzzz", "loadInterSplash - onAdFailedToShow: ${error?.message}")
                    onNextAction()
                }
            }
        )
    }


    fun preloadOpenAdSplashConfig(
        context: Context, onInterReady: () -> Unit = {},
        onNextAction: () -> Unit = {}
    ) {
        AppOpenAdManager.loadSplashAppOpenAds(
            ids = listOf(BuildConfig.ads_open_app),
            timeOut = 20000,
            timeDelay = 1000,
            listener = object : AppOpenAdListener {
                override fun onReady() {
                    super.onReady()
                    Log.d("ta", "loadInterSplash - onAdSplashReady")
                    onInterReady()
                }

                // See the matching InterstitialAdListener override above: the manager calls this
                // directly (not onFailed/onFailedToShow) when the ad isn't requestable or the
                // splash timeout elapses with nothing loaded — must be forwarded or the splash
                // screen hangs.
                override fun onNextAction() {
                    super.onNextAction()
                    Log.d("ta", "loadInterSplash - onNextAction (no ad / not requestable)")
                    onNextAction()
                }

                override fun onFailed(error: ApAdError) {
                    super.onFailed(error)
                    Log.e("zzzzzz", "loadInterSplash - onAdFailedToLoad: ${error?.message}")
                    onNextAction()
                }

                override fun onFailedToShow(error: ApAdError) {
                    super.onFailedToShow(error)
                    Log.e("zzzzzz", "loadInterSplash - onAdFailedToShow: ${error?.message}")
                    onNextAction()
                }
            }
        )
    }


    fun initAds() {
//        preloadOpenAdSplashConfig(this@SplashActivity, {
//           AppOpenAdManager.showSplashAppOpen(
//                this@SplashActivity,
//                listener = object : AppOpenAdListener {
//                    override fun onNextAction() {
//                        super.onNextAction()
//                        onNextAction(false, awaitingSplashAd = true)
//                    }
//                })
//        }) {
//            onNextAction(false)
//        }
        preloadInterSplashConfig(applicationContext, {
            // The splash ad preloaded by loadSplashListAds()/onReady() is cached under
            // InterstitialAdManager's private SPLASH_TAG (and interstitialSplash), which only
            // onShowSplash() reads. showInterstitial(activity, tag, listener) polls
            // FullScreenAdLruCache under the *given* tag instead — with tag = "" here, nothing
            // was ever cached under that key, so it always fell straight through to
            // listener.onNextAction() and skipped the ad, even after a successful preload.
            InterstitialAdManager.onShowSplash(
                activity = this@SplashActivity,
                listener = object : InterstitialAdListener {
                    override fun onNextAction() {
                        super.onNextAction()
                        startActivity(
                            Intent(
                                this@SplashActivity,
                                MainActivity::class.java
                            )
                        )
                        finish()
                    }
                })
        }) {
            startActivity(
                Intent(
                    this@SplashActivity,
                    MainActivity::class.java
                )
            )
            finish()
        }
        preloadForNextScreens()
        nativeAdHolder.request()
    }

    suspend fun requestUmp(): Boolean {
        consentManager.requestUMP()
        return consentManager.getCanRequestAd()
    }

    fun onNextAction(isDelay: Boolean, awaitingSplashAd: Boolean = false) {
//        LanguageActivity.start(
//            this@SplashActivity,
//            LanguageScreenType.LanguageLoading,
//            animate = true,
//        )
       /* startActivity(
            Intent(
                this@SplashActivity,
                MainActivity::class.java
            )
        )
        finish()*/
    }

    @Composable
    override fun BoxScope.ContentView() {
        SplashScreen {
            NativeAdCard(
                holder = nativeAdHolder,
                modifier = Modifier.fillMaxWidth(),
                loading = {
                    ShimmerMediumCtaTopView(it)
                },
                nativeView = { ad ->
                    NativeMediumCtaTop(
                        nativeAdView = ad,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

        }
    }

    companion object {
        /** [NativeAds] placement tag for the native ad preloaded here on Splash for the next screen to consume. */
        const val TAG_NEXT_SCREEN_NATIVE = "next_screen_native"

        /** [BannerAds] placement tag for the banner preloaded here on Splash for the next screen to consume. */
        const val TAG_NEXT_SCREEN_BANNER = "next_screen_banner"
    }
}

@Composable
private fun SplashScreen(onNativeAd: @Composable () -> Unit = {}) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backgroundResource(R.drawable.img_bg_splash),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.img_logo_splash),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(18.Sdp))
                .dropShadow(
                    RoundedCornerShape(12.Sdp),
                ) {
                    color = primaryColor.opacity(20f)
                    radius = 20f
                },
        )
        Text(stringResource(R.string.app_name), modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.weight(1f))
        LinearProgressIndicator(
            trackColor = primaryColor.opacity(20f),
            color = primaryColor,
            modifier = Modifier
                .paddingBottom(8.Sdp)
                .height(4.Sdp)
                .widthPercent(60f)

        )
        onNativeAd()
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashScreenPreview() {
    AppViewTheme {
        SplashScreen {
            ShimmerMediumCtaTopView(isShowShimmer = true)
        }
    }
}
