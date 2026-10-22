package com.lib.ads.gma

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.billing.BillingConnectionManager
import com.lib.ads.gma.ads.billing.ProductDetailsRepository
import com.lib.ads.gma.ads.billing.PurchaseEvent
import com.lib.ads.gma.ads.billing.PurchaseItem
import com.lib.ads.gma.ads.billing.PurchaseProcessor
import com.lib.ads.gma.ads.billing.PurchaseVerifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A consumable no purchase flow is waiting for must still reach the app exactly once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsumableDeliveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val delivered = mutableListOf<PurchaseEvent.Success>()

    private val processor = PurchaseProcessor(
        context = context,
        connectionManager = BillingConnectionManager(),
        repository = ProductDetailsRepository().apply {
            syncPurchaseItemsToListProduct(
                listOf(PurchaseItem("coins_100", AppPurchase.TYPE_IAP.PURCHASE, consume = true))
            )
        },
        verifier = PurchaseVerifier(),
        useDevTestPurchase = { false },
    ).apply { onRecoveredConsumable = { delivered += it } }

    private fun purchase(token: String) = Purchase(
        """{"orderId":"GPA.1","packageName":"app","productIds":["coins_100"],"purchaseState":0,""" +
            """"purchaseToken":"$token","acknowledged":false}""",
        "signature",
    )

    @Test
    fun `pending purchase completing while the app runs is delivered`() {
        processor.handlePurchase(purchase("token-pending"))

        assertEquals(listOf("coins_100"), delivered.map { it.productId })
    }

    @Test
    fun `a later refresh does not deliver the same purchase again`() {
        val bought = purchase("token-refresh")
        processor.handlePurchase(bought)
        processor.onRecovered(bought, "coins_100", isSubscription = false)

        assertEquals(1, delivered.size)
    }

    @Test
    fun `purchase found only on refresh is delivered once`() {
        val bought = purchase("token-recovered")
        processor.onRecovered(bought, "coins_100", isSubscription = false)
        processor.onRecovered(bought, "coins_100", isSubscription = false)

        assertEquals(1, delivered.size)
    }
}
