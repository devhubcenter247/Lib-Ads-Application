package com.lib.ads.gma.app.gma.feature

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lib.ads.gma.BuildConfig
import com.lib.ads.gma.R
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdCard
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdHolderConfig
import com.lib.ads.gma.ads.helper.adnative.preload.NativeAdPreloadHolderOptions
import com.lib.ads.gma.ads.helper.adnative.preload.createNativeAdHolder
import com.lib.ads.gma.app.ads.view.NativeMediumMediaLeftCtaRight
import com.lib.ads.gma.app.ads.view.ShimmerMediaLeftCtaRightView
import com.lib.ads.gma.app.base.BaseActivity
import com.lib.ads.gma.app.gma.MainActivity
import com.lib.ads.gma.app.gma.headline400
import com.lib.ads.gma.app.gma.headline600
import com.lib.ads.gma.app.style.AppViewTheme
import com.lib.ads.gma.app.style.backgroundResource
import com.lib.ads.gma.app.style.onClickRipple
import com.lib.ads.gma.app.style.paddingBottom
import com.lib.ads.gma.app.style.paddingHorizontal
import com.lib.ads.gma.app.style.paddingVertical
import com.lib.ads.gma.app.style.statusBarPadding
import com.lib.ads.gma.app.style.white
import com.sdp.ssp.android.Sdp
import com.sdp.ssp.android.Ssp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

abstract class FeatureActivity : BaseActivity() {
    companion object {
        private const val ARG_SCREEN_TYPE = "ARG_SCREEN_TYPE"
        var listFeatureSelect = mutableStateListOf<FeatureModel>()

        fun start(context: Context, screenType: FeatureScreenType, animation: Boolean = true) {
            val target = when (screenType) {
                FeatureScreenType.Feature1 -> Feature1Activity::class.java
                FeatureScreenType.Feature2 -> Feature2Activity::class.java
            }
            context.startActivity(
                Intent(context, target).putExtra(ARG_SCREEN_TYPE, screenType).apply {
                    if (!animation) {
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                })
        }
    }

    private val nativeAdHolder: NativeAdHolderConfig by createNativeAdHolder(
            enabled = BuildConfig.ad_native_high.isNotBlank(),
            tag = "welcome",
            options = NativeAdPreloadHolderOptions(
                fallbackAdUnitIds = listOf(BuildConfig.ad_native_high),
                lifecycleOwner = this@FeatureActivity,
                autoRequestOnStart = false,
                autoReloadOnResume = false,
                cancelOnPause = true,
            ),
        )

    open val screenType: FeatureScreenType = FeatureScreenType.Feature1

    override fun initialize() {
        nativeAdHolder.request()
    }

    @Composable
    override fun BoxScope.ContentView() {
        BackHandler { finish() }
        FeatureScreen(
            nativeAdHolder = nativeAdHolder,
            initialSelectedIds = listFeatureSelect.map { it.index }.toSet(),
            onSelectFeature = { item, isSelected ->
                if (screenType == FeatureScreenType.Feature1) {
                    listFeatureSelect.clear()
                    if (!isSelected) listFeatureSelect.add(item)
                    start(this@FeatureActivity, FeatureScreenType.Feature2, false)
                    finish()
                    return@FeatureScreen
                }

                if (isSelected) {
                    listFeatureSelect.remove(item)
                } else {
                    listFeatureSelect.add(item)
                }
        }, onFinish = {
            if (screenType is FeatureScreenType.Feature2) {
                startActivity(Intent(this@FeatureActivity, MainActivity::class.java))
                finish()
            }
        })
    }
}

@Composable
fun FeatureScreen(
    nativeAdHolder: NativeAdHolderConfig? = null,
    initialSelectedIds: Set<Int> = emptySet(),
    onSelectFeature: (FeatureModel, Boolean) -> Unit = {_, _ -> },
    onFinish: () -> Unit
) {
    var selected by remember(initialSelectedIds) { mutableStateOf(initialSelectedIds) }
    val hazeState = rememberHazeState()

    Box(
        Modifier
            .fillMaxSize()
            .backgroundResource(R.drawable.img_bg_splash)
            .hazeSource(hazeState),
    )
    Column(
        Modifier
            .statusBarPadding()
            .fillMaxSize(),
    ) {
        Text(
            text = stringResource(R.string.which_style_inspires_you_most),
            style = headline600(fontSize = 16.Ssp),
            modifier = Modifier
                .padding(bottom = 12.dp)
                .paddingHorizontal(16.Sdp),
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .paddingHorizontal(16.Sdp)
        ) {
            items(listFeatureModel, key = { it.index }) { item ->
                val isSelected = item.index in selected
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
                        .onClickRipple {
                            selected = if (isSelected) {
                                selected - item.index
                            } else {
                                selected + item.index
                            }
                            onSelectFeature(item,isSelected)
                        },
                ) {
                    Text(
                        text = stringResource(item.content),
                        style = headline400(fontSize = 12.Ssp),
                        modifier = Modifier.padding(16.Sdp),
                    )
                }
            }
        }
        Button(
            onClick = onFinish,
            enabled = selected.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .paddingBottom(16.Sdp)
                .paddingHorizontal(16.Sdp),
        ) {
            Text(
                stringResource(R.string.continues),
                modifier = Modifier.paddingVertical(8.Sdp),
                color = white,
                fontSize = 12.Ssp,
            )
        }
        NativeAdCard(
            holder = nativeAdHolder,
            modifier = Modifier
                .fillMaxWidth(),
            loading = { ShimmerMediaLeftCtaRightView(it) },
            nativeView = { ad ->
                NativeMediumMediaLeftCtaRight(
                    nativeAdView = ad,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun FeatureScreenPreview() {
    AppViewTheme { FeatureScreen() {} }
}
