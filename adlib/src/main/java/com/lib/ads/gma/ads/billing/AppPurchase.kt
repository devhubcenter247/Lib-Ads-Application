package com.lib.ads.gma.ads.billing

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.util.Log
import android.view.View
import androidx.annotation.IntDef
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.collect.ImmutableList
import com.lib.ads.gma.ads.event.LogEventManager
import com.lib.ads.gma.ads.event.FirebaseAnalytics
import com.lib.ads.gma.ads.util.AppUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency

/**
 * Manager for in-app purchases and subscriptions.
 */
class AppPurchase private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null

    private var price: String = "1.49$"
    private var oldPrice: String = "2.99$"

    private var listSubscriptionId: ArrayList<QueryProductDetailsParams.Product> = arrayListOf()
    private var listINAPId: ArrayList<QueryProductDetailsParams.Product> = arrayListOf()
    private var listSubscriptionProductIds: List<String> = emptyList()
    private var listINAPProductIds: List<String> = emptyList()
    private var purchaseItems: List<PurchaseItem> = emptyList()
    private var purchaseListener: PurchaseListener? = null
    private var updatePurchaseListener: UpdatePurchaseListener? = null
    private var billingListener: BillingListener? = null
    private var isInitBillingFinish: Boolean = false
    private lateinit var billingClient: BillingClient
    private var skuListINAPFromStore: List<ProductDetails>? = null
    private var skuListSubsFromStore: List<ProductDetails>? = null
    private val skuDetailsINAPMap: MutableMap<String, ProductDetails> = HashMap()
    private val skuDetailsSubsMap: MutableMap<String, ProductDetails> = HashMap()
    private var isAvailable: Boolean = false
    private var isListGot: Boolean = false
    private var isConsumePurchase: Boolean = false

    private var retryConsumeTimes: Int = 0
    private val maxRetryConsumeTimes: Int = 1
    private var countReconnectBilling: Int = 0
    private var countMaxReconnectBilling: Int = 4

    // Tracking purchase adjust
    private var idPurchaseCurrent: String = ""
    private var typeIap: Int = 0

    // Status verify purchase INAP & SUBS
    private var verifyFinish: Boolean = false
    private var isVerifyINAP: Boolean = false
    private var isVerifySUBS: Boolean = false
    private var isUpdateInapps: Boolean = false
    private var isUpdateSubs: Boolean = false

    private var isPurchase: Boolean = false
    private var idPurchased: String = ""
    private val ownerIdSubs: MutableList<PurchaseResult> = mutableListOf()
    private val ownerIdInApp: MutableList<PurchaseResult> = mutableListOf()

    // Entitlements owned across every currently-verified purchase, keyed by what each product
    // grants (see Entitlement) rather than a single "bought something" flag. isPurchase above
    // stays in sync as a derived view: true iff REMOVE_ADS is owned — every ad-gating call site
    // that reads isPurchased() keeps working unchanged.
    private var ownedEntitlements: Set<Entitlement> = emptySet()

    private var enableTrackingRevenue: Boolean = false
    private var discount: Double = 1.0

    fun setPurchaseListener(purchaseListener: PurchaseListener?) {
        this.purchaseListener = purchaseListener
    }

    fun setEnableTrackingRevenue(enableTrackingRevenue: Boolean) {
        this.enableTrackingRevenue = enableTrackingRevenue
    }

    fun getEnableTrackingRevenue(): Boolean = enableTrackingRevenue

    fun setUpdatePurchaseListener(listener: UpdatePurchaseListener?) {
        this.updatePurchaseListener = listener
    }

    fun setBillingListener(billingListener: BillingListener?) {
        this.billingListener = billingListener
        if (isAvailable) {
            billingListener?.onInitBillingFinished(0)
            isInitBillingFinish = true
        }
    }

    fun setBillingListener(billingListener: BillingListener?, timeout: Int) {
        Log.d(TAG, "setBillingListener: timeout $timeout")
        this.billingListener = billingListener
        if (isInitBillingFinish || isAvailable) {
            Log.d(TAG, "setBillingListener: finish")
            billingListener?.onInitBillingFinished(0)
            return
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeout.toLong())
            Log.d(TAG, "setBillingListener: timeout run")
            isInitBillingFinish = true
            billingListener?.onInitBillingFinished(BillingClient.BillingResponseCode.ERROR)
        }
    }

    fun isAvailable(): Boolean = isAvailable

    fun getInitBillingFinish(): Boolean = isInitBillingFinish

    fun setEventConsumePurchaseTest(view: View) {
        view.setOnClickListener {
            if (AppUtil.VARIANT_DEV) {
                Log.d(TAG, "setEventConsumePurchaseTest: success")
                getInstance().consumePurchase(PRODUCT_ID_TEST)
            }
        }
    }

    fun setPrice(price: String) {
        this.price = price
    }

    fun setConsumePurchase(consumePurchase: Boolean) {
        isConsumePurchase = consumePurchase
    }

    fun setOldPrice(oldPrice: String) {
        this.oldPrice = oldPrice
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.e(TAG, "onPurchasesUpdated code: ${billingResult.responseCode} msg: ${billingResult.debugMessage}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                } else {
                    Log.d(TAG, "onPurchasesUpdated: OK but purchases is null")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "onPurchasesUpdated: USER_CANCELED")
                purchaseListener?.onUserCancelBilling()
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "onPurchasesUpdated: ITEM_ALREADY_OWNED")
                purchaseListener?.displayErrorMessage("You already own this item")
                // Re-query so isPurchased reflects the existing ownership
                verifyPurchased(false)
            }
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
                Log.d(TAG, "onPurchasesUpdated: ITEM_UNAVAILABLE")
                purchaseListener?.displayErrorMessage("This item is not available")
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                Log.d(TAG, "onPurchasesUpdated: BILLING_UNAVAILABLE")
                purchaseListener?.displayErrorMessage("Billing is not supported on this device")
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                Log.d(TAG, "onPurchasesUpdated: SERVICE_DISCONNECTED/UNAVAILABLE")
                purchaseListener?.displayErrorMessage("Network error. Please try again")
            }
            BillingClient.BillingResponseCode.ERROR -> {
                Log.e(TAG, "onPurchasesUpdated: ERROR ${billingResult.debugMessage}")
                purchaseListener?.displayErrorMessage("Purchase failed. Please try again")
            }
            else -> {
                Log.d(TAG, "onPurchasesUpdated: unhandled code=${billingResult.responseCode}")
            }
        }
    }

    private val purchaseClientStateListener: BillingClientStateListener = object : BillingClientStateListener {
        override fun onBillingServiceDisconnected() {
            isAvailable = false
            if (countReconnectBilling < countMaxReconnectBilling) {
                countReconnectBilling++
                billingClient.startConnection(purchaseClientStateListener)
            }
        }

        override fun onBillingSetupFinished(billingResult: BillingResult) {
            Log.d(TAG, "onBillingSetupFinished: ${billingResult.responseCode}")
            timeoutJob?.cancel()
            countReconnectBilling = 0

            if (!isInitBillingFinish) {
                verifyPurchased(true)
            }

            isInitBillingFinish = true
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                isAvailable = true
                // Check product detail INAP
                if (listINAPId.isNotEmpty()) {
                    val paramsINAP = QueryProductDetailsParams.newBuilder()
                        .setProductList(listINAPId)
                        .build()

                    billingClient.queryProductDetailsAsync(paramsINAP) { _, result ->
                        val productDetailsList = result.productDetailsList
                        Log.d(TAG, "onSkuINAPDetailsResponse: count=${productDetailsList.size}")
                        productDetailsList.forEach { detail ->
                            Log.d(TAG, "  [INAPP] productId=${detail.productId} name=${detail.name}")
                            detail.oneTimePurchaseOfferDetails?.let { offer ->
                                Log.d(TAG, "    price=${offer.formattedPrice} priceMicros=${offer.priceAmountMicros} currency=${offer.priceCurrencyCode}")
                            }
                        }
                        skuListINAPFromStore = productDetailsList
                        isListGot = true
                        addSkuINAPToMap(productDetailsList)
                    }
                }
                // Check product detail SUBS
                if (listSubscriptionId.isNotEmpty()) {
                    val paramsSUBS = QueryProductDetailsParams.newBuilder()
                        .setProductList(listSubscriptionId)
                        .build()
                    for (item in listSubscriptionProductIds) {
                        Log.d(TAG, "onBillingSetupFinished querying sub: $item")
                    }

                    billingClient.queryProductDetailsAsync(paramsSUBS) { _, result ->
                        val productDetailsList = result.productDetailsList
                        Log.d(TAG, "onSkuSubsDetailsResponse: count=${productDetailsList.size}")
                        productDetailsList.forEach { detail ->
                            Log.d(TAG, "  [SUBS] productId=${detail.productId} name=${detail.name}")
                            detail.subscriptionOfferDetails?.forEachIndexed { offerIndex, offer ->
                                Log.d(TAG, "    offer[$offerIndex] basePlanId=${offer.basePlanId} offerId=${offer.offerId}")
                                offer.pricingPhases.pricingPhaseList.forEachIndexed { phaseIndex, phase ->
                                    Log.d(TAG, "      phase[$phaseIndex] price=${phase.formattedPrice} priceMicros=${phase.priceAmountMicros} period=${phase.billingPeriod} cycles=${phase.billingCycleCount} recurrence=${phase.recurrenceMode}")
                                }
                            }
                        }
                        skuListSubsFromStore = productDetailsList
                        isListGot = true
                        addSkuSubsToMap(productDetailsList)
                    }
                } else {
                    Log.d(TAG, "onBillingSetupFinished: listSubscriptionId empty")
                }
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                || billingResult.responseCode == BillingClient.BillingResponseCode.ERROR
            ) {
                Log.e(TAG, "onBillingSetupFinished: ERROR")
            }
        }
    }

    fun getOwnerIdSubs(): List<PurchaseResult> = ownerIdSubs

    fun getOwnerIdInApp(): List<PurchaseResult> = ownerIdInApp

    @Deprecated("Use initBilling with PurchaseItem list instead")
    @Suppress("DEPRECATION")
    fun initBilling(application: Application, listINAPId: List<String>, listSubsId: List<String>) {
        val mutableListINAPId = listINAPId.toMutableList()
        if (AppUtil.VARIANT_DEV) {
            mutableListINAPId.add(PRODUCT_ID_TEST)
        }
        this.listSubscriptionId = listIdToListProduct(listSubsId, BillingClient.ProductType.SUBS)
        this.listINAPId = listIdToListProduct(mutableListINAPId, BillingClient.ProductType.INAPP)
        this.listSubscriptionProductIds = listSubsId.toList()
        this.listINAPProductIds = mutableListINAPId.toList()

        billingClient = BillingClient.newBuilder(application)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()

        billingClient.startConnection(purchaseClientStateListener)
    }

    fun initBilling(application: Application, purchaseItemList: List<PurchaseItem>) {
        val mutableList = purchaseItemList.toMutableList()
        if (AppUtil.VARIANT_DEV) {
            mutableList.add(PurchaseItem(PRODUCT_ID_TEST, TYPE_IAP.PURCHASE, ""))
        }
        this.purchaseItems = mutableList
        syncPurchaseItemsToListProduct(this.purchaseItems)

        billingClient = BillingClient.newBuilder(application)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()

        billingClient.startConnection(purchaseClientStateListener)
    }

    private fun addSkuSubsToMap(skuList: List<ProductDetails>) {
        for (skuDetails in skuList) {
            skuDetailsSubsMap[skuDetails.productId] = skuDetails
        }
    }

    private fun addSkuINAPToMap(skuList: List<ProductDetails>) {
        for (skuDetails in skuList) {
            skuDetailsINAPMap[skuDetails.productId] = skuDetails
        }
    }

    fun setPurchase(purchase: Boolean) {
        isPurchase = purchase
        ownedEntitlements = if (purchase) ownedEntitlements + Entitlement.REMOVE_ADS else ownedEntitlements - Entitlement.REMOVE_ADS
    }

    /** True iff the user owns the [Entitlement.REMOVE_ADS] entitlement. Every ad-gating check in the SDK reads this. */
    fun isPurchased(): Boolean = isPurchase

    @Suppress("UNUSED_PARAMETER")
    fun isPurchased(context: android.content.Context): Boolean = isPurchase

    /** True iff the user owns [entitlement] — e.g. [Entitlement.PREMIUM_FEATURES] to gate paid app features independently of ad suppression. */
    fun hasEntitlement(entitlement: Entitlement): Boolean = entitlement in ownedEntitlements

    fun getOwnedEntitlements(): Set<Entitlement> = ownedEntitlements

    /** What [productId] grants. Falls back to both entitlements if the product wasn't registered via a [PurchaseItem] (e.g. the deprecated raw-id initBilling overload). */
    private fun getEntitlements(productId: String): Set<Entitlement> {
        if (purchaseItems.isEmpty()) return setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES)
        return purchaseItems.firstOrNull { it.itemId == productId }?.entitlements
            ?: setOf(Entitlement.REMOVE_ADS, Entitlement.PREMIUM_FEATURES)
    }

    /** Adds entitlements from a newly-confirmed owned purchase (does not clear existing ones). */
    private fun grantEntitlements(entitlements: Set<Entitlement>) {
        if (entitlements.isEmpty()) return
        ownedEntitlements = ownedEntitlements + entitlements
        if (Entitlement.REMOVE_ADS in entitlements) isPurchase = true
    }

    /** Recomputes the full entitlement set from every currently-owned purchase (ground truth). */
    private fun refreshEntitlementsFromOwnedLists() {
        val ownedIds = (ownerIdSubs + ownerIdInApp).flatMap { it.productId }
        ownedEntitlements = ownedIds.flatMap { getEntitlements(it) }.toSet()
        isPurchase = Entitlement.REMOVE_ADS in ownedEntitlements
    }

    fun getIdPurchased(): String = idPurchased

    private fun addOrUpdateOwnerIdInApp(purchaseResult: PurchaseResult, id: String) {
        val existingItem = ownerIdInApp.find { it.productId.contains(id) }
        existingItem?.let { ownerIdInApp.remove(it) }
        ownerIdInApp.add(purchaseResult)
    }

    private fun addOrUpdateOwnerIdSub(purchaseResult: PurchaseResult, id: String) {
        val existingItem = ownerIdSubs.find { it.productId.contains(id) }
        existingItem?.let { ownerIdSubs.remove(it) }
        ownerIdSubs.add(purchaseResult)
    }

    fun verifyPurchased(isCallback: Boolean) {
        verifyFinish = false
        isVerifyINAP = false
        isVerifySUBS = false
        listINAPId.let {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { billingResult, list ->
                Log.d(TAG, "verifyPurchased INAPP code:${billingResult.responseCode} === size:${list.size}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in list) {
                        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                        for (id in listINAPProductIds) {
                            if (purchase.products.contains(id)) {
                                Log.i(TAG, "verifyPurchased INAPP: Order Id: ${purchase.orderId}")
                                addOrUpdateOwnerIdInApp(PurchaseResult.fromPurchase(purchase), id)
                                grantEntitlements(getEntitlements(id))
                            }
                        }
                    }
                    isVerifyINAP = true
                    if (isVerifySUBS) {
                        if (isCallback) {
                            billingListener?.onInitBillingFinished(billingResult.responseCode)
                            timeoutJob?.cancel()
                        }
                        verifyFinish = true
                    }
                } else {
                    isVerifyINAP = true
                    if (isVerifySUBS) {
                        billingListener?.onInitBillingFinished(billingResult.responseCode)
                        timeoutJob?.cancel()
                        verifyFinish = true
                    }
                }
            }
        }

        listSubscriptionId.let {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            ) { billingResult, list ->
                Log.d(TAG, "verifyPurchased SUBS code:${billingResult.responseCode} === size:${list.size}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in list) {
                        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                        for (id in listSubscriptionProductIds) {
                            if (purchase.products.contains(id)) {
                                addOrUpdateOwnerIdSub(PurchaseResult.fromPurchase(purchase), id)
                                Log.d(TAG, "verifyPurchased SUBS: true")
                                grantEntitlements(getEntitlements(id))
                            }
                        }
                    }
                    isVerifySUBS = true
                    if (isVerifyINAP) {
                        if (isCallback) {
                            billingListener?.onInitBillingFinished(billingResult.responseCode)
                            timeoutJob?.cancel()
                        }
                        verifyFinish = true
                    }
                } else {
                    isVerifySUBS = true
                    if (isVerifyINAP) {
                        if (isCallback) {
                            billingListener?.onInitBillingFinished(billingResult.responseCode)
                            timeoutJob?.cancel()
                            verifyFinish = true
                        }
                    }
                }
            }
        }
    }

    fun updatePurchaseStatus() {
        isUpdateInapps = false
        isUpdateSubs = false
        ownerIdInApp.clear()
        ownerIdSubs.clear()

        listINAPId.let {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { billingResult, list ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in list) {
                        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                        for (id in listINAPProductIds) {
                            if (purchase.products.contains(id)) {
                                addOrUpdateOwnerIdInApp(PurchaseResult.fromPurchase(purchase), id)
                            }
                        }
                    }
                }
                isUpdateInapps = true
                refreshEntitlementsFromOwnedLists()
                if (isUpdateSubs) {
                    updatePurchaseListener?.onUpdateFinished()
                }
            }
        }

        listSubscriptionId.let {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            ) { billingResult, list ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (purchase in list) {
                        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                        for (id in listSubscriptionProductIds) {
                            if (purchase.products.contains(id)) {
                                addOrUpdateOwnerIdSub(PurchaseResult.fromPurchase(purchase), id)
                            }
                        }
                    }
                }
                isUpdateSubs = true
                refreshEntitlementsFromOwnedLists()
                if (isUpdateInapps) {
                    updatePurchaseListener?.onUpdateFinished()
                }
            }
        }
    }

    fun purchase(activity: Activity, productId: String): String {
        if (skuListINAPFromStore == null) {
            purchaseListener?.displayErrorMessage("Billing error init")
            return ""
        }
        val productDetails = skuDetailsINAPMap[productId]

        if (AppUtil.VARIANT_DEV) {
            val purchaseDevBottomSheet = PurchaseDevBottomSheet(TYPE_IAP.PURCHASE, productDetails, activity, purchaseListener)
            purchaseDevBottomSheet.show()
            return ""
        }

        if (productDetails == null) {
            return "Not found item with id: $productId"
        }
        Log.d(TAG, "purchase: $productDetails")

        idPurchaseCurrent = productId
        typeIap = TYPE_IAP.PURCHASE

        val productDetailsParamsList = ImmutableList.of(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

        return handleBillingResult(billingResult)
    }

    fun subscribe(activity: Activity, subsId: String): String =
        subscribe(activity, subsId, null, BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE)

    fun subscribe(
        activity: Activity,
        subsId: String,
        oldPurchaseToken: String?,
        replacementMode: Int = BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
    ): String {
        if (skuListSubsFromStore == null) {
            purchaseListener?.displayErrorMessage("Billing error init")
            return ""
        }

        if (AppUtil.VARIANT_DEV) {
            purchase(activity, PRODUCT_ID_TEST)
            return "Billing test"
        }

        val skuDetails = skuDetailsSubsMap[subsId] ?: return "Product ID invalid"
        val subsDetail = skuDetails.subscriptionOfferDetails
            ?: return "Can't found offer for this subscription!"

        val trailId = purchaseItems.find { it.itemId == subsId }?.trialId

        var offerToken = ""
        for (item in subsDetail) {
            val offerId = item.offerId
            if (offerId != null && offerId == trailId) {
                offerToken = item.offerToken
                break
            }
        }
        if (offerToken.isEmpty()) {
            // Fall back to the base plan offer (first offer without an offerId = no trial/promo)
            offerToken = subsDetail.firstOrNull { it.offerId == null }?.offerToken
                ?: subsDetail.last().offerToken
        }
        Log.d(TAG, "subscribe: offerToken: $offerToken")

        idPurchaseCurrent = subsId
        typeIap = TYPE_IAP.SUBSCRIPTION

        val productDetailsParamsList = ImmutableList.of(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(skuDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)

        if (oldPurchaseToken != null) {
            @Suppress("DEPRECATION")
            billingFlowParamsBuilder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldPurchaseToken)
                    .setSubscriptionReplacementMode(replacementMode)
                    .build()
            )
        }

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParamsBuilder.build())

        return handleBillingResult(billingResult)
    }

    @Suppress("DEPRECATION")
    private fun handleBillingResult(billingResult: BillingResult): String {
        return when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                purchaseListener?.displayErrorMessage("Billing not supported for type of request")
                "Billing not supported for type of request"
            }
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> ""
            BillingClient.BillingResponseCode.ERROR -> {
                purchaseListener?.displayErrorMessage("Error completing request")
                "Error completing request"
            }
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Error processing request."
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "Selected item is already owned"
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "Item not available"
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "Play Store service is not connected now"
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> "Timeout"
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                purchaseListener?.displayErrorMessage("Network error.")
                "Network Connection down"
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                purchaseListener?.displayErrorMessage("Request Canceled")
                "Request Canceled"
            }
            BillingClient.BillingResponseCode.OK -> "Subscribed Successfully"
            else -> ""
        }
    }

    fun consumePurchase(productId: String) {
        Log.d(TAG, "consumePurchase: $productId")
        retryConsumeTimes = 0
        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, list ->
            var pc: Purchase? = null
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in list) {
                    if (purchase.products.contains(productId)) {
                        pc = purchase
                    }
                }
            }
            if (pc == null || pc.purchaseState != Purchase.PurchaseState.PURCHASED) return@queryPurchasesAsync

            try {
                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(pc.purchaseToken)
                    .build()

                billingClient.consumeAsync(consumeParams) { billingResult1, _ ->
                    if (billingResult1.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "onConsumeResponse: OK")
                        retryConsumeTimes = 0
                        updatePurchaseStatus()
                    } else {
                        Log.e(TAG, "consumePurchase: error $billingResult1")
                        if (retryConsumeTimes >= maxRetryConsumeTimes) {
                            retryConsumeTimes = 0
                            return@consumeAsync
                        }
                        retryConsumeTimes++
                        consumePurchase(productId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "consumePurchase: error", e)
            }
        }
    }

    private fun getListInAppId(): List<String> = listINAPProductIds

    private fun getListSubId(): List<String> = listSubscriptionProductIds

    /** Product IDs of in-app purchases confirmed by Play Store. */
    fun getInAppProductIds(): List<String> = skuDetailsINAPMap.keys.toList()

    /** Product IDs of subscriptions confirmed by Play Store. */
    fun getSubscriptionProductIds(): List<String> = skuDetailsSubsMap.keys.toList()

    /** Product IDs of all products (in-app + subscriptions) confirmed by Play Store. */
    fun getAllProductIds(): List<String> = getInAppProductIds() + getSubscriptionProductIds()

    /**
     * Returns a flat, UI-ready list of all products fetched from Play Store.
     * Call this after [BillingListener.onInitBillingFinished].
     */
    fun getProductInfoList(): List<BillingProductInfo> {
        val result = mutableListOf<BillingProductInfo>()

        // ── In-app purchases ──────────────────────────────────────────────
        skuDetailsINAPMap.forEach { (productId, details) ->
            val offer = details.oneTimePurchaseOfferDetails
            result.add(
                BillingProductInfo(
                    productId    = productId,
                    name         = details.name,
                    type         = TYPE_IAP.PURCHASE,
                    price        = offer?.formattedPrice ?: "",
                    priceMicros  = offer?.priceAmountMicros ?: 0L,
                    currency     = offer?.priceCurrencyCode ?: "",
                )
            )
        }

        // ── Subscriptions ─────────────────────────────────────────────────
        skuDetailsSubsMap.forEach { (productId, details) ->
            val offers = details.subscriptionOfferDetails ?: return@forEach

            // Promo offer = first offer that has an offerId
            val promoOffer = offers.firstOrNull { it.offerId != null }
            // Base plan = offer with offerId == null
            val baseOffer  = offers.firstOrNull { it.offerId == null } ?: offers.last()

            val promoPhases = promoOffer?.pricingPhases?.pricingPhaseList
            val trialPhase  = promoPhases?.firstOrNull { it.priceAmountMicros == 0L }
            val introPhase  = promoPhases?.firstOrNull { it.priceAmountMicros > 0L }
            val regularPhase = baseOffer.pricingPhases.pricingPhaseList.last()

            result.add(
                BillingProductInfo(
                    productId          = productId,
                    name               = details.name,
                    type               = TYPE_IAP.SUBSCRIPTION,
                    regularPrice       = regularPhase.formattedPrice,
                    regularPriceMicros = regularPhase.priceAmountMicros,
                    currency           = regularPhase.priceCurrencyCode,
                    billingPeriod      = regularPhase.billingPeriod,
                    introPrice         = introPhase?.formattedPrice ?: "",
                    introPriceMicros   = introPhase?.priceAmountMicros ?: 0L,
                    introBillingPeriod = introPhase?.billingPeriod ?: "",
                    introCycles        = introPhase?.billingCycleCount ?: 0,
                    trialPeriod        = trialPhase?.billingPeriod ?: "",
                    promoOfferId       = promoOffer?.offerId,
                    promoOfferToken    = promoOffer?.offerToken ?: "",
                    baseOfferToken     = baseOffer.offerToken,
                )
            )
        }

        return result
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "handlePurchase: PENDING state - skipping entitlement grant")
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            Log.d(TAG, "handlePurchase: unexpected state ${purchase.purchaseState} - skipping")
            return
        }

        // Tracking adjust
        val price = getPriceWithoutCurrency(idPurchaseCurrent, typeIap) / 1000000.0
        val currency = getCurrency(idPurchaseCurrent, typeIap)
        LogEventManager.onTrackRevenuePurchase(price.toFloat(), currency, idPurchaseCurrent, typeIap)

        purchaseListener?.let { listener ->
            if (!isConsumePurchase) {
                grantEntitlements(getEntitlements(idPurchaseCurrent))
            }
            listener.onProductPurchased(purchase.orderId, purchase.originalJson)
        }

        if (isConsumePurchase) {
            val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.consumeAsync(consumeParams) { billingResult, _ ->
                Log.d(TAG, "onConsumeResponse: ${billingResult.debugMessage}")
            }
        } else {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                if (!purchase.isAcknowledged) {
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                        Log.d(TAG, "onAcknowledgePurchaseResponse: ${billingResult.debugMessage}")
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            FirebaseAnalytics.logConfirmPurchaseGoogle(
                                purchase.orderId, idPurchaseCurrent, purchase.purchaseToken
                            )
                        }
                    }
                }
            }
        }
    }

    fun getPrice(productId: String): String {
        val skuDetails = skuDetailsINAPMap[productId] ?: return ""
        Log.e(TAG, "getPrice: ${skuDetails.oneTimePurchaseOfferDetails?.formattedPrice}")
        return skuDetails.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
    }

    fun getName(productId: String, typeIap: Int): String {
        val productDetails = if (typeIap == TYPE_IAP.SUBSCRIPTION) {
            skuDetailsSubsMap[productId]
        } else {
            skuDetailsINAPMap[productId]
        }
        return productDetails?.name ?: ""
    }

    fun getPeriod(productId: String): String {
        return try {
            val productDetails = skuDetailsSubsMap[productId] ?: return ""
            val offerDetails = productDetails.subscriptionOfferDetails
            offerDetails?.let {
                val pricingPhase = it.last().pricingPhases.pricingPhaseList[0]
                pricingPhase.billingPeriod
            } ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getTrialPeriod(productId: String): String {
        return try {
            val productDetails = skuDetailsSubsMap[productId] ?: return ""
            val offerDetails = productDetails.subscriptionOfferDetails ?: return ""
            for (offerDetail in offerDetails) {
                for (pricingPhase in offerDetail.pricingPhases.pricingPhaseList) {
                    if (pricingPhase.priceAmountMicros == 0L && pricingPhase.billingCycleCount == 1) {
                        return pricingPhase.billingPeriod
                    }
                }
            }
            ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getPriceSub(productId: String): String {
        val skuDetails = skuDetailsSubsMap[productId] ?: return ""
        val subsDetail = skuDetails.subscriptionOfferDetails ?: return ""
        val pricingPhaseList = subsDetail.last().pricingPhases.pricingPhaseList
        Log.e(TAG, "getPriceSub: ${pricingPhaseList.last().formattedPrice}")
        return pricingPhaseList.last().formattedPrice
    }

    fun getPricePricingPhaseList(productId: String): List<ProductDetails.PricingPhase>? {
        val skuDetails = skuDetailsSubsMap[productId] ?: return null
        val subsDetail = skuDetails.subscriptionOfferDetails ?: return null
        return subsDetail.last().pricingPhases.pricingPhaseList
    }

    /**
     * Returns the introductory (discounted) price for a subscription.
     * @param productId The product ID (key in skuDetailsSubsMap), NOT the offer ID.
     * @param offerId   Optional: target a specific offer by its offer ID. If null, the first
     *                  offer that has an offerId (i.e. a promo offer) is used automatically.
     */
    fun getIntroductorySubPrice(productId: String, offerId: String? = null): String {
        val skuDetails = skuDetailsSubsMap[productId] ?: return ""
        val subsDetail = skuDetails.subscriptionOfferDetails ?: return ""
        val targetOffer = if (offerId != null) {
            subsDetail.firstOrNull { it.offerId == offerId }
        } else {
            subsDetail.firstOrNull { it.offerId != null } // first promo offer
        } ?: return ""
        // Skip free-trial phases (priceAmountMicros == 0) to reach the first paid introductory phase
        return targetOffer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros > 0 }?.formattedPrice ?: ""
    }

    fun getCurrency(productId: String, typeIAP: Int): String {
        val skuDetails = if (typeIAP == TYPE_IAP.PURCHASE) {
            skuDetailsINAPMap[productId]
        } else {
            skuDetailsSubsMap[productId]
        } ?: return ""

        return if (typeIAP == TYPE_IAP.PURCHASE) {
            skuDetails.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: ""
        } else {
            val subsDetail = skuDetails.subscriptionOfferDetails ?: return ""
            val pricingPhaseList = subsDetail.last().pricingPhases.pricingPhaseList
            pricingPhaseList.last().priceCurrencyCode
        }
    }

    fun getPriceWithoutCurrency(productId: String, typeIAP: Int): Double {
        val skuDetails = if (typeIAP == TYPE_IAP.PURCHASE) {
            skuDetailsINAPMap[productId]
        } else {
            skuDetailsSubsMap[productId]
        } ?: return 0.0

        return if (typeIAP == TYPE_IAP.PURCHASE) {
            skuDetails.oneTimePurchaseOfferDetails?.priceAmountMicros?.toDouble() ?: 0.0
        } else {
            val subsDetail = skuDetails.subscriptionOfferDetails ?: return 0.0
            val pricingPhaseList = subsDetail.last().pricingPhases.pricingPhaseList
            pricingPhaseList.last().priceAmountMicros.toDouble()
        }
    }

    fun getPriceWithCurrency(productId: String, typeIAP: Int): String {
        return try {
            val price = getInstance().getPriceWithoutCurrency(productId, typeIAP) / 1000000.0
            formatCurrency(price, getCurrency(productId, typeIAP))
        } catch (e: Exception) {
            ""
        }
    }

    fun getPriceWithCurrency(productId: String, typeIAP: Int, sale: Double): String {
        return try {
            val price = getInstance().getPriceWithoutCurrency(productId, typeIAP) / 1000000.0
            val priceOrigin = price / sale
            formatCurrency(priceOrigin, getCurrency(productId, typeIAP))
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatCurrency(price: Double, currency: String): String {
        if (currency.isEmpty()) return ""
        val format = NumberFormat.getCurrencyInstance()
        format.maximumFractionDigits = 0
        format.currency = Currency.getInstance(currency)
        return format.format(price)
    }

    fun setDiscount(discount: Double) {
        this.discount = discount
    }

    fun getDiscount(): Double = discount

    @Deprecated("Use syncPurchaseItemsToListProduct instead")
    private fun listIdToListProduct(listId: List<String>, styleBilling: String): ArrayList<QueryProductDetailsParams.Product> {
        val listProduct = ArrayList<QueryProductDetailsParams.Product>()
        for (id in listId) {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(styleBilling)
                .build()
            listProduct.add(product)
        }
        return listProduct
    }

    private fun syncPurchaseItemsToListProduct(purchaseItems: List<PurchaseItem>) {
        val listInAppProduct = ArrayList<QueryProductDetailsParams.Product>()
        val listSubsProduct = ArrayList<QueryProductDetailsParams.Product>()
        for (item in purchaseItems) {
            val product: QueryProductDetailsParams.Product
            if (item.type == TYPE_IAP.PURCHASE) {
                product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(item.itemId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                listInAppProduct.add(product)
            } else {
                product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(item.itemId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                listSubsProduct.add(product)
            }
        }
        this.listINAPId = listInAppProduct
        this.listINAPProductIds = purchaseItems.filter { it.type == TYPE_IAP.PURCHASE }.map { it.itemId }
        Log.d(TAG, "syncPurchaseItemsToListProduct: listINAPId ${this.listINAPId.size}")
        this.listSubscriptionId = listSubsProduct
        this.listSubscriptionProductIds = purchaseItems.filter { it.type != TYPE_IAP.PURCHASE }.map { it.itemId }
        Log.d(TAG, "syncPurchaseItemsToListProduct: listSubscriptionId ${this.listSubscriptionId.size}")
    }

    @IntDef(TYPE_IAP.PURCHASE, TYPE_IAP.SUBSCRIPTION)
    @Retention(AnnotationRetention.SOURCE)
    annotation class TYPE_IAP {
        companion object {
            const val PURCHASE = 1
            const val SUBSCRIPTION = 2
        }
    }

    companion object {
        private val LICENSE_KEY: String? = null
        private val MERCHANT_ID: String? = null
        private const val TAG = "PurchaseEG"

        const val PRODUCT_ID_TEST = "android.test.purchased"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppPurchase? = null

        
        fun getInstance(): AppPurchase {
            return instance ?: synchronized(this) {
                instance ?: AppPurchase().also { instance = it }
            }
        }
    }
}
