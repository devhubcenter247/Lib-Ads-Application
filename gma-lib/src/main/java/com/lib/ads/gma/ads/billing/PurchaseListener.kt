package com.lib.ads.gma.ads.billing

interface PurchaseListener {
    fun onProductPurchased(productId: String?, transactionDetails: String)
    fun displayErrorMessage(errorMsg: String)
    fun onUserCancelBilling()
}
fun interface UpdatePurchaseListener {
    fun onUpdateFinished()
}

