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
    /** Formatted price, e.g. "263.000 ₫". Empty for subscriptions. */
    val price: String = "",
    val priceMicros: Long = 0L,
    val currency: String = "",

    // ── Subscription fields (type == SUBSCRIPTION) ─────────────────────────
    /** Regular recurring price, e.g. "263.000 ₫". */
    val regularPrice: String = "",
    val regularPriceMicros: Long = 0L,
    /** Billing period of the base plan, e.g. "P1M", "P1Y". */
    val billingPeriod: String = "",

    /** Discounted introductory price (first paid phase of the promo offer). Empty if no promo. */
    val introPrice: String = "",
    val introPriceMicros: Long = 0L,
    /** Introductory billing period, e.g. "P1M". */
    val introBillingPeriod: String = "",
    /** Number of discounted cycles, e.g. 2. 0 if no promo. */
    val introCycles: Int = 0,

    /** Free-trial period string, e.g. "P3D". Empty if no trial. */
    val trialPeriod: String = "",

    /** Offer ID of the promo offer (e.g. "promo-trial-5usd"). Null if no promo. */
    val promoOfferId: String? = null,
    /** Offer token to pass to [AppPurchase.subscribe] when applying the promo. */
    val promoOfferToken: String = "",
    /** Offer token for the base plan (no promo). */
    val baseOfferToken: String = "",
) {
    val hasPromo: Boolean get() = promoOfferId != null
    val hasTrial: Boolean get() = trialPeriod.isNotEmpty()
    val hasIntroPrice: Boolean get() = introPriceMicros > 0
}
