package com.lib.ads.gma.ads.billing

class PurchaseItem @JvmOverloads constructor(
    var itemId: String,
    var type: Int,
    var trialId: String? = null,
    var consume: Boolean = false,
    var entitlements: Set<Entitlement> = setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES)
) {
    constructor(itemId: String, trialId: String, type: Int) : this(
        itemId = itemId,
        type = type,
        trialId = trialId,
        consume = false
    )

    companion object {
        /** SKU that only removes ads — premium features stay locked. */
        @JvmStatic
        @JvmOverloads
        fun removeAdsOnly(itemId: String, type: Int, trialId: String? = null, consume: Boolean = false) =
            PurchaseItem(itemId, type, trialId, consume, setOf(Entitlement.REMOVE_ADS))

        /** SKU that only unlocks premium features — ads keep showing. */
        @JvmStatic
        @JvmOverloads
        fun premiumFeaturesOnly(itemId: String, type: Int, trialId: String? = null, consume: Boolean = false) =
            PurchaseItem(itemId, type, trialId, consume, setOf(Entitlement.PREMIUM_FEATURES))

        /** SKU that grants both — removes ads and unlocks premium features. */
        @JvmStatic
        @JvmOverloads
        fun bundle(itemId: String, type: Int, trialId: String? = null, consume: Boolean = false) =
            PurchaseItem(itemId, type, trialId, consume, setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES))
    }
}
