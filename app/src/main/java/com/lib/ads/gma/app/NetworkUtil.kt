package com.lib.ads.gma.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Utility class for network connectivity checks.
 */
object NetworkUtil {

    private var context: Context? = null

    
    fun initNetwork(context: Context) {
        this.context = context.applicationContext
    }

    
    fun isOnline(): Boolean {
        val ctx = context ?: return false
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val netInfo = connectivityManager.activeNetworkInfo
            netInfo != null && netInfo.isConnected
        }
    }
}
