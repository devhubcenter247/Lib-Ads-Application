package com.lib.ads.gma.ads.ads.wrapper

import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError

/**
 * Wrapper class for ad errors, supporting both LoadAdError and AdError.
 */
class ApAdError {
    private var loadAdError: LoadAdError? = null
    private var adError: AdError? = null
    private var customMessage: String = ""

    constructor(adError: AdError?) {
        this.adError = adError
    }

    constructor(loadAdError: LoadAdError?) {
        this.loadAdError = loadAdError
    }

    constructor(message: String) {
        this.customMessage = message
    }

    /**
     * Sets a custom error message.
     */
    fun setMessage(message: String) {
        this.customMessage = message
    }

    /**
     * Gets the error message from any available source.
     */
    val message: String
        @JvmName("getErrorMessage")
        get() = loadAdError?.message
            ?: adError?.message
            ?: customMessage.takeIf { it.isNotEmpty() }
            ?: "unknown error"

    // Java compatibility - keeping the original method signature
    fun getMessage(): String = message

    /** Diagnostic representation suitable for logs; keeps the underlying GMA error details. */
    fun debugDescription(): String = when {
        loadAdError != null -> "message=${loadAdError!!.message} sdkError=$loadAdError"
        adError != null -> "message=${adError!!.message} sdkError=$adError"
        customMessage.isNotEmpty() -> "message=$customMessage"
        else -> "message=unknown error"
    }

    override fun toString(): String = debugDescription()
}
