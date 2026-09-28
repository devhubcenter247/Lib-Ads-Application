package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.lib.ads.gma.ads.util.AppLogger

/** Coordinates purchase queries and delegates state management to focused components. */
internal class PurchaseVerifier {
    private val store = PurchaseEntitlementStore()
    private val queries = PurchaseQueryService()

    var isPurchased: Boolean
        get() = store.isPurchased
        set(value) = store.setPurchased(value)

    fun isPurchasedFlow(): StateFlow<Boolean> = store.isPurchasedFlow
    fun ownedEntitlementsFlow(): StateFlow<Set<Entitlement>> = store.ownedEntitlementsFlow
    fun hasEntitlement(entitlement: Entitlement): Boolean = entitlement in store.ownedEntitlements
    fun ownedEntitlements(): Set<Entitlement> = store.ownedEntitlements
    fun grantEntitlements(entitlements: Set<Entitlement>) = store.grant(entitlements)
    fun getOwnerIdSubs(): List<PurchaseResult> = store.getOwnerIdSubs()
    fun getOwnerIdInApp(): List<PurchaseResult> = store.getOwnerIdInApp()
    fun getSubscriptionPurchaseToken(subsId: String): String? = store.getSubscriptionPurchaseToken(subsId)

    fun verifyAndRefresh(
        billingClient: BillingClient?,
        listINAPId: List<QueryProductDetailsParams.Product>,
        listSubsId: List<QueryProductDetailsParams.Product>,
        productIdMap: ConcurrentHashMap<QueryProductDetailsParams.Product, String>,
        shouldConsume: (String) -> Boolean,
        getEntitlements: (String) -> Set<Entitlement>,
        onComplete: ((Int) -> Unit)?,
    ) {
        AppLogger.i(TAG, "verifyAndRefresh: started inApp=${listINAPId.size}, subscriptions=${listSubsId.size}")
        queryBoth(
            billingClient = billingClient,
            onComplete = onComplete,
            onInApp = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearInApp()
                purchases.forEach { purchase ->
                    if (purchase.isPending()) {
                        AppLogger.d(TAG, "verifyPurchased INAPP: PENDING purchase ${purchase.products}")
                    } else {
                        listINAPId.forEach { product ->
                            val productId = productIdMap[product] ?: return@forEach
                            if (purchase.products.contains(productId)) {
                                if (shouldConsume(productId)) {
                                    reconsume(billingClient, purchase.purchaseToken, productId)
                                } else {
                                    store.addInApp(PurchaseResult.fromPurchase(purchase), productId)
                                    store.grant(getEntitlements(productId))
                                }
                            }
                        }
                    }
                }
            },
            onSubs = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearSubscriptions()
                purchases.forEach { purchase ->
                    if (purchase.isPending()) {
                        AppLogger.d(TAG, "verifyPurchased SUBS: PENDING purchase ${purchase.products}")
                    } else {
                        listSubsId.forEach { product ->
                            val productId = productIdMap[product] ?: return@forEach
                            if (purchase.products.contains(productId)) {
                                store.addSubscription(PurchaseResult.fromPurchase(purchase), productId)
                                store.grant(getEntitlements(productId))
                            }
                        }
                    }
                }
            },
            onBothComplete = { store.replaceFromOwners(getEntitlements) },
        )
    }

    fun updatePurchaseStatus(
        billingClient: BillingClient?,
        listINAPId: List<QueryProductDetailsParams.Product>,
        listSubsId: List<QueryProductDetailsParams.Product>,
        productIdMap: ConcurrentHashMap<QueryProductDetailsParams.Product, String>,
        shouldConsume: (String) -> Boolean,
        getEntitlements: (String) -> Set<Entitlement>,
        onComplete: (() -> Unit)?,
    ) {
        AppLogger.i(TAG, "updatePurchaseStatus: started inApp=${listINAPId.size}, subscriptions=${listSubsId.size}")
        queryBoth(
            billingClient = billingClient,
            onInApp = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearInApp()
                purchases.forEach { purchase ->
                    listINAPId.forEach { product ->
                        val productId = productIdMap[product] ?: return@forEach
                        if (purchase.products.contains(productId)) {
                            if (shouldConsume(productId)) {
                                reconsume(billingClient, purchase.purchaseToken, productId)
                            } else {
                                store.addInApp(PurchaseResult.fromPurchase(purchase), productId)
                            }
                        }
                    }
                }
            },
            onSubs = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearSubscriptions()
                purchases.forEach { purchase ->
                    listSubsId.forEach { product ->
                        val productId = productIdMap[product] ?: return@forEach
                        if (purchase.products.contains(productId)) {
                            store.addSubscription(PurchaseResult.fromPurchase(purchase), productId)
                        }
                    }
                }
            },
            onBothComplete = {
                store.replaceFromOwners(getEntitlements)
                AppLogger.i(TAG, "updatePurchaseStatus: purchases refreshed")
                onComplete?.invoke()
            },
        )
    }

    private fun queryBoth(
        billingClient: BillingClient?,
        onInApp: (BillingResult?, List<Purchase>) -> Unit,
        onSubs: (BillingResult?, List<Purchase>) -> Unit,
        onComplete: ((Int) -> Unit)? = null,
        onBothComplete: (() -> Unit)? = null,
    ) {
        val remaining = AtomicInteger(2)
        val errorCode = AtomicInteger(BillingClient.BillingResponseCode.OK)

        fun complete(result: BillingResult?) {
            val code = result?.responseCode ?: BillingClient.BillingResponseCode.ERROR
            if (code != BillingClient.BillingResponseCode.OK) {
                errorCode.compareAndSet(BillingClient.BillingResponseCode.OK, code)
            }
            if (remaining.decrementAndGet() == 0) {
                AppLogger.d(TAG, "queryBoth: completed code=${errorCode.get()}")
                onBothComplete?.invoke()
                onComplete?.invoke(errorCode.get())
            }
        }

        queries.queryBoth(
            billingClient,
            { result, purchases ->
                onInApp(result, purchases)
                complete(result)
            },
            { result, purchases ->
                onSubs(result, purchases)
                complete(result)
            },
        )
    }

    private fun reconsume(client: BillingClient?, token: String, productId: String) {
        AppLogger.i(TAG, "Re-consuming uncompleted consumable: $productId")
        client?.consumeAsync(
            ConsumeParams.newBuilder().setPurchaseToken(token).build()
        ) { result, _ -> AppLogger.d(TAG, "Re-consume result: ${result.debugMessage}") }
    }

    private fun Purchase.isPending(): Boolean = purchaseState == Purchase.PurchaseState.PENDING

    private val BillingResult?.isOk: Boolean
        get() = this?.responseCode == BillingClient.BillingResponseCode.OK

    companion object {
        private const val TAG = "GMA_Purchase"
    }
}
