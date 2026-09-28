package com.lib.ads.gma.ads.helper.splash

import android.app.Activity
import android.app.Dialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lib.ads.gma.ads.billing.AppPurchase
import com.lib.ads.gma.ads.dialog.PrepareLoadingAdsDialog
import com.lib.ads.gma.ads.helper.IAdsConfig
import com.lib.ads.gma.ads.manager.AdsManager
import com.lib.ads.gma.ads.util.AdType
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────

sealed class SplashAdState {
    data object None : SplashAdState()
    data object Loading : SplashAdState()
    data class Ready(val interstitialAd: InterstitialAd) : SplashAdState()
    data object Showing : SplashAdState()
    data object Timeout : SplashAdState()
    data class LoadFailed(val errorMessage: String? = null) : SplashAdState()
    data class ShowFailed(val errorMessage: String? = null) : SplashAdState()
    data object Dismissed : SplashAdState()
}

// ──────────────────────────────────────────────
// Config
// ──────────────────────────────────────────────

open class SplashAdConfig(
    override var listId: List<String>,
    override val canShowAds: Boolean,
    val timeout: Long = 15_000L,
    val minDelay: Long = 3_000L
) : IAdsConfig {

    constructor(idAds: String, canShowAds: Boolean, timeout: Long = 15_000L, minDelay: Long = 3_000L)
        : this(listOf(idAds), canShowAds, timeout, minDelay)

    override val canReloadAds: Boolean = false

    var showDelay: Long = 800L

    fun setListId(list: List<String>) = apply {
        this.listId = list
    }

    fun setShowDelay(delayMs: Long) = apply {
        this.showDelay = delayMs
    }

    companion object {
        fun create(
            adId: String,
            canShow: Boolean = true,
            timeout: Long = 15_000L,
            minDelay: Long = 3_000L
        ): SplashAdConfig {
            return SplashAdConfig(adId, canShow, timeout, minDelay)
        }
    }
}

// ──────────────────────────────────────────────
// Helper
// ──────────────────────────────────────────────

class SplashAdHelper(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val config: SplashAdConfig
) {
    companion object {
        private const val TAG = "SplashAdHelper"
        // The ad window needs a brief moment to finish its entrance transition and fully cover
        // the screen; the loading dialog dismissal is delayed by this much to bridge that gap.
        private const val AD_TRANSITION_COVER_DELAY = 300L
    }

    private val _adState = MutableStateFlow<SplashAdState>(SplashAdState.None)
    val adState: StateFlow<SplashAdState> = _adState.asStateFlow()

    private var splashAd: InterstitialAd? = null
    private val isActive = AtomicBoolean(false)
    private val isTimeout = AtomicBoolean(false)
    private val isDelayComplete = AtomicBoolean(false)
    private var currentJob: Job? = null
    private var loadingDialog: Dialog? = null
    private var adReadyDeferred: CompletableDeferred<InterstitialAd?>? = null

    var disableAdResumeWhenClickAds: Boolean = false
    var openActivityAfterShowInterAds: Boolean = false

    init {
        lifecycleOwner.lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    cancel()
                    dismissLoadingDialog()
                    owner.lifecycle.removeObserver(this)
                }
            }
        )

        _adState.onEach { state ->
            log("State changed: ${state::class.simpleName}")
        }.launchIn(lifecycleOwner.lifecycleScope)
    }

    fun loadAndShow(
        onAdReady: (() -> Unit)? = null,
        onNextAction: () -> Unit,
        onAdClosed: (() -> Unit)? = null,
        onAdFailedToLoad: ((String?) -> Unit)? = null
    ) {
        if (AppPurchase.getInstance().isPurchased(activity)) {
            log("User has purchased - skipping ad")
            onNextAction()
            return
        }

        if (!config.canShowAds) {
            log("Ads disabled in config")
            onNextAction()
            return
        }

        isActive.set(true)
        isTimeout.set(false)
        isDelayComplete.set(false)

        currentJob = lifecycleOwner.lifecycleScope.launch {
            try {
                _adState.emit(SplashAdState.Loading)

                val delayJob = launch {
                    delay(config.minDelay)
                    isDelayComplete.set(true)
                    log("Minimum delay complete")

                    splashAd?.let {
                        onAdReady?.invoke()
                    }
                }

                val loadedAd = try {
                    withTimeout(config.timeout.milliseconds) {
                        loadAd()
                    }
                } catch (e: TimeoutCancellationException) {
                    log("Loading timed out")
                    isTimeout.set(true)
                    _adState.emit(SplashAdState.Timeout)
                    null
                }

                if (isTimeout.get()) {
                    if (loadedAd != null) {
                        splashAd = loadedAd
                        log("Ad loaded after timeout - showing anyway")
                        _adState.emit(SplashAdState.Ready(loadedAd))
                        onAdReady?.invoke()
                    } else {
                        log("Timeout with no ad - proceeding")
                        onNextAction()
                        return@launch
                    }
                }

                if (loadedAd == null && !isTimeout.get()) {
                    log("Failed to load ad")
                    _adState.emit(SplashAdState.LoadFailed("Failed to load"))
                    onAdFailedToLoad?.invoke("Failed to load")
                    onNextAction()
                    return@launch
                }

                splashAd = loadedAd

                if (!isDelayComplete.get()) {
                    log("Waiting for minimum delay to complete...")
                    delayJob.join()
                }

                if (loadedAd != null) {
                    _adState.emit(SplashAdState.Ready(loadedAd))
                    onAdReady?.invoke()
                }

            } catch (e: CancellationException) {
                log("Loading cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                log("Error: ${e.message}")
                _adState.emit(SplashAdState.LoadFailed(e.message))
                onAdFailedToLoad?.invoke(e.message)
                onNextAction()
            }
        }
    }

    fun showIfReady(
        onNextAction: () -> Unit,
        onAdClosed: (() -> Unit)? = null
    ) {
        val ad = splashAd
        if (ad == null) {
            log("showIfReady: No ad available")
            onNextAction()
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            showAd(ad, onNextAction, onAdClosed)
        }
    }

    private suspend fun loadAd(): InterstitialAd? {
        return loadFromList(config.listId)
    }

    private suspend fun loadFromList(adIds: List<String>): InterstitialAd? {
        for ((index, adId) in adIds.withIndex()) {
            log("Trying ad ID at index $index: $adId")

            if (isTimeout.get() && !isActive.get()) {
                log("Stopped loading due to timeout/cancellation")
                return null
            }

            val result = loadSingleAd(adId)
            if (result != null) {
                log("Successfully loaded ad at index $index")
                return result
            }
            log("Failed at index $index, trying next...")
        }
        return null
    }

    private suspend fun loadSingleAd(adId: String): InterstitialAd? {
        return suspendCancellableCoroutine { continuation ->
            val appCtx = activity.applicationContext

            InterstitialAd.load(
                activity,
                adId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        log("onAdLoaded: $adId")
                        ad.setImmersiveMode(true)

                        ad.setOnPaidEventListener { adValue ->
                            log("OnPaidEvent: ${adValue.valueMicros}")
                            AdsManager.logPaidEvent(appCtx, adValue, ad.adUnitId, ad.responseInfo, AdType.INTERSTITIAL)
                        }

                        if (continuation.isActive) {
                            continuation.resume(ad)
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        log("onAdFailedToLoad: ${error.message}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                log("Ad loading cancelled for: $adId")
            }
        }
    }

    private suspend fun showAd(
        ad: InterstitialAd,
        onNextAction: () -> Unit,
        onAdClosed: (() -> Unit)?
    ) {
        _adState.emit(SplashAdState.Showing)
        showLoadingDialog()

        val adUnitId = ad.adUnitId
        val appCtx = activity.applicationContext

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                log("onAdShowedFullScreenContent")
                AdsManager.setFullScreenAdShowing(true)
                // Delay dismissing the loading dialog briefly so it keeps covering the screen
                // through the ad window's entrance transition, avoiding a flash of the activity
                // underneath before the ad fully covers it.
                lifecycleOwner.lifecycleScope.launch {
                    delay(AD_TRANSITION_COVER_DELAY)
                    dismissLoadingDialog()
                }
            }

            override fun onAdDismissedFullScreenContent() {
                log("onAdDismissedFullScreenContent")
                AdsManager.setFullScreenAdShowing(false)
                dismissLoadingDialog()
                splashAd = null

                lifecycleOwner.lifecycleScope.launch {
                    _adState.emit(SplashAdState.Dismissed)
                }

                onNextAction()
                onAdClosed?.invoke()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                log("onAdFailedToShowFullScreenContent: ${adError.message}")
                AdsManager.setFullScreenAdShowing(false)
                dismissLoadingDialog()
                splashAd = null

                lifecycleOwner.lifecycleScope.launch {
                    _adState.emit(SplashAdState.ShowFailed(adError.message))
                }

                onNextAction()
            }

            override fun onAdClicked() {
                log("onAdClicked")
                AdsManager.handleAdClick(appCtx, adUnitId)
            }

            override fun onAdImpression() {
                log("onAdImpression")
                AdsManager.handleAdImpression()
            }
        }

        delay(config.showDelay)

        ad.show(activity)
    }

    fun isAdLoaded(): Boolean = splashAd != null

    fun cancel() {
        log("cancel() called")
        isActive.set(false)
        currentJob?.cancel()
        currentJob = null
        splashAd = null
    }

    private fun showLoadingDialog() {
        try {
            dismissLoadingDialog()
            loadingDialog = PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
        } catch (e: Exception) {
            log("Error showing dialog: ${e.message}")
        }
    }

    private fun dismissLoadingDialog() {
        try {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
            loadingDialog = null
        } catch (e: Exception) {
            log("Error dismissing dialog: ${e.message}")
        }
    }

    private fun log(message: String) {
        android.util.Log.d(TAG, message)
    }
}
