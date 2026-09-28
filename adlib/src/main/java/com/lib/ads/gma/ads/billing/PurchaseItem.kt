package com.lib.ads.gma.ads.billing

/**
 * Represents an item available for purchase.
 *
 * @param itemId The product ID.
 * @param type The type of purchase (in-app or subscription).
 * @param trialId Optional trial ID.
 * @param entitlements What owning this product grants. Defaults to both — a plain bundle SKU
 *   that removes ads and unlocks premium features, matching the SDK's historical single-flag
 *   behavior. Pass a narrower set (e.g. `setOf(Entitlement.PREMIUM_FEATURES)`) for a
 *   features-only SKU that should NOT suppress ads, or `setOf(Entitlement.REMOVE_ADS)` for an
 *   ads-only SKU.
 */
data class PurchaseItem @JvmOverloads constructor(
    var itemId: String,
    var type: Int,
    var trialId: String? = null,
    var entitlements: Set<Entitlement> = setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES)
) {
    companion object {
        /** SKU that only removes ads — premium features stay locked. */
        @JvmStatic
        @JvmOverloads
        fun removeAdsOnly(itemId: String, type: Int, trialId: String? = null) =
            PurchaseItem(itemId, type, trialId, setOf(Entitlement.REMOVE_ADS))

        /** SKU that only unlocks premium features — ads keep showing. */
        @JvmStatic
        @JvmOverloads
        fun premiumFeaturesOnly(itemId: String, type: Int, trialId: String? = null) =
            PurchaseItem(itemId, type, trialId, setOf(Entitlement.PREMIUM_FEATURES))

        /** SKU that grants both — removes ads and unlocks premium features. */
        @JvmStatic
        @JvmOverloads
        fun bundle(itemId: String, type: Int, trialId: String? = null) =
            PurchaseItem(itemId, type, trialId, setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES))
    }
}
