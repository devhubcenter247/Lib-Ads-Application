package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.Purchase

class PurchaseResult(
    var orderId: String?,
    var packageName: String?,
    var productId: List<String>?,
    var purchaseTime: Long,
    var purchaseState: Int,
    var purchaseToken: String?,
    var quantity: Int,
    var autoRenewing: Boolean,
    var acknowledged: Boolean
) {
    companion object {
        @JvmStatic
        fun fromPurchase(purchase: Purchase): PurchaseResult = PurchaseResult(
            orderId = purchase.orderId,
            packageName = purchase.packageName,
            productId = purchase.products,
            purchaseTime = purchase.purchaseTime,
            purchaseState = purchase.purchaseState,
            purchaseToken = purchase.purchaseToken,
            quantity = purchase.quantity,
            autoRenewing = purchase.isAutoRenewing,
            acknowledged = purchase.isAcknowledged
        )
    }
}
