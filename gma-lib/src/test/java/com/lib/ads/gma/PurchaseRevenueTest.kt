package com.lib.ads.gma

import com.lib.ads.gma.ads.billing.PurchaseRevenue
import com.lib.ads.gma.ads.billing.PurchaseRevenue.OfferPricing
import com.lib.ads.gma.ads.billing.PurchaseRevenue.Price
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseRevenueTest {

    private val configured = setOf("remove_ads", "coins_100")

    @Test
    fun `purchase products win over the stale current product`() {
        assertEquals("coins_100", PurchaseRevenue.resolveProductId(listOf("coins_100"), configured::contains, "remove_ads"))
    }

    @Test
    fun `configured product is preferred in a multi-product purchase`() {
        assertEquals("remove_ads", PurchaseRevenue.resolveProductId(listOf("other", "remove_ads"), configured::contains, ""))
    }

    @Test
    fun `unknown products still resolve, empty purchase falls back`() {
        assertEquals("other", PurchaseRevenue.resolveProductId(listOf("other"), configured::contains, "remove_ads"))
        assertEquals("remove_ads", PurchaseRevenue.resolveProductId(emptyList(), configured::contains, "remove_ads"))
    }

    private val base = OfferPricing("base-token", null, listOf(Price(4_990_000L, "USD")))
    private val trial = OfferPricing("trial-token", "free-trial", listOf(Price(0L, "USD"), Price(4_990_000L, "USD")))
    private val intro = OfferPricing("intro-token", "intro", listOf(Price(990_000L, "USD"), Price(4_990_000L, "USD")))

    @Test
    fun `trial start reports zero, not the regular price`() {
        assertEquals(0L, PurchaseRevenue.subscriptionStartPrice(listOf(base, trial, intro), "trial-token")!!.micros)
    }

    @Test
    fun `intro offer reports the intro price actually charged`() {
        assertEquals(990_000L, PurchaseRevenue.subscriptionStartPrice(listOf(base, trial, intro), "intro-token")!!.micros)
    }

    @Test
    fun `unknown or missing offer falls back to the base plan`() {
        assertEquals(4_990_000L, PurchaseRevenue.subscriptionStartPrice(listOf(trial, base), null)!!.micros)
        assertEquals(4_990_000L, PurchaseRevenue.subscriptionStartPrice(listOf(trial, base), "gone")!!.micros)
    }

    @Test
    fun `no base plan and no matching offer gives no price`() {
        assertNull(PurchaseRevenue.subscriptionStartPrice(listOf(trial), null))
    }
}
