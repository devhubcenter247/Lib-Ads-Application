package com.lib.ads.gma.ads.billing

sealed class BillingState {
    data object Disconnected : BillingState()
    data object Connecting : BillingState()
    data object Connected : BillingState()
    data class Error(val responseCode: Int, val message: String) : BillingState()
}
