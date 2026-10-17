package com.lib.ads.gma.ads.billing

import android.app.Activity
import android.content.Context
import com.lib.ads.gma.ads.event.AdsLogEventManager
import com.lib.ads.gma.ads.event.FirebaseAnalyticsUtil
import com.lib.ads.gma.ads.util.AppLogger
import com.lib.ads.gma.ads.util.SharePreferenceUtils
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.ConcurrentHashMap

internal class PurchaseProcessor(
    private val context: Context,
    private val connectionManager: BillingConnectionManager,
    private val repository: ProductDetailsRepository,
    private val verifier: PurchaseVerifier,
    private val useDevTestPurchase: () -> Boolean,
) {

    var onPurchaseStatusUpdate: (() -> Unit)? = null

    /** Receives consumables that completed while the app was not running (see [onRecovered]). */
    var onRecoveredConsumable: ((PurchaseEvent.Success) -> Unit)? = null

    private var idPurchaseCurrent: String = ""
    private var typeIap: Int = 0
    private var purchaseEventCallback: ((PurchaseEvent) -> Unit)? = null

    /** Offer launched per subscription product, so revenue uses the price actually charged. */
    private val launchedOfferTokens = ConcurrentHashMap<String, String>()

    /** Recovered purchases seen before product details (prices) were loaded. */
    private val revenueAwaitingPrices = mutableListOf<RecoveredRevenue>()

    private data class RecoveredRevenue(val purchase: Purchase, val productId: String)

    fun purchase(activity: Activity, productId: String, onResult: (PurchaseEvent) -> Unit) {
        AppLogger.i(TAG, "purchase: begin productId=$productId, devTestFlow=${useDevTestPurchase()}")
        if (useDevTestPurchase()) {
            val productDetails = repository.getINAPProductDetails(productId)
            PurchaseDevBottomSheet(1, productId, productDetails, activity, onResult).show()
            return
        }
        if (!repository.hasINAPProducts()) {
            AppLogger.w(TAG, "purchase: no in-app products loaded")
            onResult(PurchaseEvent.Error(6, "Billing error init"))
            return
        }
        val productDetails = repository.getINAPProductDetails(productId)
        if (productDetails == null) {
            AppLogger.w(TAG, "purchase: product not found productId=$productId")
            onResult(PurchaseEvent.Error(6, "Not found item with id: $productId"))
            return
        }
        AppLogger.d(TAG, "purchase: $productDetails")
        purchaseEventCallback = onResult
        idPurchaseCurrent = productId
        typeIap = AppPurchase.TYPE_IAP.PURCHASE

        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()

        launchBillingFlow(activity, flowParams, onResult)
    }

    fun subscribe(activity: Activity, subsId: String, onResult: (PurchaseEvent) -> Unit) {
        AppLogger.i(TAG, "subscribe: begin productId=$subsId, devTestFlow=${useDevTestPurchase()}")
        if (useDevTestPurchase()) {
            val subsDetails = repository.getSubsProductDetails(subsId)
            PurchaseDevBottomSheet(2, subsId, subsDetails, activity, onResult).show()
            return
        }
        if (!repository.hasSubsProducts()) {
            AppLogger.w(TAG, "subscribe: no subscription products loaded")
            onResult(PurchaseEvent.Error(6, "Billing error init"))
            return
        }
        val skuDetails = repository.getSubsProductDetails(subsId)
        if (skuDetails == null) {
            AppLogger.w(TAG, "subscribe: product not found productId=$subsId")
            onResult(PurchaseEvent.Error(6, "Product ID invalid"))
            return
        }
        val offers = skuDetails.subscriptionOfferDetails
        if (offers.isNullOrEmpty()) {
            AppLogger.w(TAG, "subscribe: no offers productId=$subsId")
            onResult(PurchaseEvent.Error(6, "Can't find offer for this subscription"))
            return
        }
        val offerToken = selectOfferToken(skuDetails, subsId)
        AppLogger.d(TAG, "subscribe: offerToken: $offerToken")
        purchaseEventCallback = onResult
        idPurchaseCurrent = subsId
        typeIap = AppPurchase.TYPE_IAP.SUBSCRIPTION
        launchedOfferTokens[subsId] = offerToken

        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(skuDetails)
                .setOfferToken(offerToken)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()

        launchBillingFlow(activity, flowParams, onResult)
    }

    fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        AppLogger.i(TAG, "onPurchasesUpdated: code=${billingResult.responseCode}, purchases=${purchases?.size ?: 0}, message=${billingResult.debugMessage}")
        val responseCode = billingResult.responseCode
        when {
            responseCode == BillingClient.BillingResponseCode.OK && purchases != null -> {
                for (purchase in purchases) {
                    handlePurchase(purchase)
                }
            }
            responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> {
                purchaseEventCallback?.invoke(PurchaseEvent.Cancelled)
                purchaseEventCallback = null
                AppLogger.d(TAG, "onPurchasesUpdated: USER_CANCELED")
            }
            else -> {
                purchaseEventCallback?.invoke(
                    PurchaseEvent.Error(billingResult.responseCode, billingResult.debugMessage)
                )
                purchaseEventCallback = null
                AppLogger.d(TAG, "onPurchasesUpdated: ...")
            }
        }
    }

    fun upgradeSubscription(
        activity: Activity,
        newSubsId: String,
        oldPurchaseToken: String,
        replacementMode: Int = 0,
        onResult: (PurchaseEvent) -> Unit
    ) {
        AppLogger.i(TAG, "upgradeSubscription: begin newProductId=$newSubsId, replacementMode=$replacementMode")
        purchaseEventCallback = onResult
        val skuDetails = repository.getSubsProductDetails(newSubsId)
        if (skuDetails == null) {
            purchaseEventCallback = null
            onResult(PurchaseEvent.Error(6, "Product ID invalid: $newSubsId"))
            return
        }
        val offerToken = selectOfferToken(skuDetails, newSubsId)
        idPurchaseCurrent = newSubsId
        typeIap = AppPurchase.TYPE_IAP.SUBSCRIPTION
        launchedOfferTokens[newSubsId] = offerToken

        val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(oldPurchaseToken)
            .setSubscriptionReplacementMode(replacementMode)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(skuDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .setSubscriptionUpdateParams(updateParams)
            .build()

        launchBillingFlow(activity, flowParams, onResult)
    }

    private fun launchBillingFlow(
        activity: Activity,
        params: BillingFlowParams,
        onResult: (PurchaseEvent) -> Unit,
    ) {
        val client = connectionManager.getBillingClient()
        if (client == null) {
            AppLogger.e(TAG, "launchBillingFlow: BillingClient is null")
            purchaseEventCallback = null
            onResult(PurchaseEvent.Error(6, "Billing not connected"))
            return
        }
        val result = client.launchBillingFlow(activity, params)
        AppLogger.d(TAG, "launchBillingFlow: responseCode=${result.responseCode}, message=${result.debugMessage}")
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseEventCallback = null
            onResult(PurchaseEvent.Error(result.responseCode, billingResultToMessage(result.responseCode)))
        }
    }

    private fun selectOfferToken(skuDetails: ProductDetails, subsId: String): String {
        val subsDetail = skuDetails.subscriptionOfferDetails ?: return ""
        val trialId = repository.getTrialId(subsId)
        for (item in subsDetail) {
            val offerId = item.offerId
            if (offerId != null && offerId == trialId) {
                return item.offerToken
            }
        }
        return subsDetail.firstOrNull { it.offerId == null }?.offerToken ?: subsDetail.last().offerToken
    }

    fun handlePurchase(purchase: Purchase) {
        // The purchase's own products win: the listener also reports purchases this process did
        // not start (pending completion, promo code), for which idPurchaseCurrent is stale or "".
        val productId = PurchaseRevenue.resolveProductId(purchase.products, repository::isConfigured, idPurchaseCurrent)
        val isSubscription = (repository.getType(productId) ?: typeIap) == AppPurchase.TYPE_IAP.SUBSCRIPTION
        AppLogger.i(TAG, "handlePurchase: productId=$productId, subs=$isSubscription, state=${purchase.purchaseState}, acknowledged=${purchase.isAcknowledged}, quantity=${purchase.quantity}")

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                // Not paid yet — no entitlement, no revenue. It is handled when it turns PURCHASED
                // (this listener, or the next refresh if the app is gone by then).
                AppLogger.d(TAG, "handlePurchase: PENDING — awaiting payment completion")
                purchaseEventCallback?.invoke(PurchaseEvent.Pending(productId))
                purchaseEventCallback = null
            }
            Purchase.PurchaseState.PURCHASED -> {
                val consume = repository.shouldConsume(productId)
                AppLogger.i(TAG, "handlePurchase: purchased productId=$productId, consume=$consume")
                if (!consume) {
                    verifier.grantEntitlements(repository.getEntitlements(productId))
                } else {
                    // Delivered to the flow callback now; a later refresh must not hand it out again.
                    SharePreferenceUtils.markPurchaseTrackedIfNew(context, grantKey(purchase))
                }
                purchaseEventCallback?.invoke(
                    PurchaseEvent.Success(purchase.orderId, productId, purchase.originalJson)
                )
                purchaseEventCallback = null
                trackRevenueOnce(purchase, productId, isSubscription, launchedOfferTokens[productId])
                completePurchase(purchase, productId, consume)
            }
            else -> {
                AppLogger.w(TAG, "handlePurchase: unknown state ${purchase.purchaseState}, skipping")
            }
        }
    }

    private fun completePurchase(purchase: Purchase, productId: String, consume: Boolean) {
        val billingClient = connectionManager.getBillingClient()
        if (consume) {
            val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient?.consumeAsync(consumeParams) { billingResult, _ ->
                AppLogger.d(TAG, "onConsumeResponse: code=${billingResult.responseCode}, message=${billingResult.debugMessage}")
            }
            return
        }
        // A failed acknowledge is retried by the next refresh (PurchaseVerifier acknowledges any
        // purchased-but-unacknowledged purchase), so it is not refunded after 3 days.
        if (purchase.isAcknowledged) return
        val ackParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(ackParams) { billingResult ->
            AppLogger.d(TAG, "onAcknowledgePurchaseResponse: code=${billingResult.responseCode}, message=${billingResult.debugMessage}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchase.orderId?.let { orderId ->
                    FirebaseAnalyticsUtil.logConfirmPurchaseGoogle(orderId, productId, purchase.purchaseToken)
                }
            }
        }
    }

    /**
     * Called by [PurchaseVerifier] for a purchased-but-unacknowledged purchase found on refresh:
     * it completed while the app was not running, so its revenue was never reported. A consumable
     * is also handed to [onRecoveredConsumable] — the verifier consumes it right after, and without
     * that callback the user would have paid for nothing.
     */
    fun onRecovered(purchase: Purchase, productId: String, isSubscription: Boolean) {
        if (!isSubscription && repository.shouldConsume(productId) &&
            SharePreferenceUtils.markPurchaseTrackedIfNew(context, grantKey(purchase))
        ) {
            val listener = onRecoveredConsumable
            if (listener == null) {
                AppLogger.w(TAG, "onRecovered: consumable $productId consumed without an onRecoveredConsumable listener")
            } else {
                com.lib.ads.gma.ads.engine.AdsProvider.runOnMain {
                    listener(PurchaseEvent.Success(purchase.orderId, productId, purchase.originalJson))
                }
            }
        }
        // ponytail: the offer behind a recovered subscription is unknown (a trial looks like the
        // base plan), so its start revenue is skipped rather than guessed; Adjust/AppsFlyer
        // server-side subscription tracking covers it when enabled.
        if (isSubscription) return
        if (repository.inAppPrice(productId) == null) {
            synchronized(revenueAwaitingPrices) { revenueAwaitingPrices += RecoveredRevenue(purchase, productId) }
            return
        }
        trackRevenueOnce(purchase, productId, isSubscription = false, offerToken = null)
    }

    private fun grantKey(purchase: Purchase) = "grant:" + purchase.purchaseToken

    /** Product details finished loading: report recovered purchases that were waiting for a price. */
    fun onProductDetailsReady() {
        val waiting = synchronized(revenueAwaitingPrices) {
            revenueAwaitingPrices.toList().also { revenueAwaitingPrices.clear() }
        }
        waiting.forEach { trackRevenueOnce(it.purchase, it.productId, isSubscription = false, offerToken = null) }
    }

    /**
     * Reports one purchase's revenue to Adjust / AppsFlyer at most once per purchase token, across
     * the purchase listener, refreshes and process restarts.
     */
    private fun trackRevenueOnce(purchase: Purchase, productId: String, isSubscription: Boolean, offerToken: String?) {
        val price = if (isSubscription) {
            repository.subscriptionStartPrice(productId, offerToken)
        } else {
            repository.inAppPrice(productId)
        }
        if (price == null) {
            AppLogger.w(TAG, "trackRevenue: no price for productId=$productId (product details not loaded)")
            return
        }
        if (!SharePreferenceUtils.markPurchaseTrackedIfNew(context, purchase.purchaseToken)) {
            AppLogger.d(TAG, "trackRevenue: already tracked productId=$productId")
            return
        }
        if (price.micros <= 0L) {
            AppLogger.d(TAG, "trackRevenue: free phase (trial) productId=$productId, nothing to report")
            return
        }
        if (isSubscription) {
            AdsLogEventManager.trackSubscriptionRevenue(
                priceMicros = price.micros,
                currency = price.currency,
                productId = productId,
                orderId = purchase.orderId.orEmpty(),
                signature = purchase.signature,
                purchaseToken = purchase.purchaseToken,
                purchaseTime = purchase.purchaseTime,
            )
        } else {
            val quantity = purchase.quantity.coerceAtLeast(1)
            AdsLogEventManager.trackPurchaseRevenue(
                revenue = price.micros * quantity / 1_000_000.0,
                currency = price.currency,
                productId = productId,
                purchaseToken = purchase.purchaseToken,
                orderId = purchase.orderId,
            )
        }
    }

    fun consumePurchase(productId: String) = consumePurchase(productId, attempt = 0)

    private fun consumePurchase(productId: String, attempt: Int) {
        AppLogger.d(TAG, "consumePurchase: $productId attempt=$attempt")
        val billingClient = connectionManager.getBillingClient() ?: return
        val queryParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(queryParams) { billingResult, list ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val purchase = list.lastOrNull { productId in it.products } ?: return@queryPurchasesAsync
            try {
                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.consumeAsync(consumeParams) { result, _ ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        AppLogger.d(TAG, "onConsumeResponse: OK")
                        onPurchaseStatusUpdate?.invoke()
                    } else {
                        AppLogger.e(TAG, "consumePurchase: error code=${result.responseCode}, message=${result.debugMessage}")
                        if (attempt < MAX_RETRY_CONSUME_TIMES) consumePurchase(productId, attempt + 1)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "consumePurchase: error", e)
            }
        }
    }

    private fun billingResultToMessage(responseCode: Int): String = when (responseCode) {
        3 -> "Billing not supported for type of request"
        6 -> "Error completing request"
        2 -> "Network Connection down"
        1 -> "Request Canceled"
        -2 -> "Error processing request."
        7 -> "Selected item is already owned"
        4 -> "Item not available"
        -1 -> "Play Store service is not connected now"
        -3 -> "Timeout"
        0 -> "Subscribed Successfully"
        5, 8 -> ""
        else -> ""
    }

    companion object {
        private const val TAG = "GMA_Purchase"
        private const val MAX_RETRY_CONSUME_TIMES = 1
    }
}
