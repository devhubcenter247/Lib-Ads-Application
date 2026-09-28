package com.lib.ads.gma.ads.helper.adnative

internal fun String.directionalAdLabel(): String {
    val genericTokens = setOf(
        "ad",
        "ads",
        "native",
        "banner",
        "interstitial",
        "reward",
        "rewarded",
        "open",
        "app",
        "preload",
        "compose",
        "test",
        "unit",
        "id",
        "full",
        "screen",
        "fullscreen"
    )
    val tokens = split('_', '-', '.', '/', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val directionalTokens = tokens.filterNot { it.lowercase() in genericTokens }
    val labelTokens = (directionalTokens.ifEmpty { tokens }).takeLast(2)
    return labelTokens.joinToString(" > ").ifBlank { this }
}
