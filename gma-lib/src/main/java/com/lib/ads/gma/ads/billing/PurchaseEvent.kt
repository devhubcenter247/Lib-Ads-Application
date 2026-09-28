package com.lib.ads.gma.ads.billing

sealed class PurchaseEvent {

    data class Success(
        val orderId: String?,
        val productId: String,
        val originalJson: String
    ) : PurchaseEvent()

    data object Cancelled : PurchaseEvent()

    data class Pending(val productId: String) : PurchaseEvent()

    data class Error(val code: Int, val message: String) : PurchaseEvent()
}
