package com.lib.ads.gma.ads.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility object for time-related operations.
 */
object TimeUtils {

    
    fun formatCurrentTime(): String {
        val millis = System.currentTimeMillis()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}
