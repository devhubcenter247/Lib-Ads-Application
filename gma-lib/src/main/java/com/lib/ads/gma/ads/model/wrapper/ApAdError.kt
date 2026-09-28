package com.lib.ads.gma.ads.model.wrapper

import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

class ApAdError {
    private var loadAdError: LoadAdError? = null
    private var fullScreenContentError: FullScreenContentError? = null
    private var errorMessage: String = ""

    @JvmField var message: String = ""

    constructor(loadAdError: LoadAdError) {
        this.loadAdError = loadAdError
        this.message = loadAdError.message
    }

    constructor(fullScreenContentError: FullScreenContentError) {
        this.fullScreenContentError = fullScreenContentError
        this.message = fullScreenContentError.message
    }

    constructor(message: String) {
        this.errorMessage = message
        this.message = message
    }

    fun getMessage(): String {
        loadAdError?.let { return it.message }
        fullScreenContentError?.let { return it.message }
        if (errorMessage.isNotEmpty()) return errorMessage
        return "unknown error"
    }

    /** Diagnostic representation suitable for logs; keeps the underlying GMA error details. */
    fun debugDescription(): String = when {
        loadAdError != null -> "message=${loadAdError!!.message} sdkError=$loadAdError"
        fullScreenContentError != null -> "message=${fullScreenContentError!!.message} sdkError=$fullScreenContentError"
        errorMessage.isNotEmpty() -> "message=$errorMessage"
        else -> "message=unknown error"
    }

    /**
     * Per-ad-unit failures of the waterfall that produced this error, one `"<unit>: <reason>"`
     * entry per id tried, in order. Empty for a single-id load.
     */
    var causes: List<String> = emptyList()
        internal set

    /** One-line reason for UI/debug overlays, e.g. `NO_FILL(3): No fill.` or `timeout 10000ms`. */
    fun shortDescription(): String {
        loadAdError?.let { return "${it.code.name}(${it.code.value}): ${it.message}" }
        return getMessage()
    }

    /** [causes] when the waterfall recorded them, else [shortDescription]. */
    fun diagnosticLines(): List<String> = causes.ifEmpty { listOf(shortDescription()) }

    override fun toString(): String = debugDescription()

}

/** Attaches waterfall [ApAdError.causes] (copied) and returns this error. */
internal fun ApAdError.withCauses(failures: List<String>): ApAdError = apply {
    if (failures.size > 1) causes = failures.toList()
}

/** Ad unit id shortened for overlays/logs: the part after the publisher id (`/1234567890`). */
internal fun shortAdUnit(adUnitId: String): String = "/" + adUnitId.substringAfterLast('/')
