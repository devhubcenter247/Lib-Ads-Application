package com.lib.ads.gma.ads.billing

import android.app.Activity
import android.app.Application
import com.lib.ads.gma.ads.engine.Ads
import com.lib.ads.gma.ads.util.AppUtil
import com.lib.ads.gma.ads.util.AppLogger
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AppPurchase private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null

    private var connectionManager: BillingConnectionManager? = null
    private var repository: ProductDetailsRepository? = null
    private var verifier: PurchaseVerifier? = null
    private var processor: PurchaseProcessor? = null

    private var billingListener: BillingListener? = null
    private val billingCallbackDelivered = AtomicBoolean(false)
    private val billingDataReady = AtomicBoolean(false)
    @Volatile
    private var billingDataResultCode = BillingClient.BillingResponseCode.ERROR
    private var updatePurchaseListener: UpdatePurchaseListener? = null
    private var compatibilityPurchaseListener: PurchaseListener? = null
    private var enableTrackingRevenue: Boolean = false
    private var logProductDetail: Boolean = false
    private var discount: Double = 1.0
    private var configuredPurchaseItems: List<PurchaseItem> = emptyList()

    val billingState: StateFlow<BillingState>?
        get() = connectionManager?.state

    fun isPurchasedFlow(): StateFlow<Boolean>? = verifier?.isPurchasedFlow()

    fun setBillingListener(billingListener: BillingListener?) {
        AppLogger.d(
            TAG,
            "setBillingListener: listener=${billingListener != null}, available=${isAvailable()}, initialized=${getInitBillingFinish()}"
        )
        setBillingListener(DEFAULT_BILLING_LISTENER_TIMEOUT_MS, billingListener)
    }

    fun setBillingListener(timeout: Int, billingListener: BillingListener?) {
        AppLogger.d(TAG, "setBillingListener: timeout=$timeout")
        this.billingListener = billingListener
        billingCallbackDelivered.set(false)
        if (billingDataReady.get()) {
            AppLogger.d(TAG, "setBillingListener: already finished")
            notifyBillingListener(billingDataResultCode)
            return
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeout.toLong())
            AppLogger.d(TAG, "setBillingListener: timeout fired")
            deliverBillingResult(BillingClient.BillingResponseCode.ERROR)
        }
    }

    private fun notifyBillingListener(code: Int) {
        timeoutJob?.cancel()
        billingDataResultCode = code
        deliverBillingResult(code)
    }

    /**
     * Waits for Billing first and then Ads initialization, with independent timeouts.
     * The final listener callback is always delivered on the main thread.
     */
    fun getBillingAndAwaitInitAds(
        billingTimeout: Int,
        initAdsTimeout: Long,
        listener: BillingListener?,
    ) = apply {
        AppLogger.d(
            TAG,
            "initBillingAndAwaitInitAds: billingTimeout=$billingTimeout, " +
                "initAdsTimeout=$initAdsTimeout"
        )
        setBillingListener(billingTimeout) { billingCode ->
            if (billingCode != BillingClient.BillingResponseCode.OK) {
                Ads.runOnMain { listener?.onInitBillingFinished(billingCode) }
                return@setBillingListener
            }
            Ads.getInstance().awaitReady(initAdsTimeout) { adsReady ->
                val resultCode = if (adsReady) {
                    BillingClient.BillingResponseCode.OK
                } else {
                    BillingClient.BillingResponseCode.ERROR
                }
                AppLogger.d(
                    TAG,
                    "initBillingAndAwaitInitAds: adsReady=$adsReady, resultCode=$resultCode"
                )
                listener?.onInitBillingFinished(resultCode)
            }
        }
    }

    private fun deliverBillingResult(code: Int) {
        if (!billingCallbackDelivered.compareAndSet(false, true)) {
            AppLogger.d(TAG, "billing callback already delivered; ignoring code=$code")
            return
        }
        Ads.runOnMain {
            billingListener?.onInitBillingFinished(code)
        }
    }

    fun setUpdatePurchaseListener(listener: UpdatePurchaseListener?) {
        AppLogger.d(TAG, "setUpdatePurchaseListener: listener=${listener != null}")
        this.updatePurchaseListener = listener
    }

    fun setPurchaseListener(listener: PurchaseListener?) {
        AppLogger.d(TAG, "setPurchaseListener: listener=${listener != null}")
        compatibilityPurchaseListener = listener
    }

    fun isPurchased(activity: Activity): Boolean = isPurchased()

    fun purchase(activity: Activity, productId: String) {
        purchase(activity, productId) { event ->
            when (event) {
                is PurchaseEvent.Success -> compatibilityPurchaseListener?.onProductPurchased(
                    productId,
                    event.toString()
                )

                PurchaseEvent.Cancelled -> compatibilityPurchaseListener?.onUserCancelBilling()
                is PurchaseEvent.Error -> compatibilityPurchaseListener?.displayErrorMessage(event.message)
                else -> Unit
            }
        }
    }

    fun setEnableTrackingRevenue(enable: Boolean) {
        enableTrackingRevenue = enable
    }

    fun getEnableTrackingRevenue(): Boolean = enableTrackingRevenue

    /** Enable or disable verbose Play Store ProductDetails logs. */
    fun setLogProductDetail(enable: Boolean) = apply {
        logProductDetail = enable
        AppLogger.d(TAG, "setLogProductDetail: enabled=$enable")
    }

    fun setDiscount(discount: Double) {
        this.discount = discount
    }

    fun getDiscount(): Double = discount

    fun initBilling(application: Application, purchaseItemList: MutableList<PurchaseItem>) = apply {
        AppLogger.i(
            TAG,
            "initBilling: products=${purchaseItemList.size}, variantDev=${AppUtil.VARIANT_DEV}, " +
                "productDetailsLog=$logProductDetail, enableTrackingRevenue=$enableTrackingRevenue"
        )
        if (connectionManager != null) {
            AppLogger.w(TAG, "initBilling: already initialized, skipping")
            return@apply
        }
        configuredPurchaseItems = purchaseItemList.toList()
        val repo = ProductDetailsRepository()
        val vfy = PurchaseVerifier()
        val connMgr = BillingConnectionManager()
        repository = repo
        verifier = vfy
        connectionManager = connMgr

        val proc = PurchaseProcessor(
            connectionManager = connMgr,
            repository = repo,
            verifier = vfy,
            useDevTestPurchase = { AppUtil.VARIANT_DEV },
        ).apply {
            onPurchaseStatusUpdate = { triggerUpdatePurchaseStatus() }
        }
        processor = proc

        connMgr.connect(
            application,
            purchasesUpdatedListener = { billingResult, purchases ->
                proc.onPurchasesUpdated(billingResult, purchases)
            },
            onSetupFinished = { billingResult: BillingResult, isFirstSetup: Boolean ->
                val verificationDone = AtomicBoolean(false)
                val productQueryDone = AtomicBoolean(false)
                var verificationCode = BillingClient.BillingResponseCode.OK
                var productQueryCode = BillingClient.BillingResponseCode.OK

                fun notifyWhenBillingDataReady() {
                    if (verificationDone.get() && productQueryDone.get()) {
                        billingDataReady.set(true)
                        notifyBillingListener(
                            if (verificationCode == BillingClient.BillingResponseCode.OK) {
                                productQueryCode
                            } else {
                                verificationCode
                            }
                        )
                    }
                }

                if (isFirstSetup && billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val productsToQuery = configuredPurchaseItems.toMutableList().apply {
                        if (AppUtil.VARIANT_DEV) {
                            add(PurchaseItem(PRODUCT_ID_TEST, 1, "", false))
                        }
                    }
                    AppLogger.d(
                        TAG,
                        "initBilling: productsToQuery=${productsToQuery.map { it.itemId }}, " +
                            "usingDevTestProduct=${AppUtil.VARIANT_DEV}"
                    )
                    repo.syncPurchaseItemsToListProduct(productsToQuery)
                    vfy.verifyAndRefresh(
                        connMgr.getBillingClient(),
                        repo.listINAPId,
                        repo.listSubscriptionId,
                        repo.productIdMap,
                        shouldConsume = { productId -> repo.shouldConsume(productId) },
                        getEntitlements = { productId -> repo.getEntitlements(productId) },
                        onComplete = { code ->
                            AppLogger.i(
                                TAG,
                                "initBilling: purchase verification completed code=$code"
                            )
                            verificationCode = code
                            verificationDone.set(true)
                            notifyWhenBillingDataReady()
                        }
                    )
                } else if (isFirstSetup) {
                    billingDataReady.set(true)
                    AppLogger.w(
                        TAG,
                        "initBilling: setup failed; skipping purchase verification code=${billingResult.responseCode}"
                    )
                    notifyBillingListener(
                        billingResult.responseCode
                    )
                }
                when (billingResult.responseCode) {
                    0 -> {
                        AppLogger.d(TAG, "initBilling: billing setup OK; querying product details")
                        val client = connMgr.getBillingClient()
                        if (client != null) {
                            repo.queryProducts(
                                billingClient = client,
                                enableProductDetailsLog = logProductDetail,
                                onComplete = { queryResult ->
                                    AppLogger.i(
                                        TAG,
                                        "initBilling: product details query completed " +
                                            "code=${queryResult.responseCode}, " +
                                            "message=${queryResult.debugMessage}, " +
                                            "inApp=${repo.getInAppProductIds()}, " +
                                            "subscriptions=${repo.getSubscriptionProductIds()}"
                                    )
                                    productQueryCode = queryResult.responseCode
                                    productQueryDone.set(true)
                                    notifyWhenBillingDataReady()
                                },
                            )
                        }
                    }

                    2, 6 -> AppLogger.e(
                        TAG,
                        "onBillingSetupFinished: ERROR code=${billingResult.responseCode}, message=${billingResult.debugMessage}"
                    )
                }
            }
        )
    }

    private fun triggerUpdatePurchaseStatus() {
        AppLogger.d(TAG, "updatePurchaseStatus: started")
        val repo = repository ?: return
        val vfy = verifier ?: return
        vfy.updatePurchaseStatus(
            connectionManager?.getBillingClient(),
            repo.listINAPId,
            repo.listSubscriptionId,
            repo.productIdMap,
            shouldConsume = { productId -> repo.shouldConsume(productId) },
            getEntitlements = { productId -> repo.getEntitlements(productId) },
            onComplete = {
                AppLogger.d(TAG, "updatePurchaseStatus: completed")
                updatePurchaseListener?.onUpdateFinished()
            }
        )
    }

    fun isAvailable(): Boolean = connectionManager?.isAvailable ?: false

    fun getInitBillingFinish(): Boolean = connectionManager?.isInitBillingFinish ?: false

    /** True iff the user owns the [Entitlement.REMOVE_ADS] entitlement. Every ad-gating check in the SDK reads this. */
    fun isPurchased(): Boolean = verifier?.isPurchased ?: false

    fun setPurchase(purchase: Boolean) {
        verifier?.isPurchased = purchase
    }

    /** True iff the user owns [entitlement] — e.g. [Entitlement.PREMIUM_FEATURES] to gate paid app features independently of ad suppression. */
    fun hasEntitlement(entitlement: Entitlement): Boolean =
        verifier?.hasEntitlement(entitlement) ?: false

    fun getOwnedEntitlements(): Set<Entitlement> = verifier?.ownedEntitlements() ?: emptySet()

    fun ownedEntitlementsFlow(): StateFlow<Set<Entitlement>>? = verifier?.ownedEntitlementsFlow()

    fun getOwnerIdSubs(): List<PurchaseResult> = verifier?.getOwnerIdSubs() ?: emptyList()

    fun getOwnerIdInApp(): List<PurchaseResult> = verifier?.getOwnerIdInApp() ?: emptyList()

    fun purchase(activity: Activity, productId: String, onResult: (PurchaseEvent) -> Unit) {
        AppLogger.d(
            TAG,
            "purchase: requested productId=$productId, processorReady=${processor != null}"
        )
        val proc = processor
        if (proc != null) {
            proc.purchase(activity, productId, onResult)
        } else {
            onResult(PurchaseEvent.Error(6, "Billing not initialized"))
        }
    }

    fun subscribe(activity: Activity, subsId: String, onResult: (PurchaseEvent) -> Unit) {
        AppLogger.d(
            TAG,
            "subscribe: requested productId=$subsId, processorReady=${processor != null}"
        )
        val proc = processor
        if (proc != null) {
            proc.subscribe(activity, subsId, onResult)
        } else {
            onResult(PurchaseEvent.Error(6, "Billing not initialized"))
        }
    }

    fun upgradeSubscription(
        activity: Activity,
        newSubsId: String,
        oldPurchaseToken: String,
        replacementMode: Int = 0,
        onResult: (PurchaseEvent) -> Unit
    ) {
        AppLogger.d(
            TAG,
            "upgradeSubscription: requested newProductId=$newSubsId, replacementMode=$replacementMode"
        )
        val proc = processor
        if (proc != null) {
            proc.upgradeSubscription(
                activity,
                newSubsId,
                oldPurchaseToken,
                replacementMode,
                onResult
            )
        } else {
            onResult(PurchaseEvent.Error(6, "Billing not initialized"))
        }
    }

    fun getSubscriptionPurchaseToken(subsId: String): String? =
        verifier?.getSubscriptionPurchaseToken(subsId)

    fun consumePurchase(productId: String) {
        AppLogger.d(
            TAG,
            "consumePurchase: requested productId=$productId, processorReady=${processor != null}"
        )
        processor?.consumePurchase(productId)
    }

    fun updatePurchaseStatus() {
        triggerUpdatePurchaseStatus()
    }

    fun getProductInfoList(): List<BillingProductInfo> =
        repository?.getProductInfoList() ?: emptyList()

    fun getInAppProductIds(): List<String> = repository?.getInAppProductIds() ?: emptyList()

    fun getSubscriptionProductIds(): List<String> =
        repository?.getSubscriptionProductIds() ?: emptyList()

    fun getAllProductIds(): List<String> = repository?.getAllProductIds() ?: emptyList()

    fun getPrice(productId: String): String =
        repository?.getPrice(productId) ?: ""

    fun getName(productId: String, typeIap: Int): String =
        repository?.getName(productId, typeIap) ?: ""

    fun getPriceSub(productId: String): String =
        repository?.getPriceSub(productId) ?: ""

    fun getPeriod(productId: String): String =
        repository?.getPeriod(productId) ?: ""

    fun getTrialPeriod(productId: String): String =
        repository?.getTrialPeriod(productId) ?: ""

    fun getIntroductorySubPrice(productId: String, offerId: String? = null): String =
        repository?.getIntroductorySubPrice(productId, offerId) ?: ""

    fun getCurrency(productId: String, typeIAP: Int): String =
        repository?.getCurrency(productId, typeIAP) ?: ""

    fun getPriceWithoutCurrency(productId: String, typeIAP: Int): Double =
        repository?.getPriceWithoutCurrency(productId, typeIAP) ?: 0.0

    fun getPriceWithCurrency(productId: String, typeIAP: Int): String =
        repository?.getPriceWithCurrency(productId, typeIAP) ?: ""

    fun getPriceWithCurrency(productId: String, typeIAP: Int, sale: Double): String =
        repository?.getPriceWithCurrency(productId, typeIAP, sale) ?: ""

    fun getPricePricingPhaseList(productId: String): List<ProductDetails.PricingPhase>? =
        repository?.getPricePricingPhaseList(productId)

    fun getListInAppId(): List<String> = repository?.getListInAppId() ?: emptyList()

    fun getListSubId(): List<String> = repository?.getListSubId() ?: emptyList()

    object TYPE_IAP {
        const val PURCHASE = 1
        const val SUBSCRIPTION = 2
    }

    @Retention(AnnotationRetention.SOURCE)
    annotation class TypeIAPAnnotation

    companion object {
        private const val TAG = "GMA_Purchase"
        private const val DEFAULT_BILLING_LISTENER_TIMEOUT_MS = 5_000
        const val PRODUCT_ID_TEST = "android.test.purchased"

        @Volatile
        private var instance: AppPurchase? = null

        @JvmStatic
        fun getInstance(): AppPurchase =
            instance ?: synchronized(AppPurchase::class.java) {
                instance ?: AppPurchase().also { instance = it }
            }
    }
}
