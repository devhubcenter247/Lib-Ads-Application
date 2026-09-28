package com.lib.ads.gma.ads.admob

typealias UmpErrorCode = Int

enum class UmpError {
    INTERNAL_ERROR,
    INTERNET_ERROR,
    INVALID_OPERATION,
    TIME_OUT,
    ACTIVITY_DESTROYED,
    ;
}

internal fun UmpErrorCode.toUmpError(): UmpError {
    return when (this) {
        1 -> UmpError.INTERNAL_ERROR
        2 -> UmpError.INTERNET_ERROR
        3 -> UmpError.INVALID_OPERATION
        4 -> UmpError.TIME_OUT
        else -> throw IllegalArgumentException("Unknown umpErrorCode $this")
    }
}
