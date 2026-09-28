package com.lib.ads.gma.app.gma

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.ExperimentalApi
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdPosition
import com.google.android.libraries.ads.mobile.sdk.pip.PictureInPictureAdPresentationScope
import com.lib.ads.gma.ads.helper.pip.PictureInPictureAdManager
import com.lib.ads.gma.app.base.BaseActivity

@OptIn(ExperimentalApi::class)
class PictureInPictureActivity : BaseActivity() {
    private val adUnitId = "ca-app-pub-3940256099942544/9657123429"

    override fun initialize() = Unit

    override fun onDestroy() {
        // The ad itself is application-scoped, but this screen's callback is not.
        PictureInPictureAdManager.setAdEventCallback(null)
        if (PictureInPictureAdManager.presentationScope() !=
            PictureInPictureAdPresentationScope.APPLICATION
        ) {
            PictureInPictureAdManager.destroy()
        }
        super.onDestroy()
    }

    @Composable
    override fun BoxScope.ContentView() {
        var status by remember { mutableStateOf(if (PictureInPictureAdManager.isLoaded) "Ad loaded" else "Not loaded") }
        var position by remember { mutableStateOf(PictureInPictureAdPosition.DEFAULT) }
        BackHandler { finish() }

        PictureInPictureDemoScreen(
            status = status,
            position = position,
            onPositionChanged = { position = it },
            onLoad = {
                status = "Loading…"
                PictureInPictureAdManager.load(
                    adUnitId = adUnitId,
                    onLoaded = { status = "Ad loaded" },
                    onFailedToLoad = { error -> status = "Load failed: ${error.message}" },
                )
                PictureInPictureAdManager.setAdEventCallback(object : PictureInPictureAdEventCallback {
                    override fun onAdShown() { status = "Ad shown" }
                    override fun onAdHidden() { status = "Ad hidden" }
                    override fun onAdImpression() { status = "Impression recorded" }
                    override fun onAdClicked() { status = "Ad clicked" }
                    override fun onAdPaid(value: AdValue) { status = "Paid: ${value.valueMicros} ${value.currencyCode}" }
                    override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                        status = "Show failed: ${error.message}"
                    }
                })
            },
            onShow = {
                status = if (PictureInPictureAdManager.show(this@PictureInPictureActivity, position)) {
                    "Showing…"
                } else {
                    "Load an ad first"
                }
            },
            onHide = {
                PictureInPictureAdManager.hide()
                status = "Hidden"
            },
            onDestroy = {
                PictureInPictureAdManager.destroy()
                status = "Destroyed"
            },
        )
    }
}

@OptIn(ExperimentalApi::class)
@Composable
private fun PictureInPictureDemoScreen(
    status: String,
    position: PictureInPictureAdPosition,
    onPositionChanged: (PictureInPictureAdPosition) -> Unit,
    onLoad: () -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onDestroy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Picture-in-picture ad demo")
        Text("Status: $status")
        Text("Test unit: ca-app-pub-3940256099942544/9657123429")
        Text("Position: ${position.name}")
        Button(onClick = { onPositionChanged(PictureInPictureAdPosition.TOP_LEFT) }, Modifier.fillMaxWidth()) { Text("Top left") }
        Button(onClick = { onPositionChanged(PictureInPictureAdPosition.TOP_RIGHT) }, Modifier.fillMaxWidth()) { Text("Top right") }
        Button(onClick = { onPositionChanged(PictureInPictureAdPosition.BOTTOM_LEFT) }, Modifier.fillMaxWidth()) { Text("Bottom left") }
        Button(onClick = { onPositionChanged(PictureInPictureAdPosition.BOTTOM_RIGHT) }, Modifier.fillMaxWidth()) { Text("Bottom right") }
        Button(onClick = onLoad, Modifier.fillMaxWidth()) { Text("Load PiP ad") }
        Button(onClick = onShow, Modifier.fillMaxWidth()) { Text("Show PiP ad (application scope)") }
        Button(onClick = onHide, Modifier.fillMaxWidth()) { Text("Hide PiP ad") }
        Button(onClick = onDestroy, Modifier.fillMaxWidth()) { Text("Destroy PiP ad") }
    }
}
