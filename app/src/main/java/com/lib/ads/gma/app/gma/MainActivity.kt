package com.lib.ads.gma.app.gma

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.helper.adnative.NativeAdSpec
import com.lib.ads.gma.ads.helper.adnative.NativeAds
import com.lib.ads.gma.ads.helper.adnative.PreloadBufferState
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.banner.createBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.params.BannerAdConfig
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.preload.BannerAd
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdCard
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdHolder
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.app.gma.feature.FeatureActivity
import com.lib.ads.gma.app.gma.feature.FeatureScreenType
import com.lib.ads.gma.app.gma.language.LanguageActivity
import com.lib.ads.gma.app.gma.language.LanguageScreenType
import com.lib.ads.gma.app.gma.onboarding.OnboardingActivity
import com.lib.ads.gma.app.gma.splash.NativeSplashActivity
import com.lib.ads.gma.app.gma.splash.SplashActivity
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.backgroundResource
import com.lib.ads.gma.app.style.onClickRipple
import com.sdp.ssp.android.Ssp
import com.sdp.ssp.android.getScreenWidthInDp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    override fun initialize() {
        bannerAdHolder.request()
    }

    private val bannerAdHolder: BannerAdHolder by lazy {
        createBannerAdHolder(
            enabled = BuildConfig.ad_banner_collapse.isNotBlank(),
            tag = "banner_home",
            options = BannerAdPreloadHolderOptions(
                fallbackAdUnitIds = listOf(BuildConfig.ad_banner_collapse),
                lifecycleOwner = this@MainActivity,
                autoRequestOnStart = false,
                size = BannerSize.Fixed(
                    widthDp = getScreenWidthInDp().value.toInt(),
                    heightDp = 56,
                ),
                autoReloadOnResume = true,
                cancelOnPause = false,
            ),
        ).value
    }


    @Composable
    override fun BoxScope.ContentView() {
        BackHandler { finish() }
        val scope = rememberCoroutineScope()


        BannerAd(
            config = BannerAdConfig(
                listOf(),
                true,
                true,
                null,
            ).apply {
                asInlineBanner(60)
            },
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()

        )

        MainDemoScreen(
            bannerAdHolder = bannerAdHolder,
            onOpenLanguageLoading = {
                LanguageActivity.start(this@MainActivity, LanguageScreenType.LanguageLoading, false)
            },
            onOpenLanguage1 = {
                LanguageActivity.start(this@MainActivity, LanguageScreenType.Language1, false)
            },
            onOpenLanguage2 = {
                LanguageActivity.start(this@MainActivity, LanguageScreenType.Language2, false)
            },
            onOpenOnboarding = {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            },
            onOpenFeature = { screenType ->
                FeatureActivity.start(this@MainActivity, screenType)
            },
            onOpenSplash = {
                startActivity(Intent(this@MainActivity, SplashActivity::class.java))
            },
            onOpenNativeSplash = {
                NativeAds.preload(
                    NativeAdSpec.simple("n_spl", BuildConfig.ad_native_high, 1)
                )
                scope.launch(Dispatchers.Main) {
                    NativeAds.stateFlow("n_spl").collect {
                        when (it) {
                            is PreloadBufferState.Ready -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        NativeSplashActivity::class.java
                                    )
                                )
                            }

                            else -> {}
                        }
                    }
                }

            },
            onOpenPictureInPicture = {
                startActivity(Intent(this@MainActivity, PictureInPictureActivity::class.java))
            },
            onResetFeatures = { FeatureActivity.listFeatureSelect.clear() },
        )
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
        }
    }
}

private data class DemoAction(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

@Composable
private fun MainDemoScreen(
    bannerAdHolder: BannerAdHolder? = null,
    onOpenLanguageLoading: () -> Unit,
    onOpenLanguage1: () -> Unit,
    onOpenLanguage2: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenFeature: (FeatureScreenType) -> Unit,
    onOpenSplash: () -> Unit,
    onOpenNativeSplash: () -> Unit,
    onOpenPictureInPicture: () -> Unit,
    onResetFeatures: () -> Unit,
) {
    val hazeState = rememberHazeState()
    var showInfo by remember { mutableStateOf(false) }
    val selectedCount = FeatureActivity.listFeatureSelect.size
    val actions = listOf(
        DemoAction(
            "Language loading",
            "Loading state with progress indicator",
            onOpenLanguageLoading
        ),
        DemoAction("Language 1", "First language selection screen", onOpenLanguage1),
        DemoAction("Language 2", "Second language selection screen", onOpenLanguage2),
        DemoAction("Onboarding", "Swipe through the onboarding slides", onOpenOnboarding),
        DemoAction(
            "Feature 1",
            "Single feature selection flow",
            { onOpenFeature(FeatureScreenType.Feature1) }),
        DemoAction(
            "Feature 2",
            "Feature screen variation 2",
            { onOpenFeature(FeatureScreenType.Feature2) }),
        DemoAction("Splash", "Splash screen with app navigation", onOpenSplash),
        DemoAction("Native splash", "Native splash loading state", onOpenNativeSplash),
        DemoAction(
            "Picture-in-picture ad",
            "Floating ad that stays above app content",
            onOpenPictureInPicture
        ),
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .backgroundResource(R.drawable.img_bg_splash)
                .hazeSource(hazeState),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
        ) {
            Text(
                text = "GMA Demo Center",
                style = headline600(fontSize = 22.Ssp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Explore screens, states, transitions, and UI components",
                style = headline400(fontSize = 12.Ssp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(hazeState),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "Selected features: $selectedCount",
                        style = headline600(fontSize = 14.Ssp),
                    )
                    Text(
                        text = "Use this hub to test every GMA screen independently.",
                        style = headline400(fontSize = 11.Ssp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = { showInfo = !showInfo },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(if (showInfo) "Hide demo info" else "Show demo info")
                    }
                    if (showInfo) {
                        Text(
                            text = "Language and feature choices are kept in memory for this demo session.",
                            style = headline400(fontSize = 11.Ssp),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(actions, key = { it.title }) { action ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .hazeEffect(hazeState)
                            .onClickRipple(onClick = action.onClick),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(action.title, style = headline600(fontSize = 14.Ssp))
                            Text(
                                action.description,
                                style = headline400(fontSize = 11.Ssp),
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onResetFeatures,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Reset demo selection")
            }
            BannerAdCard(holder = bannerAdHolder)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainDemoScreenPreview() {
    AppViewTheme {
        MainDemoScreen(
            onOpenLanguageLoading = {},
            onOpenLanguage1 = {},
            onOpenLanguage2 = {},
            onOpenOnboarding = {},
            onOpenFeature = {},
            onOpenSplash = {},
            onOpenNativeSplash = {},
            onOpenPictureInPicture = {},
            onResetFeatures = {},
        )
    }
}
