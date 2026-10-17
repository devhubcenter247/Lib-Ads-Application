package com.lib.ads.gma.ads.helper.utils

/**
 * Same enum as gma-lib's. adlib's native helpers referenced it without the module defining it,
 * so adlib did not compile; the two modules are never on one classpath, so the copy is safe.
 */
enum class AdOptionVisibility {
    GONE,
    INVISIBLE
}
