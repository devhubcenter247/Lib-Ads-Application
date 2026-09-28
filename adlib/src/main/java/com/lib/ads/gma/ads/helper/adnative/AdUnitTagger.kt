package com.lib.ads.gma.ads.helper.adnative

import java.util.Collections
import java.util.WeakHashMap

object AdUnitTagger {
    private val map = Collections.synchronizedMap(WeakHashMap<Any, String>())

    fun tag(ad: Any, adUnitId: String) {
        map[ad] = adUnitId
    }

    fun idOf(ad: Any): String? = map[ad]

}
