package com.lib.ads.gma.ads.util

enum class AdFormat {
    NATIVE,
    BANNER,
    INTERSTITIAL,
    APP_OPEN,
    REWARDED,
}

/** Structured logger for correlating one ad's lifecycle timeline by ad format and key. */
object AdLogger {
    fun tag(format: AdFormat): String = "GMA_${format.name}"

    fun d(format: AdFormat, adKey: String, event: String, message: String = "") {
        AppLogger.d(tag(format), format(adKey, event, message))
    }

    fun w(format: AdFormat, adKey: String, event: String, message: String = "") {
        AppLogger.w(tag(format), format(adKey, event, message))
    }

    fun e(format: AdFormat, adKey: String, event: String, message: String = "") {
        AppLogger.e(tag(format), format(adKey, event, message))
    }

    private fun format(adKey: String, event: String, message: String): String = buildString {
        append("adKey=").append(adKey).append(" event=").append(event)
        if (message.isNotBlank()) append(' ').append(message)
    }
}
