package com.lib.ads.gma

import com.lib.ads.gma.ads.helper.fullscreen.preload.WeightedAdUnit
import com.lib.ads.gma.ads.helper.interstitial.InterstitialAdConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class InterstitialAdConfigWeightedTest {
    @Test
    fun weightedAdUnits_fromPlainWaterfall_keepOrderAsPriority() {
        val config = InterstitialAdConfig.waterfall(listOf("first", "second", "third"))

        assertEquals(
            listOf(
                WeightedAdUnit("first", 3f),
                WeightedAdUnit("second", 2f),
                WeightedAdUnit("third", 1f),
            ),
            config.weightedAdUnits(),
        )
    }

    @Test
    fun setWeightedListId_dedupesByHighestWeightAndUpdatesPlainIds() {
        val config = InterstitialAdConfig.waterfall(listOf("old"))
            .setWeightedListId(
                listOf(
                    WeightedAdUnit("high", 10f),
                    WeightedAdUnit("low", 1f),
                    WeightedAdUnit("high", 20f),
                )
            )

        assertEquals(listOf("high", "low"), config.listId)
        assertEquals(
            listOf(
                WeightedAdUnit("high", 20f),
                WeightedAdUnit("low", 1f),
            ),
            config.weightedAdUnits(),
        )
    }

    @Test
    fun setListId_clearsExplicitWeights() {
        val config = InterstitialAdConfig.weightedWaterfall(
            listOf(WeightedAdUnit("a", 100f))
        ).setListId(listOf("b", "c"))

        assertEquals(
            listOf(
                WeightedAdUnit("b", 2f),
                WeightedAdUnit("c", 1f),
            ),
            config.weightedAdUnits(),
        )
    }
}
