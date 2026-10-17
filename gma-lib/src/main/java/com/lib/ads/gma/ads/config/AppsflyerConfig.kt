package com.lib.ads.gma.ads.config

import com.lib.ads.gma.ads.event.AdsTrackEvent

class AppsflyerConfig(
    var enableAppsflyer: Boolean = false,
    var devKey: String = "",
    var events: Set<AdsTrackEvent> = AdsTrackEvent.DEFAULT,
    var alreadyInitialized: Boolean = false,
) {
    constructor(
        enableAppsflyer: Boolean,
        devKey: String,
        alreadyInitialized: Boolean,
    ) : this(enableAppsflyer, devKey, AdsTrackEvent.DEFAULT, alreadyInitialized)

    /** Lets AppsFlyer read the UMP/TCF consent string (DMA). Applied before `start()`. */
    var enableTCFDataCollection: Boolean = true
}
