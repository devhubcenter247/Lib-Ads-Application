package com.lib.ads.gma.ads.event

import android.app.Application
import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerLib
import com.appsflyer.adrevenue.AppsFlyerAdRevenue
import com.appsflyer.adrevenue.adnetworks.generic.MediationNetwork
import com.appsflyer.adrevenue.adnetworks.generic.Scheme
import com.appsflyer.share.AFInAppEventParameterName
import com.appsflyer.share.AFInAppEventType
import com.appsflyer.share.attribution.AppsFlyerRequestListener
import com.google.android.gms.ads.AdValue
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.util.AdType
import java.util.Currency
import java.util.Locale

/**
 * Singleton for AppsFlyer SDK integration.
 */
class AdAppsflyer private constructor() {

    private var context: Context? = null

    companion object {
        private const val TAG = "AzAppsflyer"

        @JvmField
        var enableAppsflyer: Boolean = false

        @Volatile
        private var instance: AdAppsflyer? = null

        fun getInstance(): AdAppsflyer {
            return instance ?: synchronized(this) {
                instance ?: AdAppsflyer().also { instance = it }
            }
        }
    }

    @JvmOverloads
    fun init(context: Application, devKey: String, enableDebugLog: Boolean = false) {
        this.context = context
        AppsFlyerLib.getInstance().init(devKey, null, context)
        // SDK 7.0.0 has no start(Context) overload — only start() / start(listener).
        // The context was already supplied to init() above.
        AppsFlyerLib.getInstance().start()

        val afRevenueBuilder = AppsFlyerAdRevenue.Builder(context)
        AppsFlyerAdRevenue.initialize(afRevenueBuilder.build())

        AppsFlyerLib.getInstance().setDebugLog(enableDebugLog)
    }

    fun onTrackEventAddToCard(contentId: String) {
        val eventValues = hashMapOf<String, Any>(
            AFInAppEventParameterName.CONTENT_ID to contentId
        )

        context?.let { ctx ->
            AppsFlyerLib.getInstance().logEvent(
                ctx,
                AFInAppEventType.ADD_TO_CART,
                eventValues,
            object : AppsFlyerRequestListener {
                override fun onSuccess() {
                    Log.i(TAG, "onTrackEventAddToCard contentId:$contentId success")
                }

                override fun onError(i: Int, s: String) {
                    Log.i(TAG, "onTrackEventAddToCard contentId:$contentId error: $s")
                }
            }
            )
        }
    }

    internal fun onTrackRevenuePurchase(price: Float, currency: String, contentId: String, typeIAP: Int) {
        val type = if (typeIAP == AppPurchase.TYPE_IAP.PURCHASE) "inapp" else "subs"

        val eventValues = hashMapOf<String, Any>(
            AFInAppEventParameterName.REVENUE to price,
            AFInAppEventParameterName.CONTENT_ID to contentId,
            AFInAppEventParameterName.CURRENCY to currency,
            AFInAppEventParameterName.CONTENT_TYPE to type
        )

        context?.let { ctx ->
            AppsFlyerLib.getInstance().logEvent(
                ctx,
                AFInAppEventType.PURCHASE,
                eventValues,
            object : AppsFlyerRequestListener {
                override fun onSuccess() {
                    Log.i(TAG, "onTrackRevenuePurchase contentId:$contentId success")
                }

                override fun onError(i: Int, s: String) {
                    Log.i(TAG, "onTrackRevenuePurchase contentId:$contentId error: $s")
                }
            }
            )
        }
    }

    fun pushTrackEventAdmobNew(adValue: AdValue, idAd: String, adType: AdType, adMediationNetwork: String) {
        val monetization = when (adMediationNetwork) {
            "com.google.ads.mediation.applovin.ApplovinAdapter",
            "com.google.ads.mediation.applovin.AppLovinMediationAdapter" -> "applovin"

            "com.google.ads.mediation.fyber.FyberMediationAdapter" -> "fyber"

            "com.google.ads.mediation.inmobi.InMobiAdapter" -> "inmobi"

            "com.google.ads.mediation.ironsource.IronSourceAdapter",
            "com.google.ads.mediation.ironsource.IronSourceRewardedAdapter" -> "ironsource"

            "com.vungle.mediation.VungleInterstitialAdapter",
            "com.vungle.mediation.VungleAdapter" -> "vungle"

            "com.google.ads.mediation.facebook.FacebookAdapter",
            "com.google.ads.mediation.facebook.FacebookMediationAdapter" -> "facebook"

            "com.mbridge.msdk",
            "com.google.ads.mediation.mintegral.MintegralMediationAdapter" -> "mintegral"

            "com.pangle.ads",
            "com.google.ads.mediation.pangle.PangleMediationAdapter" -> "pangle"

            "com.google.ads.mediation.unity.UnityAdapter",
            "com.google.ads.mediation.unity.UnityMediationAdapter" -> "unity"

            else -> "admob"
        }

        Log.i(
            TAG,
            "pushTrackEventAdmob enableAppsflyer:$enableAppsflyer --- value: ${adValue.valueMicros / 1_000_000.0} -- adType: $adType -- monetization: $monetization"
        )

        if (enableAppsflyer) {
            val customParams = hashMapOf(Scheme.AD_TYPE to adType.toString())

            AppsFlyerAdRevenue.logAdRevenue(
                monetization,
                MediationNetwork.googleadmob,
                Currency.getInstance(Locale.US),
                adValue.valueMicros / 1_000_000.0,
                customParams
            )
        }
    }

    fun updateServerUninstallToken(context: Application, token: String) {
        AppsFlyerLib.getInstance().updateServerUninstallToken(context, token)
    }

    fun onTrackRevenue(context: Context, eventName: String, revenue: Float, currency: String) {
        // Implementation placeholder
    }

    fun onTrackRevenuePurchase(revenue: Float, currency: String) {
        // Implementation placeholder
    }
}
