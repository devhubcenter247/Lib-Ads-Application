package com.lib.ads.gma.ads.util

import android.util.Log


object AppLogger {
    var isEnabled: Boolean = false
    fun d(tag: String?, message: String) {
        if (isEnabled) Log.d(tag, message)
    }

    fun i(tag: String?, message: String) {
        if (isEnabled) Log.i(tag, message)
    }

    fun w(tag: String?, message: String) {
        if (isEnabled) Log.w(tag, message)
    }

    fun e(tag: String?, message: String) {
        if (isEnabled) Log.e(tag, message)
    }

    fun v(tag: String?, message: String) {
        if (isEnabled) Log.v(tag, message)
    }
}