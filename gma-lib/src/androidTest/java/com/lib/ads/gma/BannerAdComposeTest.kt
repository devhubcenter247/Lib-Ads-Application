package com.lib.ads.gma

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdCard
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdDisplayState
import com.lib.ads.gma.ads.helper.banner.preload.BannerAdHolder
import com.lib.ads.gma.ads.helper.banner.preload.DefaultBannerAdLoading
import com.lib.ads.gma.compose.AdDebugInfo
import com.lib.ads.gma.compose.AdDebugOverlayCompose
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Test loading slots get an explicit size: a 0x0 node is never "displayed", so
// assertIsDisplayed() would fail regardless of what BannerAdCard renders.
@RunWith(AndroidJUnit4::class)
class BannerAdComposeTest {

    @get:Rule
    val rule = createComposeRule()

    // ── BannerAdCard – state-driven rendering ────────────────────────────────

    @Test
    fun bannerAdCard_idleState_showsLoadingContent() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Idle)
        val holder = BannerAdHolder.testInstance(state = state)

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) }
            )
        }

        rule.onNodeWithTag("loading").assertIsDisplayed()
    }

    @Test
    fun bannerAdCard_loadingState_showsLoadingContent() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Loading)
        val holder = BannerAdHolder.testInstance(state = state)

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) }
            )
        }

        rule.onNodeWithTag("loading").assertIsDisplayed()
    }

    @Test
    fun bannerAdCard_cancelledState_loadingIsHidden() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Cancelled)
        val holder = BannerAdHolder.testInstance(state = state)

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) }
            )
        }

        // Cancelled renders Unit (nothing) — loading lambda is not called
        rule.onNodeWithTag("loading").assertDoesNotExist()
    }

    @Test
    fun bannerAdCard_errorState_loadingIsHidden() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Error(null))
        val holder = BannerAdHolder.testInstance(state = state)

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) },
                error = { Box(Modifier.testTag("error")) }
            )
        }

        // Error state: loading lambda not called, error IS called but inside height(0.dp)
        rule.onNodeWithTag("loading").assertDoesNotExist()
        rule.onNodeWithTag("error").assertIsNotDisplayed()
    }

    // ── BannerAdCard inspection mode ─────────────────────────────────────────

    @Test
    fun bannerAdCard_inspectionMode_showsLoadingComposable() {
        val holder = BannerAdHolder.testInstance()

        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                BannerAdCard(
                    holder = holder,
                    loading = { Box(Modifier.size(1.dp).testTag("preview_loading")) }
                )
            }
        }

        rule.onNodeWithTag("preview_loading").assertIsDisplayed()
    }

    // ── DefaultBannerAdLoading shimmer placeholder ────────────────────────────

    @Test
    fun defaultBannerAdLoading_renders() {
        rule.setContent {
            Box(Modifier.testTag("banner_shimmer")) {
                DefaultBannerAdLoading()
            }
        }

        rule.onNodeWithTag("banner_shimmer").assertIsDisplayed()
    }

    // ── AdDebugOverlay ───────────────────────────────────────────────────────

    @Test
    fun adDebugOverlay_rendersStateLabel() {
        val info = AdDebugInfo(
            adType = "Banner",
            state = "Loading",
            adUnitId = "ca-app-pub-test/banner",
            loadTimeMs = null
        )

        rule.setContent {
            Box(Modifier.testTag("overlay")) {
                AdDebugOverlayCompose(info = info)
            }
        }

        rule.onNodeWithTag("overlay").assertIsDisplayed()
    }

    // ── State transitions ────────────────────────────────────────────────────

    @Test
    fun bannerAdCard_stateTransition_loadingToCancelled_hidesContent() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Loading)
        val holder = BannerAdHolder.testInstance(state = state)

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) }
            )
        }

        rule.onNodeWithTag("loading").assertIsDisplayed()

        state.value = BannerAdDisplayState.Cancelled
        rule.waitForIdle()

        // Cancelled renders Unit — loading lambda not called, node removed from composition
        rule.onNodeWithTag("loading").assertDoesNotExist()
    }

    @Test
    fun bannerAdCard_onStateChange_calledWithCorrectState() {
        val state = MutableStateFlow<BannerAdDisplayState>(BannerAdDisplayState.Idle)
        val holder = BannerAdHolder.testInstance(state = state)
        val receivedStates = mutableListOf<BannerAdDisplayState>()

        rule.setContent {
            BannerAdCard(
                holder = holder,
                loading = { Box(Modifier.size(1.dp).testTag("loading")) },
                onStateChange = { receivedStates.add(it) }
            )
        }

        rule.waitForIdle()

        assert(receivedStates.any { it is BannerAdDisplayState.Idle })
    }
}
