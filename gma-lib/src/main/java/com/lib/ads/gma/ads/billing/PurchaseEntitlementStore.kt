package com.lib.ads.gma.ads.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.lib.ads.gma.ads.util.AppLogger

/** Owns verified purchases and derives the public entitlement state from them. */
internal class PurchaseEntitlementStore {
    private val _isPurchased = MutableStateFlow(false)
    private val _ownedEntitlements = MutableStateFlow<Set<Entitlement>>(emptySet())
    private val ownerLock = Any()
    private val ownerIdSubs = mutableListOf<PurchaseResult>()
    private val ownerIdInApp = mutableListOf<PurchaseResult>()

    val isPurchasedFlow: StateFlow<Boolean> = _isPurchased.asStateFlow()
    val ownedEntitlementsFlow: StateFlow<Set<Entitlement>> = _ownedEntitlements.asStateFlow()
    val isPurchased: Boolean get() = _isPurchased.value
    val ownedEntitlements: Set<Entitlement> get() = _ownedEntitlements.value

    fun setPurchased(value: Boolean) {
        AppLogger.d(TAG, "setPurchased: value=$value")
        _ownedEntitlements.update {
            if (value) it + Entitlement.REMOVE_ADS else it - Entitlement.REMOVE_ADS
        }
        _isPurchased.value = value
    }

    fun grant(entitlements: Set<Entitlement>) {
        if (entitlements.isEmpty()) return
        AppLogger.i(TAG, "grant: entitlements=$entitlements")
        _ownedEntitlements.update { it + entitlements }
        if (Entitlement.REMOVE_ADS in entitlements) _isPurchased.value = true
    }

    fun replaceFromOwners(getEntitlements: (String) -> Set<Entitlement>) {
        val ownedIds = (getOwnerIdSubs() + getOwnerIdInApp()).flatMap { it.productId.orEmpty() }
        val entitlements = ownedIds.flatMap(getEntitlements).toSet()
        _ownedEntitlements.value = entitlements
        _isPurchased.value = Entitlement.REMOVE_ADS in entitlements
        AppLogger.i(TAG, "replaceFromOwners: owners=${ownedIds.size}, entitlements=$entitlements, isPurchased=${_isPurchased.value}")
    }

    fun addInApp(purchase: PurchaseResult, productId: String) = addOrUpdate(ownerIdInApp, purchase, productId)
    fun addSubscription(purchase: PurchaseResult, productId: String) = addOrUpdate(ownerIdSubs, purchase, productId)

    fun clearInApp() = synchronized(ownerLock) { ownerIdInApp.clear() }
    fun clearSubscriptions() = synchronized(ownerLock) { ownerIdSubs.clear() }

    fun getOwnerIdSubs(): List<PurchaseResult> = synchronized(ownerLock) { ownerIdSubs.toList() }
    fun getOwnerIdInApp(): List<PurchaseResult> = synchronized(ownerLock) { ownerIdInApp.toList() }

    fun getSubscriptionPurchaseToken(subsId: String): String? = synchronized(ownerLock) {
        ownerIdSubs.find { it.productId?.contains(subsId) == true }?.purchaseToken
    }

    private fun addOrUpdate(target: MutableList<PurchaseResult>, purchase: PurchaseResult, productId: String) {
        synchronized(ownerLock) {
            val index = target.indexOfFirst { it.productId?.contains(productId) == true }
            if (index >= 0) target[index] = purchase else target.add(purchase)
            AppLogger.d(TAG, "addOrUpdate: productId=$productId, updated=${index >= 0}, total=${target.size}")
        }
    }

    companion object { private const val TAG = "GMA_Purchase" }
}
