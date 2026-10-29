package com.lib.ads.gma.ads.event

import android.content.Context
import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustThirdPartySharing
import com.google.firebase.analytics.FirebaseAnalytics
import com.lib.ads.gma.ads.util.SharePreferenceUtils

/**
 * Forwards the UMP (IAB TCF v2) decision to Firebase Consent Mode v2 and Adjust's Google DMA
 * options. Mapping follows Google's TCF → consent-mode inference:
 * ad_storage ← purpose 1, ad_user_data ← purposes 1 + 7, ad_personalization ← purposes 3 + 4.
 * analytics_storage is left to the app — TCF has no purpose for it.
 */
internal object ConsentSignals {
    private const val TAG = "ConsentSignals"

    data class Dma(
        val eea: Boolean,
        val adStorage: Boolean,
        val adUserData: Boolean,
        val adPersonalization: Boolean,
    ) {
        val signature: String get() = listOf(eea, adStorage, adUserData, adPersonalization).joinToString(",")
    }

    /**
     * @param gdprApplies `IABTCF_gdprApplies`: 1 in scope, 0 out of scope, anything else unknown.
     * @param purposeConsents `IABTCF_PurposeConsents`, one '0'/'1' per purpose starting at purpose 1.
     * @return null when there is no decision yet (no CMP data) — callers must then change nothing.
     */
    fun fromTcf(gdprApplies: Int, purposeConsents: String?): Dma? = when (gdprApplies) {
        0 -> Dma(eea = false, adStorage = true, adUserData = true, adPersonalization = true)
        1 -> {
            fun purpose(n: Int) = purposeConsents?.getOrNull(n - 1) == '1'
            Dma(
                eea = true,
                adStorage = purpose(1),
                adUserData = purpose(1) && purpose(7),
                adPersonalization = purpose(3) && purpose(4),
            )
        }
        else -> null
    }

    fun read(context: Context): Dma? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
        val gdprApplies = runCatching { prefs.getInt("IABTCF_gdprApplies", -1) }.getOrDefault(-1)
        val purposes = runCatching { prefs.getString("IABTCF_PurposeConsents", null) }.getOrNull()
        return fromTcf(gdprApplies, purposes)
    }

    /**
     * Applies the current decision. Firebase is set on every call (cheap, process-local); Adjust
     * only when the decision changed, since each call is a tracked third-party-sharing event.
     */
    fun apply(context: Context, adjustEnabled: Boolean) {
        val dma = read(context) ?: return
        val appContext = context.applicationContext
        runCatching {
            val granted = FirebaseAnalytics.ConsentStatus.GRANTED
            val denied = FirebaseAnalytics.ConsentStatus.DENIED
            FirebaseAnalytics.getInstance(appContext).setConsent(
                mapOf(
                    FirebaseAnalytics.ConsentType.AD_STORAGE to if (dma.adStorage) granted else denied,
                    FirebaseAnalytics.ConsentType.AD_USER_DATA to if (dma.adUserData) granted else denied,
                    FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to if (dma.adPersonalization) granted else denied,
                )
            )
        }.onFailure { Log.w(TAG, "Firebase setConsent failed: ${it.message}") }

        if (!adjustEnabled || SharePreferenceUtils.getConsentSignature(appContext) == dma.signature) return
        runCatching {
            Adjust.trackThirdPartySharing(AdjustThirdPartySharing(null).apply {
                addGranularOption("google_dma", "eea", dma.eea.flag())
                addGranularOption("google_dma", "ad_personalization", dma.adPersonalization.flag())
                addGranularOption("google_dma", "ad_user_data", dma.adUserData.flag())
            })
            SharePreferenceUtils.setConsentSignature(appContext, dma.signature)
        }.onFailure { Log.w(TAG, "Adjust third-party sharing failed: ${it.message}") }
    }

    private fun Boolean.flag() = if (this) "1" else "0"
}
