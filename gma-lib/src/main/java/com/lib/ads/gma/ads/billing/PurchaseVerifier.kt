package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.AcknowledgePurchaseParams
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

    /** Called with the entitlements Play just confirmed (successful refresh only) — the value to persist. */
    var onEntitlementsVerified: ((Set<Entitlement>) -> Unit)? = null

    /**
     * A PURCHASED but not yet acknowledged purchase seen during a refresh: it finished while the
     * app was dead (pending payment completed, acknowledge/consume failed) and may never have gone
     * through the purchase flow. Acknowledged purchases are never reported — they were handled
     * already, and reporting them would re-count every existing owner after an SDK update.
     */
    fun interface RecoveredPurchaseListener {
        fun onRecovered(purchase: Purchase, productId: String, isSubscription: Boolean)
    }

    fun verifyAndRefresh(
        billingClient: BillingClient?,
        listINAPId: List<QueryProductDetailsParams.Product>,
        listSubsId: List<QueryProductDetailsParams.Product>,
        productIdMap: ConcurrentHashMap<QueryProductDetailsParams.Product, String>,
        shouldConsume: (String) -> Boolean,
        getEntitlements: (String) -> Set<Entitlement>,
        onRecovered: RecoveredPurchaseListener? = null,
        onComplete: ((Int) -> Unit)?,
    ) {
        AppLogger.i(TAG, "verifyAndRefresh: started inApp=${listINAPId.size}, subscriptions=${listSubsId.size}")
        refresh(billingClient, listINAPId, listSubsId, productIdMap, shouldConsume, getEntitlements, onRecovered) { code ->
            onComplete?.invoke(code)
        }
    }

    fun updatePurchaseStatus(
        billingClient: BillingClient?,
        listINAPId: List<QueryProductDetailsParams.Product>,
        listSubsId: List<QueryProductDetailsParams.Product>,
        productIdMap: ConcurrentHashMap<QueryProductDetailsParams.Product, String>,
        shouldConsume: (String) -> Boolean,
        getEntitlements: (String) -> Set<Entitlement>,
        onRecovered: RecoveredPurchaseListener? = null,
        onComplete: (() -> Unit)?,
    ) {
        AppLogger.i(TAG, "updatePurchaseStatus: started inApp=${listINAPId.size}, subscriptions=${listSubsId.size}")
        refresh(billingClient, listINAPId, listSubsId, productIdMap, shouldConsume, getEntitlements, onRecovered) {
            AppLogger.i(TAG, "updatePurchaseStatus: purchases refreshed")
            onComplete?.invoke()
        }
    }

    /**
     * Rebuilds ownership from Play. Only PURCHASED purchases count: a PENDING one is not paid and
     * must neither grant entitlements nor be consumed/acknowledged. A purchased one that was never
     * acknowledged is acknowledged here, otherwise Play refunds it after 3 days.
     * Entitlements are derived once, from the final owner lists, when both queries finished.
     */
    private fun refresh(
        billingClient: BillingClient?,
        listINAPId: List<QueryProductDetailsParams.Product>,
        listSubsId: List<QueryProductDetailsParams.Product>,
        productIdMap: ConcurrentHashMap<QueryProductDetailsParams.Product, String>,
        shouldConsume: (String) -> Boolean,
        getEntitlements: (String) -> Set<Entitlement>,
        onRecovered: RecoveredPurchaseListener?,
        onComplete: (Int) -> Unit,
    ) {
        val inAppIds = listINAPId.mapNotNull { productIdMap[it] }
        val subsIds = listSubsId.mapNotNull { productIdMap[it] }
        queryBoth(
            billingClient = billingClient,
            onInApp = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearInApp()
                purchases.forEach { purchase ->
                    if (!purchase.isPurchased()) {
                        AppLogger.d(TAG, "refresh INAPP: skip state=${purchase.purchaseState} ${purchase.products}")
                        return@forEach
                    }
                    inAppIds.filter { it in purchase.products }.forEach { productId ->
                        if (!purchase.isAcknowledged) onRecovered?.onRecovered(purchase, productId, isSubscription = false)
                        if (shouldConsume(productId)) {
                            reconsume(billingClient, purchase.purchaseToken, productId)
                        } else {
                            acknowledgeIfNeeded(billingClient, purchase)
                            store.addInApp(PurchaseResult.fromPurchase(purchase), productId)
                        }
                    }
                }
            },
            onSubs = { result, purchases ->
                if (!result.isOk) return@queryBoth
                store.clearSubscriptions()
                purchases.forEach { purchase ->
                    if (!purchase.isPurchased()) {
                        AppLogger.d(TAG, "refresh SUBS: skip state=${purchase.purchaseState} ${purchase.products}")
                        return@forEach
                    }
                    subsIds.filter { it in purchase.products }.forEach { productId ->
                        if (!purchase.isAcknowledged) onRecovered?.onRecovered(purchase, productId, isSubscription = true)
                        acknowledgeIfNeeded(billingClient, purchase)
                        store.addSubscription(PurchaseResult.fromPurchase(purchase), productId)
                    }
                }
            },
            onBothComplete = { code ->
                if (code == BillingClient.BillingResponseCode.OK) {
                    store.replaceFromOwners(getEntitlements)
                    onEntitlementsVerified?.invoke(store.ownedEntitlements)
                } else {
                    // A failed query (offline, Play Store unavailable) proves nothing: rebuilding
                    // from the empty owner lists would revoke a paying user's entitlements and
                    // show them ads. Keep the current (cached or granted) state.
                    AppLogger.w(TAG, "refresh: query failed code=$code, keeping entitlements=${store.ownedEntitlements}")
                }
                onComplete(code)
            },
        )
    }

    private fun queryBoth(
        billingClient: BillingClient?,
        onInApp: (BillingResult?, List<Purchase>) -> Unit,
        onSubs: (BillingResult?, List<Purchase>) -> Unit,
        onBothComplete: (Int) -> Unit,
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
                onBothComplete(errorCode.get())
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

    private fun acknowledgeIfNeeded(client: BillingClient?, purchase: Purchase) {
        if (purchase.isAcknowledged || client == null) return
        AppLogger.i(TAG, "Acknowledging recovered purchase: ${purchase.products}")
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        ) { result -> AppLogger.d(TAG, "Recovered acknowledge result: code=${result.responseCode} ${result.debugMessage}") }
    }

    private fun reconsume(client: BillingClient?, token: String, productId: String) {
        AppLogger.i(TAG, "Re-consuming uncompleted consumable: $productId")
        client?.consumeAsync(
            ConsumeParams.newBuilder().setPurchaseToken(token).build()
        ) { result, _ -> AppLogger.d(TAG, "Re-consume result: ${result.debugMessage}") }
    }

    private fun Purchase.isPurchased(): Boolean = purchaseState == Purchase.PurchaseState.PURCHASED

    private val BillingResult?.isOk: Boolean
        get() = this?.responseCode == BillingClient.BillingResponseCode.OK

    companion object {
        private const val TAG = "GMA_Purchase"
    }
}
