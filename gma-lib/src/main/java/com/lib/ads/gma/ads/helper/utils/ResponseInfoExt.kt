package com.lib.ads.gma.ads.helper.extension

import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo

/**
 * Parses the AdMob ad unit out of the filled source's credentials. Only works when AdMob itself
 * served the impression and the unit uses the `ca-app-pub-…/…` form: a mediated fill (Meta,
 * Pangle, …) carries the partner's credentials and an Ad Manager unit (`/123/x`) never matches.
 * Prefer [adUnitIdOrEmpty] whenever the [Ad] is at hand.
 */
fun ResponseInfo.extractAdUnitIdOrNull(): String? {
    val pubid = loadedAdSourceResponseInfo?.credentials?.getString("pubid") ?: return null
    return Regex("(ca-app-pub-\\d+/\\d+)").find(pubid)?.groupValues?.getOrNull(1)
}

/** The ad unit this ad was requested with — correct for mediated fills and Ad Manager units. */
fun Ad.adUnitIdOrEmpty(): String =
    runCatching { adUnitId }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: getResponseInfo().extractAdUnitIdOrNull().orEmpty()
