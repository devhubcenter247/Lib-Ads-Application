package com.lib.ads.gma.ads.billing

/**
 * What owning a product actually unlocks. A single [PurchaseItem] can grant one or both —
 * a "remove ads only" SKU, a "premium features only" SKU, or a bundle SKU granting both.
 */
enum class Entitlement {
    /** Suppresses ad requests/shows across the SDK — this is exactly what [AppPurchase.isPurchased] gates on. */
    REMOVE_ADS,

    /** Unlocks paid app features. Independent of ad suppression. */
    PREMIUM_FEATURES,
}
