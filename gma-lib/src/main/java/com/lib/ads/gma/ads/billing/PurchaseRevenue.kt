package com.lib.ads.gma.ads.billing

/** Pure purchase → revenue rules, free of Billing types so they are unit-testable. */
internal object PurchaseRevenue {

    data class Price(val micros: Long, val currency: String)

    data class OfferPricing(
        val offerToken: String,
        /** null for the base plan's own offer. */
        val offerId: String?,
        /** Pricing phases in charge order. */
        val phases: List<Price>,
    )

    /**
     * The product a Play purchase is for. A purchase reported by the listener can belong to a flow
     * other than the one this process started (pending completion, promo code, restore after
     * process death), so the purchase's own product list wins over the "current" product.
     */
    fun resolveProductId(purchaseProducts: List<String>, isConfigured: (String) -> Boolean, fallback: String): String =
        purchaseProducts.firstOrNull(isConfigured) ?: purchaseProducts.firstOrNull() ?: fallback

    /**
     * What the user is charged when the subscription starts: the first phase of the offer they
     * bought. A free trial's first phase is 0 — that is no revenue, and reporting the regular price
     * there overstated revenue for every trial start. [offerToken] null (offer unknown) falls back
     * to the base plan offer.
     */
    fun subscriptionStartPrice(offers: List<OfferPricing>, offerToken: String?): Price? {
        val offer = offerToken?.let { token -> offers.firstOrNull { it.offerToken == token } }
            ?: offers.firstOrNull { it.offerId == null }
            ?: return null
        return offer.phases.firstOrNull()
    }
}
