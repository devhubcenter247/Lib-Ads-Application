package com.lib.ads.gma.ads.helper.extension

import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo

fun ResponseInfo.extractAdUnitIdOrNull(): String? {
    val pubid = loadedAdSourceResponseInfo?.credentials?.getString("pubid") ?: return null
    return Regex("(ca-app-pub-\\d+/\\d+)").find(pubid)?.groupValues?.getOrNull(1)
}
