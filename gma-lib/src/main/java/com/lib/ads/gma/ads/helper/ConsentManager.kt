package com.lib.ads.gma.ads.helper

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.lib.ads.gma.ads.engine.AdsProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Single UMP consent manager used by both the public facade and the GMA-backed helpers. */
class ConsentManager(private val activity: Activity) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(activity.applicationContext)

    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    suspend fun requestUMP() = requestConsent(activity)

    suspend fun loadAndShowConsentForm() = requestConsent(activity)

    suspend fun showPrivacyOption() = suspendCancellableCoroutine<Unit> { continuation ->
        showPrivacyOptionsForm(activity) { continuation.resume(Unit) }
    }

    fun getCanRequestAd(): Boolean = canRequestAds

    /**
     * Development builds only: forgets the stored consent so the form shows again on the next
     * [requestUMP]. Does nothing in production builds.
     */
    fun resetConsentForDebug() {
        if (AdsProvider.getInstance().adConfigOrNull?.isVariantDev != true) return
        consentInformation.reset()
    }

    /** Debug geography / test devices from AdSdkConfig, applied in development builds only. */
    private fun buildRequestParameters(): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        val config = AdsProvider.getInstance().adConfigOrNull
        if (config != null && config.isVariantDev &&
            (config.consentDebugGeography != null || config.consentTestDeviceHashedIds.isNotEmpty())
        ) {
            val debug = ConsentDebugSettings.Builder(activity)
            config.consentDebugGeography?.let(debug::setDebugGeography)
            config.consentTestDeviceHashedIds.forEach { debug.addTestDeviceHashedId(it) }
            builder.setConsentDebugSettings(debug.build())
        }
        return builder.build()
    }

    /**
     * `requestConsentInfoUpdate` can fail transiently (e.g. no network yet at cold start)
     * separately from the actual consent decision. On failure it retries [maxRetries] more
     * times with a short delay instead of silently falling back to whatever [canRequestAds]
     * happened to be cached from a previous run.
     */
    private suspend fun requestConsent(
        activity: Activity,
        maxRetries: Int = 2,
        retryDelayMs: Long = 1000L,
    ) {
        repeat(maxRetries + 1) { attempt ->
            val updated = suspendCancellableCoroutine<Boolean> { continuation ->
                val params = buildRequestParameters()

                consentInformation.requestConsentInfoUpdate(
                    activity,
                    params,
                    {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                    },
                    { formError ->
                        Log.w(
                            "ConsentManager",
                            "requestConsentInfoUpdate failed (attempt ${attempt + 1}/${maxRetries + 1}): ${formError.message}"
                        )
                        if (continuation.isActive) continuation.resume(false)
                    }
                )
            }
            if (updated) {
                AdsProvider.getInstance().onConsentUpdated(activity)
                return
            }
            if (attempt < maxRetries) delay(retryDelayMs)
        }
    }

    fun showPrivacyOptionsForm(
        activity: Activity,
        onConsentFormDismissedListener: ConsentForm.OnConsentFormDismissedListener
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            AdsProvider.getInstance().onConsentUpdated(activity)
            onConsentFormDismissedListener.onConsentFormDismissed(formError)
        }
    }

    companion object {
        fun getConsentResult(context: Context): Boolean =
            UserMessagingPlatform.getConsentInformation(context.applicationContext).canRequestAds()

        fun isCMPConsent(context: Context): Boolean {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(
                "${appContext.packageName}_preferences", Context.MODE_PRIVATE
            )
            if (prefs.getInt("IABTCF_gdprApplies", -1) == 0) return true
            return prefs.getString("IABTCF_PurposeConsents", null)?.firstOrNull() == '1'
        }
    }
}
