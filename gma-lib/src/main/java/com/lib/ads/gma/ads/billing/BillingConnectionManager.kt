package com.lib.ads.gma.ads.billing

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.lib.ads.gma.ads.util.AppLogger

internal class BillingConnectionManager {

    private var billingClient: BillingClient? = null

    @Volatile var isAvailable: Boolean = false
        private set

    @Volatile var isInitBillingFinish: Boolean = false
        private set

    private val _state: MutableStateFlow<BillingState> = MutableStateFlow(BillingState.Disconnected)
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private var reconnectAttempts: Int = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var stateListener: BillingClientStateListener? = null

    fun connect(
        application: Application,
        purchasesUpdatedListener: PurchasesUpdatedListener,
        onSetupFinished: (BillingResult, Boolean) -> Unit
    ) {
        AppLogger.i(TAG, "connect: starting BillingClient connection")
        _state.value = BillingState.Connecting
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
        AppLogger.d(TAG, "connect: BillingClient created")

        var isFirstSetup = true
        stateListener = object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                AppLogger.d(TAG, "onBillingSetupFinished: code=${billingResult.responseCode}")
                reconnectAttempts = 0
                isInitBillingFinish = true
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isAvailable = true
                    _state.value = BillingState.Connected
                } else {
                    isAvailable = false
                    _state.value = BillingState.Error(billingResult.responseCode, billingResult.debugMessage)
                }
                AppLogger.i(TAG, "onBillingSetupFinished: available=$isAvailable, state=${_state.value}")
                onSetupFinished(billingResult, isFirstSetup)
                isFirstSetup = false
            }

            override fun onBillingServiceDisconnected() {
                AppLogger.w(TAG, "onBillingServiceDisconnected: attempt=$reconnectAttempts")
                isAvailable = false
                _state.value = BillingState.Disconnected
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    val delayMs = (reconnectAttempts * 2_000L).coerceAtMost(30_000L)
                    reconnectHandler.postDelayed({
                        AppLogger.d(TAG, "reconnect: retry=$reconnectAttempts delayMs=$delayMs")
                        val sl = stateListener
                        if (sl != null) {
                            billingClient?.startConnection(sl)
                        }
                    }, delayMs)
                }
            }
        }
        val sl = stateListener
        if (sl != null) {
            billingClient?.startConnection(sl)
        }
    }

    internal suspend fun awaitConnection(): BillingClient? {
        _state.first { it is BillingState.Connected }
        return billingClient
    }

    fun getBillingClient(): BillingClient? = billingClient

    companion object {
        private const val TAG = "BillingConnectionMgr"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
