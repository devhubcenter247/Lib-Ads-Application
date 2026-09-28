package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.lib.ads.gma.ads.util.AppLogger

/** Small adapter around BillingClient purchase queries. */
internal class PurchaseQueryService {
    fun query(
        client: BillingClient?,
        productType: String,
        callback: (BillingResult?, List<Purchase>) -> Unit,
    ) {
        if (client == null) {
            AppLogger.w(TAG, "query: skipped productType=$productType because BillingClient is null")
            callback(null, emptyList())
            return
        }
        AppLogger.d(TAG, "query: productType=$productType")
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(productType).build(),
        ) { result, purchases ->
            AppLogger.d(TAG, "query: productType=$productType code=${result.responseCode}, purchases=${purchases.size}, message=${result.debugMessage}")
            callback(result, purchases)
        }
    }

    fun queryBoth(
        client: BillingClient?,
        onInApp: (BillingResult?, List<Purchase>) -> Unit,
        onSubscriptions: (BillingResult?, List<Purchase>) -> Unit,
    ) {
        query(client, BillingClient.ProductType.INAPP, onInApp)
        query(client, BillingClient.ProductType.SUBS, onSubscriptions)
    }

    companion object {
        private const val TAG = "GMA_Purchase"
    }
}
