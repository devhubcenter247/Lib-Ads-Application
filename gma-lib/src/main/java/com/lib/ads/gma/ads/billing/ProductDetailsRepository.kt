package com.lib.ads.gma.ads.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import java.text.NumberFormat
import java.util.Currency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.lib.ads.gma.ads.util.AppLogger

internal class ProductDetailsRepository {

    private val skuDetailsINAPMap = ConcurrentHashMap<String, ProductDetails>()
    private val skuDetailsSubsMap = ConcurrentHashMap<String, ProductDetails>()
    val productIdMap = ConcurrentHashMap<QueryProductDetailsParams.Product, String>()

    var listINAPId: List<QueryProductDetailsParams.Product> = emptyList()
        private set
    var listSubscriptionId: List<QueryProductDetailsParams.Product> = emptyList()
        private set
    private var purchaseItems: List<PurchaseItem> = emptyList()

    fun syncPurchaseItemsToListProduct(items: List<PurchaseItem>) {
        purchaseItems = items
        val listInApp = mutableListOf<QueryProductDetailsParams.Product>()
        val listSubs = mutableListOf<QueryProductDetailsParams.Product>()
        productIdMap.clear()
        for (item in items) {
            val productType = if (item.type == 1) "inapp" else "subs"
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(item.itemId)
                .setProductType(productType)
                .build()
            if (item.type == 1) listInApp.add(product) else listSubs.add(product)
            productIdMap[product] = item.itemId
        }
        listINAPId = listInApp
        listSubscriptionId = listSubs
        AppLogger.d(TAG, "syncPurchaseItemsToListProduct: INAPP=${listInApp.size}, SUBS=${listSubs.size}")
    }

    fun queryProducts(
        billingClient: BillingClient,
        enableProductDetailsLog: Boolean,
        onComplete: (BillingResult) -> Unit,
    ) {
        AppLogger.d(TAG, "queryProducts: inApp=${listINAPId.size}, subscriptions=${listSubscriptionId.size}")
        val queryCount = (if (listINAPId.isNotEmpty()) 1 else 0) +
            (if (listSubscriptionId.isNotEmpty()) 1 else 0)
        if (queryCount == 0) {
            onComplete(
                BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.OK)
                    .build()
            )
            return
        }
        val completedQueries = AtomicInteger(0)
        val firstError = AtomicReference<BillingResult?>(null)

        fun queryFinished(result: BillingResult) {
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                firstError.compareAndSet(null, result)
            }
            if (completedQueries.incrementAndGet() == queryCount) {
                onComplete(firstError.get() ?: result)
            }
        }

        if (listINAPId.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listINAPId)
                .build()
            billingClient.queryProductDetailsAsync(params) { billingResult, result ->
                AppLogger.d(
                    TAG,
                    "onSkuINAPDetailsResponse: code=${billingResult.responseCode}, " +
                        "message=${billingResult.debugMessage}, products=${result.productDetailsList.size}"
                )
                for (d in result.productDetailsList) {
                    skuDetailsINAPMap[d.productId] = d
                    if (enableProductDetailsLog) {
                        logProductDetails(d)
                    }
                }
                if (enableProductDetailsLog) {
                    val productInfo = getProductInfoList()
                    AppLogger.i(TAG, "initBilling: INAPP productInfo=$productInfo")
                }
                queryFinished(billingResult)
            }
        }
        if (listSubscriptionId.isNotEmpty()) {
            for (item in listSubscriptionId) {
                AppLogger.d(TAG, "queryProducts SUBS: ${productIdMap[item] ?: ""}")
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listSubscriptionId)
                .build()
            billingClient.queryProductDetailsAsync(params) { billingResult, result ->
                AppLogger.d(
                    TAG,
                    "onSkuSubsDetailsResponse: code=${billingResult.responseCode}, " +
                        "message=${billingResult.debugMessage}, products=${result.productDetailsList.size}"
                )
                for (d in result.productDetailsList) {
                    skuDetailsSubsMap[d.productId] = d
                    if (enableProductDetailsLog) {
                        logProductDetails(d)
                    }
                }
                if (enableProductDetailsLog) {
                    val productInfo = getProductInfoList()
                    AppLogger.i(TAG, "initBilling: SUBS productInfo=$productInfo")
                }
                queryFinished(billingResult)
            }
        }
    }

    private fun logProductDetails(details: ProductDetails) {
        val oneTime = details.oneTimePurchaseOfferDetails
        if (oneTime != null) {
            AppLogger.i(
                TAG,
                "product: id=${details.productId}, type=${details.productType}, " +
                    "name=${details.name}, title=${details.title}, " +
                    "price=${oneTime.formattedPrice}, micros=${oneTime.priceAmountMicros}, " +
                    "currency=${oneTime.priceCurrencyCode}"
            )
        }

        val subscriptionOffers = details.subscriptionOfferDetails
        if (!subscriptionOffers.isNullOrEmpty()) {
            AppLogger.i(
                TAG,
                "product: id=${details.productId}, type=${details.productType}, " +
                    "name=${details.name}, title=${details.title}, " +
                    "subscriptionOffers=${subscriptionOffers.size}"
            )
            subscriptionOffers.forEachIndexed { offerIndex, offer ->
                val phases = offer.pricingPhases.pricingPhaseList.joinToString(
                    separator = "; "
                ) { phase ->
                    "price=${phase.formattedPrice}, micros=${phase.priceAmountMicros}, " +
                        "currency=${phase.priceCurrencyCode}, period=${phase.billingPeriod}, " +
                        "cycles=${phase.billingCycleCount}"
                }
                AppLogger.i(
                    TAG,
                    "productOffer: id=${details.productId}, index=$offerIndex, " +
                        "basePlanId=${offer.basePlanId}, offerId=${offer.offerId}, " +
                        "offerToken=${offer.offerToken}, phases=[$phases]"
                )
            }
        }
    }

    fun getINAPProductDetails(productId: String): ProductDetails? = skuDetailsINAPMap[productId]

    fun getSubsProductDetails(productId: String): ProductDetails? = skuDetailsSubsMap[productId]

    fun hasINAPProducts(): Boolean = skuDetailsINAPMap.isNotEmpty()

    fun hasSubsProducts(): Boolean = skuDetailsSubsMap.isNotEmpty()

    fun shouldConsume(productId: String): Boolean =
        purchaseItems.firstOrNull { it.itemId == productId }?.consume ?: false

    fun getEntitlements(productId: String): Set<Entitlement> =
        purchaseItems.firstOrNull { it.itemId == productId }?.entitlements ?: emptySet()

    fun getTrialId(subsId: String): String? =
        purchaseItems.firstOrNull { it.itemId == subsId }?.trialId

    fun getPrice(productId: String): String? {
        val d = skuDetailsINAPMap[productId] ?: return ""
        AppLogger.d(TAG, "getPrice: ${d.oneTimePurchaseOfferDetails?.formattedPrice}")
        return d.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
    }

    fun getName(productId: String, typeIap: Int): String? {
        val d = if (typeIap == 2) skuDetailsSubsMap[productId] else skuDetailsINAPMap[productId]
        return d?.name ?: ""
    }

    fun getPriceSub(productId: String): String {
        val d = skuDetailsSubsMap[productId] ?: return ""
        val offers = d.subscriptionOfferDetails ?: return ""
        val phases = offers.last().pricingPhases.pricingPhaseList
        AppLogger.d(TAG, "getPriceSub: ${phases.last().formattedPrice}")
        return phases.last().formattedPrice
    }

    fun getPeriod(productId: String): String {
        return try {
            val d = skuDetailsSubsMap[productId] ?: return ""
            val offers = d.subscriptionOfferDetails ?: return ""
            offers.last().pricingPhases.pricingPhaseList.first().billingPeriod
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getTrialPeriod(productId: String): String {
        return try {
            val d = skuDetailsSubsMap[productId] ?: return ""
            val offers = d.subscriptionOfferDetails ?: return ""
            for (offer in offers) {
                for (phase in offer.pricingPhases.pricingPhaseList) {
                    if (phase.priceAmountMicros == 0L && phase.billingCycleCount == 1) {
                        return phase.billingPeriod
                    }
                }
            }
            ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getPricePricingPhaseList(productId: String): List<ProductDetails.PricingPhase>? {
        val d = skuDetailsSubsMap[productId] ?: return null
        val offers = d.subscriptionOfferDetails ?: return null
        return offers.last().pricingPhases.pricingPhaseList
    }

    fun getIntroductorySubPrice(productId: String, offerId: String? = null): String {
        val d = skuDetailsSubsMap[productId] ?: return ""
        val subsOffers = d.subscriptionOfferDetails ?: return ""
        val targetOffer = if (offerId != null) {
            subsOffers.firstOrNull { it.offerId == offerId }
        } else {
            subsOffers.firstOrNull { it.offerId != null }
        } ?: return ""
        return targetOffer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros > 0 }?.formattedPrice ?: ""
    }

    fun getProductInfoList(): List<BillingProductInfo> {
        val result = mutableListOf<BillingProductInfo>()

        skuDetailsINAPMap.forEach { (productId, details) ->
            val offer = details.oneTimePurchaseOfferDetails
            result.add(
                BillingProductInfo(
                    productId = productId,
                    name = details.name,
                    type = 1,
                    price = offer?.formattedPrice ?: "",
                    priceMicros = offer?.priceAmountMicros ?: 0L,
                    currency = offer?.priceCurrencyCode ?: "",
                )
            )
        }

        skuDetailsSubsMap.forEach { (productId, details) ->
            val offers = details.subscriptionOfferDetails ?: return@forEach
            val promoOffer = offers.firstOrNull { it.offerId != null }
            val baseOffer = offers.firstOrNull { it.offerId == null } ?: offers.last()
            val promoPhases = promoOffer?.pricingPhases?.pricingPhaseList
            val trialPhase = promoPhases?.firstOrNull { it.priceAmountMicros == 0L }
            val introPhase = promoPhases?.firstOrNull { it.priceAmountMicros > 0L }
            val regularPhase = baseOffer.pricingPhases.pricingPhaseList.last()
            result.add(
                BillingProductInfo(
                    productId = productId,
                    name = details.name,
                    type = 2,
                    regularPrice = regularPhase.formattedPrice,
                    regularPriceMicros = regularPhase.priceAmountMicros,
                    currency = regularPhase.priceCurrencyCode,
                    billingPeriod = regularPhase.billingPeriod,
                    introPrice = introPhase?.formattedPrice ?: "",
                    introPriceMicros = introPhase?.priceAmountMicros ?: 0L,
                    introBillingPeriod = introPhase?.billingPeriod ?: "",
                    introCycles = introPhase?.billingCycleCount ?: 0,
                    trialPeriod = trialPhase?.billingPeriod ?: "",
                    promoOfferId = promoOffer?.offerId,
                    promoOfferToken = promoOffer?.offerToken ?: "",
                    baseOfferToken = baseOffer.offerToken,
                )
            )
        }

        AppLogger.d(TAG, "getProductInfoList: inApp=${skuDetailsINAPMap.size}, subscriptions=${skuDetailsSubsMap.size}, result=${result.size}")
        return result
    }

    fun getInAppProductIds(): List<String> = skuDetailsINAPMap.keys.toList()

    fun getSubscriptionProductIds(): List<String> = skuDetailsSubsMap.keys.toList()

    fun getAllProductIds(): List<String> = getInAppProductIds() + getSubscriptionProductIds()

    fun getCurrency(productId: String, typeIAP: Int): String {
        val d = if (typeIAP == 1) skuDetailsINAPMap[productId] else skuDetailsSubsMap[productId]
        d ?: return ""
        return if (typeIAP == 1) {
            d.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: ""
        } else {
            val offers = d.subscriptionOfferDetails ?: return ""
            val phases = offers.last().pricingPhases.pricingPhaseList
            phases.last().priceCurrencyCode
        }
    }

    fun getPriceWithoutCurrency(productId: String, typeIAP: Int): Double {
        val d = if (typeIAP == 1) skuDetailsINAPMap[productId] else skuDetailsSubsMap[productId]
        d ?: return 0.0
        return if (typeIAP == 1) {
            d.oneTimePurchaseOfferDetails?.priceAmountMicros?.toDouble() ?: 0.0
        } else {
            val offers = d.subscriptionOfferDetails ?: return 0.0
            val phases = offers.last().pricingPhases.pricingPhaseList
            phases.last().priceAmountMicros.toDouble()
        }
    }

    fun getPriceWithCurrency(productId: String, typeIAP: Int): String {
        return try {
            val price = getPriceWithoutCurrency(productId, typeIAP) / 1_000_000.0
            formatCurrency(price, getCurrency(productId, typeIAP))
        } catch (e: Exception) {
            ""
        }
    }

    fun getPriceWithCurrency(productId: String, typeIAP: Int, sale: Double): String {
        return try {
            val price = getPriceWithoutCurrency(productId, typeIAP) / 1_000_000.0
            formatCurrency(price / sale, getCurrency(productId, typeIAP))
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatCurrency(price: Double, currency: String): String {
        if (currency.isEmpty()) return ""
        val format = NumberFormat.getCurrencyInstance().apply {
            maximumFractionDigits = 0
            setCurrency(Currency.getInstance(currency))
        }
        return format.format(price)
    }

    fun getListInAppId(): List<String> = listINAPId.map { productIdMap[it] ?: "" }

    fun getListSubId(): List<String> = listSubscriptionId.map { productIdMap[it] ?: "" }

    companion object {
        private const val TAG = "GMA_Purchase"
    }
}
