package com.lib.ads.gma.app.gma.language

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdCard
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdHolderConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.createNativeAdHolder
import com.lib.ads.gma.ads.helper.banner.params.BannerSize
import com.lib.ads.gma.ads.helper.banner.createBannerAdHolder
import com.lib.ads.gma.ads.helper.banner.params.BannerAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdCard
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdHolder
import com.lib.ads.gma.ads.manager.InterstitialAdManager
import com.lib.ads.gma.ads.model.wrapper.InterstitialAdListener
import com.lib.ads.gma.app.ads.view.NativeSmallCtaRight
import com.lib.ads.gma.app.ads.view.ShimmerSmallCtaRightView
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.app.gma.headline400
import com.lib.ads.gma.app.gma.headline600
import com.lib.ads.gma.app.gma.onboarding.OnboardingActivity
import com.lib.ads.gma.app.gma.splash.SplashActivity
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.backgroundResource
import com.lib.ads.gma.app.style.gray
import com.lib.ads.gma.app.style.onClickRipple
import com.lib.ads.gma.app.style.opacity
import com.lib.ads.gma.app.style.paddingBottom
import com.lib.ads.gma.app.style.paddingHorizontal
import com.lib.ads.gma.app.style.paddingTop
import com.lib.ads.gma.app.style.paddingVertical
import com.lib.ads.gma.app.style.primaryColor
import com.lib.ads.gma.app.style.statusBarPadding
import com.lib.ads.gma.app.style.white
import com.lib.ads.gma.app.style.widthPercent
import com.sdp.ssp.android.Sdp
import com.sdp.ssp.android.Ssp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Interstitial splash ads now navigate (onNextAction) *before* they show, so the ad's own
 * AdActivity can be started after the destination Activity and land on top of it. That only
 * works if this Activity doesn't chain further navigation of its own while an ad might still be
 * covering it — otherwise the newly-started Activity would itself land on top of the ad. Call
 * this before any startActivity()/finish() here.
 */
private suspend fun awaitAdNotShowing() {
    while (Ads.getInstance().isFullScreenAdShowing()) {
        delay(100.milliseconds)
    }
}

// Max time the "awaiting splash ad" cover is allowed to hide this screen's real content —
// safety net in case the ad never shows (no ad, load/show failure) so we don't get stuck.
private const val AD_OVERLAY_TIMEOUT_MS = 5000L

abstract class LanguageActivity : BaseActivity() {
    companion object {
        private const val ARG_SCREEN_TYPE = "ARG_SCREEN_TYPE"

        fun start(
            context: Context,
            screenType: LanguageScreenType,
            animate: Boolean = true,
        ) {
            val target = when (screenType) {
                LanguageScreenType.Language1 -> Language1Activity::class.java
                LanguageScreenType.Language2 -> Language2Activity::class.java
                LanguageScreenType.LanguageLoading -> LanguageLoadingActivity::class.java
            }
            val intent = Intent(context, target)
                .putExtra(ARG_SCREEN_TYPE, screenType)
            if (!animate) {
                context.startActivity(intent)
            } else {
                val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
                context.startActivity(intent, options)
            }
        }

        var selected by mutableStateOf(getListLanguageLfo().firstOrNull())
        private var savedScrollIndex: Int = 0
        private var savedScrollOffset: Int = 0
    }

    private val nativeBannerHolders: List<NativeAdHolderConfig> by lazy {
        val slotCount = (getLanguageArray().size / LANGUAGES_PER_NATIVE_BANNER)
        List(slotCount) { slot ->
            createNativeAdHolder(
                enabled = BuildConfig.ad_native.isNotBlank(),
                tag = "language_native_banner_${languageScreenType::class.simpleName}_$slot",
                options = NativeAdPreloadHolderOptions(
                    fallbackAdUnitIds = listOf(BuildConfig.ad_native),
                    lifecycleOwner = this@LanguageActivity,
                    autoRequestOnStart = false,
                    autoReloadOnResume = false,
                    cancelOnPause = true,
                ),
            ).value
        }
    }
    private val bannerAdHolder: BannerAdHolder by createBannerAdHolder(
            enabled = BuildConfig.ad_banner.isNotBlank(),
            tag = SplashActivity.TAG_NEXT_SCREEN_BANNER,
            options = BannerAdPreloadHolderOptions(
                lifecycleOwner = this@LanguageActivity,
                autoRequestOnStart = false,
                autoReloadOnResume = true,
                size = BannerSize.LargePortraitAdaptive,
                cancelOnPause = true,
            )
    )
    open val languageScreenType: LanguageScreenType get() = LanguageScreenType.LanguageLoading
    override fun initialize() {
        InterstitialAdManager.loadInterstitial(
            tag = "splash_interstitial",
            ids = listOf(BuildConfig.ad_interstitial_splash),
            listener = object : InterstitialAdListener {},
        )
        bannerAdHolder.request()
    }

    @Composable
    override fun BoxScope.ContentView() {
        BackHandler { finish() }
        val languageListState = rememberLazyListState(
            initialFirstVisibleItemIndex = savedScrollIndex,
            initialFirstVisibleItemScrollOffset = savedScrollOffset,
        )

        LaunchedEffect(Unit) {
            if (languageScreenType is LanguageScreenType.LanguageLoading) {
                savedScrollIndex = 0
                savedScrollOffset = 0
                awaitAdNotShowing()
                delay(3000.milliseconds)

                start(this@LanguageActivity, LanguageScreenType.Language1)
                finish()
            }
        }
        LanguageScreen(
            selected = selected,
            isLoadingLang = languageScreenType is LanguageScreenType.LanguageLoading,
            listState = languageListState,
            onContinue = { selected ->
                startActivity(
                    Intent(
                        this@LanguageActivity,
                        OnboardingActivity::class.java
                    )
                )
                finish()
            },
            bannerAdControl = {
                BannerAdCard(
                    holder = bannerAdHolder
                )
            },
            nativeBannerHolders = nativeBannerHolders,
            onLangSelected = {
                selected = it
                if (languageScreenType is LanguageScreenType.Language1) {
                    savedScrollIndex = languageListState.firstVisibleItemIndex
                    savedScrollOffset = languageListState.firstVisibleItemScrollOffset

                    start(this@LanguageActivity, LanguageScreenType.Language2)
                    finish()
                } else {
                    bannerAdHolder.reload()
                }

            })
    }
}

@Composable
private fun LanguageScreen(
    selected: LanguageItem? = null,
    isLoadingLang: Boolean = true,
    bannerAdControl: @Composable () -> Unit = {},
    nativeBannerHolders: List<NativeAdHolderConfig> = emptyList(),
    listState: LazyListState = rememberLazyListState(),
    onContinue: (String) -> Unit,
    onLangSelected: (LanguageItem) -> Unit = {}
) {

    val hazeState = rememberHazeState()
    val languageEntries = remember { buildLanguageEntries(getLanguageArray()) }
    Box(
        Modifier
            .fillMaxSize()
            .backgroundResource(R.drawable.img_bg_splash)
            .hazeSource(hazeState),
    )
    Column(
        Modifier
            .fillMaxSize()
            .paddingHorizontal(12.Sdp)
            .statusBarPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        bannerAdControl()
        Text(
            text = stringResource(R.string.language),
            style = headline600(fontSize = 16.Ssp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        if (isLoadingLang) {
            LinearProgressIndicator(
                trackColor = primaryColor.opacity(20f),
                color = primaryColor,
                modifier = Modifier
                    .widthPercent(80f)
                    .height(4.Sdp)
            )
            Text(
                text = stringResource(R.string.loading_language),
                style = headline400(fontSize = 12.Ssp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(1f)
                    .paddingVertical(8.Sdp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            items(languageEntries, key = { it.key }) { entry ->
                if (entry is LanguageListEntry.NativeBanner) {
                    val holder = nativeBannerHolders.getOrNull(entry.slot)
                    if (holder != null) {
                        LanguageNativeBannerSlot(holder)
                    }
                } else {
                    val language = (entry as LanguageListEntry.Language).item
                    val isSelected = selected?.code == language.code
                    Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
                        },
                    ),
                    border = if (isSelected) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        )
                    },
                    shape = RoundedCornerShape(12.Sdp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .hazeEffect(hazeState)
                        .onClickRipple() { onLangSelected(language) },
                    ) {
                        Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(language.flagId),
                            contentDescription = language.name,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(
                            language.name,
                            style = headline400(fontSize = 12.Ssp),
                            modifier = Modifier.weight(1f),
                        )
                        }
                    }
                }
            }
        }
        if (!isLoadingLang) {
            Button(
                onClick = { onContinue(selected?.code.orEmpty()) },
                enabled = selected != null,
                colors = ButtonColors(
                    containerColor = primaryColor,
                    contentColor = white,
                    disabledContainerColor = gray.opacity(0.5f),
                    disabledContentColor = white.opacity(0.5f),
                ),
                modifier = Modifier
                    .paddingTop(8.Sdp)
                    .fillMaxWidth(0.8f)
                    .paddingBottom(16.Sdp),
            ) {
                Text(
                    stringResource(R.string.continues),
                    modifier = Modifier.paddingVertical(8.Sdp),
                    color = white,
                    style = headline600(),
                    fontSize = 12.Ssp
                )
            }
        }
    }
}

private const val LANGUAGES_PER_NATIVE_BANNER = 4

private sealed interface LanguageListEntry {
    val key: String

    data class Language(val item: LanguageItem) : LanguageListEntry {
        override val key: String = item.code
    }

    data class NativeBanner(val slot: Int) : LanguageListEntry {
        override val key: String = "native_banner_$slot"
    }
}

private fun buildLanguageEntries(languages: List<LanguageItem>): List<LanguageListEntry> = buildList {
    languages.forEachIndexed { index, language ->
        add(LanguageListEntry.Language(language))
        if ((index + 1) % LANGUAGES_PER_NATIVE_BANNER == 0) {
            add(LanguageListEntry.NativeBanner(index / LANGUAGES_PER_NATIVE_BANNER))
        }
    }
}

@Composable
private fun LanguageNativeBannerSlot(holder: NativeAdHolderConfig) {
    LaunchedEffect(holder) { holder.request() }
    NativeAdCard(
        holder = holder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.Sdp),
        loading = { ShimmerSmallCtaRightView(it) },
        nativeView = { ad ->
            NativeSmallCtaRight(
                nativeAdView = ad,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun LanguageScreenPreview() {
    AppViewTheme { LanguageScreen(onContinue = {}) }
}
