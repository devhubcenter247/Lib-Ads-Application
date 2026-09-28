package com.lib.ads.gma.ads.billing

/**
 * Flat, UI-ready snapshot of a product fetched from Play Store.
 * Build via [AppPurchase.getProductInfoList].
 */
data class BillingProductInfo(
    val productId: String,
    val name: String,
    /** [AppPurchase.TYPE_IAP.PURCHASE] or [AppPurchase.TYPE_IAP.SUBSCRIPTION] */
    val type: Int,

    // ── In-app purchase fields (type == PURCHASE) ──────────────────────────
    val price: String = "",
    val priceMicros: Long = 0L,
    val currency: String = "",

    // ── Subscription fields (type == SUBSCRIPTION) ─────────────────────────
    val regularPrice: String = "",
    val regularPriceMicros: Long = 0L,
    val billingPeriod: String = "",
    val introPrice: String = "",
    val introPriceMicros: Long = 0L,
    val introBillingPeriod: String = "",
    val introCycles: Int = 0,
    val trialPeriod: String = "",
    val promoOfferId: String? = null,
    val promoOfferToken: String = "",
    val baseOfferToken: String = "",
) {
    val hasPromo: Boolean get() = promoOfferId != null
    val hasTrial: Boolean get() = trialPeriod.isNotEmpty()
    val hasIntroPrice: Boolean get() = introPriceMicros > 0
}
