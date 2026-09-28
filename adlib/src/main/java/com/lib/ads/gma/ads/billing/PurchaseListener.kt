package com.lib.ads.gma.ads.billing

/**
 * Callback interface for purchase events.
 */
interface PurchaseListener {
    fun onProductPurchased(productId: String?, transactionDetails: String)
    fun displayErrorMessage(errorMsg: String)
    fun onUserCancelBilling()
}
