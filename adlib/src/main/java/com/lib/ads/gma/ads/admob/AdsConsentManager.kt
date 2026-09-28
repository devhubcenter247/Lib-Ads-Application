package com.lib.ads.gma.ads.admob

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.os.bundleOf
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.lib.ads.gma.ads.manager.AdSdkInitializer
import com.lib.ads.gma.ads.event.FirebaseAnalytics
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdsConsentManager(private val activity: Activity) {
    companion object {
        const val TAG = "AdsConsentManager"

        private fun getConsentPreferences(context: Context) =
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

        fun isCMPConsent(context: Context): Boolean =
            getConsentResultStatus(context) == 1

        fun getConsentResult(context: Context): Boolean {
            val umpEnabled = AdSdkInitializer.adConfig?.adsConsentConfig?.enableUMP == true
            if (!umpEnabled) return true

            val canRequestAds = UserMessagingPlatform
                .getConsentInformation(context.applicationContext)
                .canRequestAds()
            Log.d(TAG, "canRequestAds: $canRequestAds")
            return canRequestAds
        }

        fun getConsentResultStatus(context: Context): Int {
            val prefs = getConsentPreferences(context)
            val purposeConsents = prefs.getString("IABTCF_PurposeConsents", "").orEmpty()
            val vendorConsents = prefs.getString("IABTCF_VendorConsents", "").orEmpty()
            val bundle = bundleOf(
                "ump_manage_opt_purpose_consent" to purposeConsents.count { it == '1' },
                "ump_manage_opt_vendor_consent" to vendorConsents.count { it == '1' },
            )
            FirebaseAnalytics.logEventTracking(context, "ump_consent_result_status", bundle)
            return purposeConsents.firstOrNull()?.digitToIntOrNull() ?: -1
        }
    }

    private val consentInformation by lazy {
        UserMessagingPlatform.getConsentInformation(activity.applicationContext)
    }

    private fun isActivityAlive(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    suspend fun requestUMP(
        enableDebug: Boolean = false,
        testDevice: String = "",
        resetData: Boolean = false,
    ): Either<UmpError, Unit> {
        if (!isActivityAlive()) return UmpError.ACTIVITY_DESTROYED.left()

        if (AdSdkInitializer.adConfig?.adsConsentConfig?.enableUMP != true) {
            AdSdkInitializer.initAdsNetwork()
            return Unit.right()
        }

        val builder = ConsentRequestParameters.Builder()
        if (enableDebug) {
            val debugBuilder = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            if (testDevice.isNotBlank()) {
                debugBuilder.addTestDeviceHashedId(testDevice)
            }
            val debugSettings = debugBuilder.build()
            builder.setConsentDebugSettings(debugSettings)
        }
        val isUnderAgeOfConsent =
            AdSdkInitializer.adConfig?.tagForUnderAgeOfConsent == 1
        val params = builder
            .setTagForUnderAgeOfConsent(isUnderAgeOfConsent)
            .build()

        // UMP reset is a test-only API. Never clear a production user's saved choice.
        if (enableDebug && resetData) {
            consentInformation.reset()
        }

        return consentInformation
            .coRequestConsentInfoUpdate(activity, params)
            .flatMap {
                if (!isActivityAlive()) return@flatMap Unit.right()
                coLoadAndShowConsentFormIfRequired()
            }
            .also {
                // An update/form error can still leave a valid choice from a previous
                // session. UMP is the source of truth for whether requests are allowed.
                if (consentInformation.canRequestAds()) {
                    AdSdkInitializer.initAdsNetwork()
                }
            }
            .logConsentGatheringFailed("ump_request_failed")
            .mapLeftUmpError()
    }

    suspend fun loadAndShowConsentForm(): Either<UmpError, Unit> {
        if (!isActivityAlive()) return UmpError.ACTIVITY_DESTROYED.left()
        return privateLoadAndShowConsentForm()
            .logConsentGatheringFailed("ump_request_failed")
            .mapLeftUmpError()
    }

    private suspend fun privateLoadAndShowConsentForm(): Either<FormError, Unit> {
        return coLoadAndShowConsentFormIfRequired()
            .logConsentResult()
    }

    suspend fun showPrivacyOption(): Either<UmpError, Unit> {
        if (!isActivityAlive()) return UmpError.ACTIVITY_DESTROYED.left()
        return coShowPrivacyOptionsForm()
            .also {
                if (isActivityAlive() && consentInformation.canRequestAds()) {
                    AdSdkInitializer.initAdsNetwork()
                }
            }
            .logConsentResult()
            .mapLeftUmpError()
    }

    private suspend fun coShowPrivacyOptionsForm(): Either<FormError, Unit> {
        if (!isActivityAlive()) return Unit.right()
        return suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.showPrivacyOptionsForm(
                activity
            ) { error ->
                if (continuation.isActive) {
                    val resumeWith = error?.left() ?: Unit.right()
                    continuation.resume(resumeWith)
                }
            }
        }
    }

    private fun <R> Either<FormError, R>.logConsentGatheringFailed(
        eventName: String,
    ): Either<FormError, R> = onLeft {
        Log.w(TAG, "%s: %s".format(it.errorCode, it.message))
        val bundle = bundleOf(
            "error_code" to it.errorCode,
            "error_msg" to it.message,
        )
        FirebaseAnalytics.logEventTracking(activity, eventName, bundle)
    }

    private fun <R> Either<FormError, R>.logConsentResult(): Either<FormError, R> = this
        .logConsentGatheringFailed("ump_consent_failed")
        .also {
            if (isActivityAlive()) {
                val bundle = bundleOf(
                    "consent" to isCMPConsent(activity)
                )
                FirebaseAnalytics.logEventTracking(
                    activity,
                    "ump_consent_result",
                    bundle
                )
            }
        }

    fun getCanRequestAd(): Boolean {
        val canRequestAd = getConsentResult(activity)
        if (canRequestAd) {
            AdSdkInitializer.initAdsNetwork()
        }
        return canRequestAd
    }

    private suspend fun coLoadAndShowConsentFormIfRequired(): Either<FormError, Unit> {
        if (!isActivityAlive()) return Unit.right()
        return suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (continuation.isActive) {
                    continuation.resume(error?.left() ?: Unit.right())
                }
            }
        }
    }
}

private fun <R> Either<FormError, R>.mapLeftUmpError(): Either<UmpError, R> = mapLeft {
    it.errorCode.toUmpError()
}

private suspend fun ConsentInformation.coRequestConsentInfoUpdate(
    activity: Activity,
    parameters: ConsentRequestParameters,
): Either<FormError, Unit> = suspendCancellableCoroutine { continuation ->
    requestConsentInfoUpdate(
        activity,
        parameters,
        {
            if (continuation.isActive) {
                continuation.resume(Unit.right())
            }
        },
        { error ->
            if (continuation.isActive) {
                continuation.resume(error.left())
            }
        }
    )
}
