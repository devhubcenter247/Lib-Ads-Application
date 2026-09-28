package com.lib.ads.gma.ads.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.facebook.appevents.AppEventsLogger
import com.facebook.appevents.FlushResult

/**
 * BroadcastReceiver for Facebook AppEvents flush callbacks.
 */
class FacebookBroadcastReceiver : BroadcastReceiver() {

    private var callback: FacebookBroadcastReceiverCallback? = null

    fun setFacebookBroadcastReceiver(callback: FacebookBroadcastReceiverCallback?) {
        this.callback = callback
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AppEventsLogger.ACTION_APP_EVENTS_FLUSHED) {
            @Suppress("DEPRECATION")
            val result = intent.getSerializableExtra(AppEventsLogger.APP_EVENTS_EXTRA_FLUSH_RESULT) as? FlushResult
            result?.let { callback?.callback(it) }
        }
    }

    fun interface FacebookBroadcastReceiverCallback {
        fun callback(result: FlushResult)
    }
}
