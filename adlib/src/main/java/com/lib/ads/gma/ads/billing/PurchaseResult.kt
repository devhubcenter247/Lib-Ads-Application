package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.Purchase

/**
 * Represents the result of a purchase transaction.
 */
data class PurchaseResult(
    var orderId: String?,
    var packageName: String,
    var productId: List<String>,
    var purchaseTime: Long,
    var purchaseState: Int,
    var purchaseToken: String,
    var quantity: Int,
    var isAutoRenewing: Boolean,
    var isAcknowledged: Boolean
) {
    companion object {
        
        fun fromPurchase(purchase: Purchase): PurchaseResult {
            return PurchaseResult(
                orderId = purchase.orderId,
                packageName = purchase.packageName,
                productId = purchase.products,
                purchaseTime = purchase.purchaseTime,
                purchaseState = purchase.purchaseState,
                purchaseToken = purchase.purchaseToken,
                quantity = purchase.quantity,
                isAutoRenewing = purchase.isAutoRenewing,
                isAcknowledged = purchase.isAcknowledged
            )
        }
    }
}
