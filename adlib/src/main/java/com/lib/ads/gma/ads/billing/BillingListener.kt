package com.lib.ads.gma.ads.billing

/**
 * Callback interface for billing initialization events.
 */
fun interface BillingListener {
    fun onInitBillingFinished(resultCode: Int)
}
