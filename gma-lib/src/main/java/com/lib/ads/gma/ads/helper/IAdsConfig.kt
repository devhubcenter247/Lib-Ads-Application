package com.lib.ads.gma.ads.helper

interface IAdsConfig {
    val idAds: String
    val canShowAds: Boolean
    val canReloadAds: Boolean

    /**
     * AdMob Ad Placements id (`AdRequest.setPlacementId` / `Ad.placementId`) — tags every
     * request/impression built from this config with a UI-location identifier, separate from the
     * ad unit id, so show rate/eCPM can be compared per placement in the AdMob console without
     * creating one ad unit per placement. `null` (the default) omits it — no behavior change.
     */
    val placementId: Long? get() = null
}
