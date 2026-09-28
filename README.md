# AdsApplication

An Android ad management library providing unified integration for AdMob ads, in-app purchases, and analytics (Adjust, AppsFlyer, Firebase).

## Features

- **AdMob Ads** — Banner, Native, Interstitial, Rewarded, and App Open ads with lifecycle-aware helpers
- **In-App Purchases** — Google Play Billing integration
- **Analytics** — Adjust, AppsFlyer, Facebook, and Firebase analytics
- **UMP Consent** — GDPR consent management via Google's User Messaging Platform
- **Jetpack Compose** — Compose UI support for banner and native ads
- **Native Ad Preloading** — Preload and cache native ads for instant display

## Project Structure

| Module | Description |
|--------|-------------|
| `adlib` | Core ad library with all integrations |
| `app` | Sample application demonstrating usage |

---

## Requirements

- Android SDK 24+ (minSdk)
- Android SDK 36 (compileSdk / targetSdk)
- JDK 11
- Gradle 8.13+
- Kotlin 2.1.0

---

## Integration

### 1. Add the library module

In `settings.gradle.kts`:

```kotlin
include(":adlib")
```

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":adlib"))
}
```

### 2. Configure `AndroidManifest.xml`

```xml
<application
    android:name=".MyApplication"
    ...>

    <!-- AdMob App ID (use product flavor placeholder) -->
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="${ad_app_id}" />

    <!-- Facebook SDK (if using Meta mediation) -->
    <meta-data
        android:name="com.facebook.sdk.ApplicationId"
        android:value="@string/facebook_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ClientToken"
        android:value="@string/facebook_client_token" />

    <!-- Adjust install referrer receiver -->
    <receiver
        android:name="com.adjust.sdk.AdjustReferrerReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="com.android.vending.INSTALL_REFERRER" />
        </intent-filter>
    </receiver>
</application>
```

### 3. Configure ad unit IDs via product flavors

In `app/build.gradle.kts`:

```kotlin
android {
    flavorDimensions += "env"
    productFlavors {
        create("appDev") {
            manifestPlaceholders["ad_app_id"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ad_banner",             "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "ad_native",             "\"ca-app-pub-3940256099942544/2247696110\"")
            buildConfigField("String", "ad_interstitial_splash","\"ca-app-pub-3940256099942544/1033173712\"")
            buildConfigField("String", "ad_reward",             "\"ca-app-pub-3940256099942544/5224354917\"")
            buildConfigField("String", "ad_appopen_resume",     "\"ca-app-pub-3940256099942544/9257395921\"")
        }
        create("appProd") {
            manifestPlaceholders["ad_app_id"] = "YOUR_PRODUCTION_APP_ID"
            buildConfigField("String", "ad_banner",             "\"YOUR_BANNER_AD_UNIT_ID\"")
            buildConfigField("String", "ad_native",             "\"YOUR_NATIVE_AD_UNIT_ID\"")
            buildConfigField("String", "ad_interstitial_splash","\"YOUR_INTERSTITIAL_AD_UNIT_ID\"")
            buildConfigField("String", "ad_reward",             "\"YOUR_REWARD_AD_UNIT_ID\"")
            buildConfigField("String", "ad_appopen_resume",     "\"YOUR_APP_OPEN_AD_UNIT_ID\"")
        }
    }
}
```

---

## Initialization

### Application class

Extend `AdsMultiDexApplication` and initialize the SDK in `onCreate()`:

```kotlin
class MyApplication : AdsMultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        initBilling()
        initAds()
    }

    private fun initAds() {
        val environment = if (BuildConfig.DEBUG) {
            AdSdkConfig.ENVIRONMENT_DEVELOP
        } else {
            AdSdkConfig.ENVIRONMENT_PRODUCTION
        }

        val config = AdSdkConfig(this, environment).apply {

            // UMP (GDPR) consent — remove if not needed
            adsConsentConfig = AdsConsentConfig(enableUMP = true).apply {
                isEnableDebug = BuildConfig.DEBUG
                testDevice = "YOUR_TEST_DEVICE_HASHED_ID"
                isResetData = false
            }

            // Adjust analytics — remove if not needed
            adjustConfig = AdjustConfig(enable = true, token = "YOUR_ADJUST_TOKEN").apply {
                eventAdImpression = "YOUR_ADJUST_AD_IMPRESSION_EVENT"
                eventNamePurchase = "YOUR_ADJUST_PURCHASE_EVENT"
            }

            // AppsFlyer analytics — remove if not needed
            appsflyerConfig = AppsflyerConfig(enable = true, token = "YOUR_APPSFLYER_TOKEN")

            // Test device IDs (hashed IDs from logcat)
            listDeviceTest = listOf("YOUR_TEST_DEVICE_HASHED_ID")

            // Minimum seconds between two interstitial shows (0 = no limit)
            intervalInterstitialAd = 0
        }

        AdSdkInitializer.init(this, config)

        // Disable resume ad while a click is being processed
        AdsManager.disableAdResumeWhenClickAds = true

        // Prevent App Open ad from showing on SplashActivity
        AdsManager.excludeAppOpen(SplashActivity::class.java)
    }

    private fun initBilling() {
        val products = listOf(
            PurchaseItem("your_product_id", AppPurchase.TYPE_IAP.PURCHASE)
        )
        AppPurchase.getInstance().initBilling(this, products)
    }
}
```

### Splash / first Activity

Request UMP consent, then start the ads network:

```kotlin
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            // 1. Request consent (blocks until resolved)
            val consentManager = AdsConsentManager(this@SplashActivity)
            consentManager.requestUMP()

            if (!consentManager.getCanRequestAd()) {
                finish()
                return@launch
            }

            // 2. Signal that consent is done — SDK loads ads
            AdSdkInitializer.setInitCallback {
                // 3. Now the ads network is ready
                initAppOpenAd()
                loadSplashInterstitial()
            }
        }
    }

    private fun initAppOpenAd() {
        AppOpenManager.getInstance().init(application, BuildConfig.ad_appopen_resume)
    }
}
```

---

## Ad Types

### Banner Ad

**XML layout:**

```xml
<FrameLayout
    android:id="@+id/fl_banner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

**Activity / Fragment:**

```kotlin
private val bannerHelper by lazy {
    BannerAdHelper(
        this,
        this, // LifecycleOwner
        BannerAdConfig(
            idAds        = BuildConfig.ad_banner,
            canShowAds   = true,
            canReloadAds = true
        )
    )
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bannerHelper.setBannerContentView(binding.flBanner)
    bannerHelper.requestAds(BannerAdParam.Request)
}
```

**Collapsible banner:**

```kotlin
BannerAdConfig(BuildConfig.ad_banner, true, true)
    .setCollapsibleGravity("bottom") // "top" or "bottom"
```

**Inline adaptive banner:**

```kotlin
BannerAdConfig(BuildConfig.ad_banner, true, true)
    .asInlineBanner(maxHeightDp = 100)
```

**Waterfall:** banner ids are tried sequentially and the first loaded id wins. Each
id has a 10-second timeout by default; timed-out banner views are destroyed and late
callbacks from those ids are ignored.

**Compose — simple drop-in:**

```kotlin
BannerAd(
    config = BannerAdConfig(
        idAds        = BuildConfig.ad_banner,
        canShowAds   = true,
        canReloadAds = true
    ),
    modifier = Modifier.fillMaxWidth()
)
```

**Compose — manual holder (reload / cancel control):**

```kotlin
val holder = rememberBannerAd(
    config = BannerAdConfig(BuildConfig.ad_banner, true, true),
    autoReloadOnResume = true,
    callback = object : AdsCallback() {
        override fun onAdLoaded() { /* ... */ }
    }
)

BannerAdCard(
    holder = holder,
    modifier = Modifier.width(250.dp), // request uses this measured width
    loading = { DefaultBannerAdLoading() },
    error   = { /* handle error */ },
    onStateChange = { state -> /* BannerAdDisplayState */ }
)

// Programmatic control
Button(onClick = { holder.reload() })  { Text("Reload") }
Button(onClick = { holder.cancel() })  { Text("Cancel") }
```

**BannerAdDisplayState values:**

| State | Meaning |
|-------|---------|
| `Idle` | Not yet started |
| `Loading` | Request in progress |
| `Loaded(adView)` | Ad ready — `holder.adView` is non-null |
| `Error(error)` | Load failed |
| `Cancelled` | Manually cancelled |

---

### Native Ad

Native ads use **`NativeAdSpec`** as the single config object. A *tag* uniquely identifies the placement and is also used as the preload bucket key.

Create a native ad layout (e.g. `res/layout/layout_native_ad.xml`) with `com.google.android.gms.ads.nativead.NativeAdView` as the root.

**XML placeholder:**

```xml
<FrameLayout
    android:id="@+id/fl_native_ad"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />

<com.facebook.shimmer.ShimmerFrameLayout
    android:id="@+id/shimmer_native"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

#### Activity — extension delegate (recommended)

The `nativeAd` / `nativeAdWaterfall` extension functions create a `NativeAdProviderHelper` via Kotlin delegation. The ad is requested automatically on first access.

**Single ad unit:**

```kotlin
// AppCompatActivity
private val nativeAdHelper by nativeAd(
    tag      = "home",
    adUnitId = BuildConfig.ad_native,
    layoutId = R.layout.layout_native_ad,
    shimmer  = { binding.shimmerNative },
    content  = { binding.flNativeAd },
)
```

**Waterfall (multiple ad units tried in order):**

```kotlin
private val nativeAdHelper by nativeAdWaterfall(
    tag       = "home",
    adUnitIds = listOf(BuildConfig.ad_native_high, BuildConfig.ad_native_low),
    layoutId  = R.layout.layout_native_ad,
    shimmer   = { binding.shimmerNative },
    content   = { binding.flNativeAd },
)
```

Native waterfall tries ids in priority order and stops at the first loaded ad. Per-id
timeouts are supported; late results from timed-out ids are destroyed and ignored.

**Fragment (explicit activity parameter):**

```kotlin
// Inside a Fragment
private val nativeAdHelper by nativeAd(
    activity = requireActivity(),
    tag      = "detail",
    adUnitId = BuildConfig.ad_native,
    layoutId = R.layout.layout_native_ad,
    shimmer  = { binding.shimmerNative },
    content  = { binding.flNativeAd },
)
```

**Optional parameters for all extension functions:**

```kotlin
private val nativeAdHelper by nativeAd(
    tag             = "home",
    adUnitId        = BuildConfig.ad_native,
    layoutId        = R.layout.layout_native_ad,
    shimmer         = { binding.shimmerNative },
    content         = { binding.flNativeAd },
    canShowAds      = true,
    canReloadAds    = true,
    autoRequest     = true,         // call CreateRequest on first access
    preloadBufferSize = 2,          // 0 = no preload
    adCallback      = object : AdsCallback() {
        override fun onNativeAdLoaded(ad: ApNativeAd) { /* ... */ }
    }
)
```

#### Activity / Fragment — manual setup

```kotlin
private val nativeAdHelper by lazy {
    val spec = NativeAdSpec(
        tag          = "home",
        adUnitIds    = listOf(BuildConfig.ad_native, BuildConfig.ad_native_fallback),
        canShowAds   = true,
        canReloadAds = true,
        defaultLayoutId = R.layout.layout_native_ad
    )

    NativeAdProviderHelper(this, this, spec)
        .setShimmerLayoutView(binding.shimmerNative)
        .setNativeContentView(binding.flNativeAd)
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    nativeAdHelper.requestAds(NativeAdParam.Request.CreateRequest)
}
```

#### Preloading (load ahead, serve instantly)

Configure preloading on `NativeAdSpec`, then register it early in the flow (e.g. Application or a previous screen):

```kotlin
// In Application.onCreate or before the screen that shows the ad
val spec = NativeAdSpec.simple(
    tag        = "home",
    adUnitId   = BuildConfig.ad_native,
    layoutId   = R.layout.layout_native_ad,
    bufferSize = 2,         // keep 2 ads cached
    autoRefill = true       // refill after each consumption
)
NativeAds.register(spec)

// Or use the waterfall + preload factory:
val spec = NativeAdSpec.waterfall(
    tag       = "home",
    adUnitIds = listOf(BuildConfig.ad_native_high, BuildConfig.ad_native_low),
    layoutId  = R.layout.layout_native_ad,
    bufferSize = 2
)
```

The `nativeAd()` extension also accepts `preloadBufferSize` to enable preloading inline:

```kotlin
private val nativeAdHelper by nativeAd(
    tag               = "home",
    adUnitId          = BuildConfig.ad_native,
    layoutId          = R.layout.layout_native_ad,
    shimmer           = { binding.shimmerNative },
    content           = { binding.flNativeAd },
    preloadBufferSize = 2   // enables preloading automatically
)
```

Preloading fills the warm buffer silently. `onNativeAdLoaded` is fired when an ad is
served to XML/Compose UI, not when a background preload slot is filled.

#### Compose — simple one-liner

```kotlin
// Single ad unit
NativeAd(
    tag      = "home",
    adUnitId = BuildConfig.ad_native,
    modifier = Modifier.fillMaxWidth(),
    loading  = { isShimmer -> if (isShimmer) ShimmerBox() },
    nativeView = { ad ->
        NativeAdView(nativeAd = ad) {
            // place NativeHeadline(), NativeBody(), NativeCallToAction(), etc.
            NativeHeadline()
            NativeBody()
            NativeCallToAction()
        }
    }
)

// Waterfall
NativeAd(
    tag      = "home",
    adUnitIds = listOf(BuildConfig.ad_native_high, BuildConfig.ad_native_low),
    nativeView = { ad -> MyNativeAdContent(ad) }
)
```

#### Compose — holder for full control

```kotlin
val holder = rememberNativeAdPreload(
    tag                = "home",
    fallbackAdUnitIds  = listOf(BuildConfig.ad_native),
    autoReloadOnResume = true,
    cancelOnPause      = false,
    adCallback         = object : AdsCallback() {
        override fun onNativeAdLoaded(ad: ApNativeAd) { /* ... */ }
    }
)

NativeAdCard(
    holder     = holder,
    modifier   = Modifier.fillMaxWidth(),
    loading    = { isShimmer -> if (isShimmer) ShimmerBox() },
    error      = { err -> /* handle */ },
    nativeView = { ad -> MyNativeAdContent(ad) }
)

// Programmatic control
Button(onClick = { holder.reload() })      { Text("Reload") }
Button(onClick = { holder.cancel() })      { Text("Cancel") }
Button(onClick = { holder.cancelLoad() })  { Text("Cancel Load") }
Button(onClick = { holder.request() })     { Text("Request") }
```

`holder.reload()` keeps the current successful native ad visible while the next request
is in flight; the UI changes only when the new request succeeds or fails.

**NativeAdDisplayState values:**

| State | Meaning |
|-------|---------|
| `Idle` | Not yet started |
| `Loading` | Request in progress |
| `Success(ad, adUnitId, fromPreload)` | Ad ready |
| `Error(error)` | Load failed |
| `Cancelled` | Manually cancelled |

#### Compose — share config with XML

```kotlin
// Use the same NativeAdSpec in both XML and Compose paths
val spec = NativeAdSpec.simple(
    tag       = "home",
    adUnitId  = BuildConfig.ad_native,
    layoutId  = R.layout.layout_native_ad,   // used by XML
    bufferSize = 2
)

// Compose — layoutId is ignored here
NativeAdWithPreload(
    config     = config,
    nativeView = { ad -> MyNativeAdContent(ad) }
)
```

#### Compose — custom native ad layout with `NativeAdView`

Use `NativeAdView` as the Compose wrapper and access ad fields via `CompositionLocal`:

```kotlin
NativeAdView(nativeAd = ad, modifier = Modifier.fillMaxWidth()) {
    // Fields read LocalApNativeAd automatically
    NativeHeadline(style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    NativeBody()
    NativeMediaView(modifier = Modifier.fillMaxWidth().height(200.dp))
    NativeCallToAction(
        shape  = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
    )
    NativeAdvertiser()
    NativeAdChoicesView()
}
```

Available composable field helpers (all read from `LocalApNativeAd`):

| Helper | Renders |
|--------|---------|
| `NativeHeadline()` | Headline text |
| `NativeBody()` | Body text |
| `NativeAdvertiser()` | Advertiser name |
| `NativeCallToAction()` | CTA button |
| `NativeMediaView()` | Media / video content |
| `NativeAdChoicesView()` | AdChoices icon (required) |

---

### Interstitial Ad

```kotlin
private val interstitialHelper by lazy {
    InterstitialAdHelper(
        this,
        this, // LifecycleOwner
        InterstitialAdConfig(
            idAds        = BuildConfig.ad_interstitial_splash,
            canShowAds   = true,
            canReloadAds = true
        ).apply {
            setIntervalBetweenAds(30) // seconds between shows
            setAutoReloadAfterShow(true)
        }
    )
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Pre-load
    interstitialHelper.requestAds(InterstitialAdParam.Request)
}

private fun onButtonClick() {
    interstitialHelper.showAd(
        activity  = this,
        onNextAction = { navigateToNextScreen() }
    )
}
```

**Observe state (optional):**

```kotlin
lifecycleScope.launch {
    interstitialHelper.adState.collect { state ->
        when (state) {
            is AdInterstitialState.Loaded   -> enableButton()
            is AdInterstitialState.Showing  -> disableButton()
            is AdInterstitialState.Dismissed -> navigateToNextScreen()
            else -> Unit
        }
    }
}
```

---

### Rewarded Ad

```kotlin
private var rewardAd: ApRewardAd? = null

private fun loadRewardAd() {
    RewardAdManager.loadRewardAdList(
        context = this,
        listId   = listOf(BuildConfig.ad_reward),
        adsCallback = object : AdsCallback() {
            override fun onRewardAdLoaded(apRewardAd: ApRewardAd?) {
                rewardAd = apRewardAd
            }
        }
    )
}

private fun showRewardAd() {
    val ad = rewardAd ?: return
    if (!ad.isReady()) return

    RewardAdManager.showRewardAd(
        activity = this,
        apRewardAd = ad,
        callback = object : AdsCallback() {
            override fun onUserEarnedReward(rewardItem: ApRewardItem) {
                // Grant reward to the user
            }
            override fun onRewardedAdClosed(earnedReward: Boolean) {
                // Inspect whether the SDK reward callback fired before close
            }
            override fun onNextAction() {
                // Continue flow whether or not reward was earned
            }
        }
    )
}
```

Grant rewards only from `onUserEarnedReward`. `onNextAction()` is a navigation/continue
callback and is not proof that the user earned a reward.

If the next action must run only after the user earns the reward and the ad has closed, gate
`onNextAction()` with a local flag:

```kotlin
var earnedReward = false
reward.waitLoadAndShow(
    activity = this,
    callback = object : AdsCallback() {
        override fun onUserEarnedReward(rewardItem: ApRewardItem) {
            earnedReward = true
        }

        override fun onNextAction() {
            if (earnedReward) openRewardedContent()
        }
    }
)
```

`loadRewardAdList()` only needs a `Context`; `Activity` is still required for `showRewardAd()`.
`ssvCustomData` is an optional server-side verification payload that your backend can use
to verify reward eligibility before crediting the user.
Common values for `ssvCustomData`: `userId`, `sessionId`, `orderId`, or `purchaseToken`.

Stateful rewarded helper:

```kotlin
private val reward by rewardedAd(adUnitId = BuildConfig.ad_reward)
reward.forceShow(object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) { /* grant */ }
    override fun onRewardedAdClosed(earnedReward: Boolean) { }
})
```

**Rewarded Interstitial** (no opt-in required from the user):

```kotlin
RewardAdManager.loadRewardInterstitialAd(
    context  = this,
    id       = BuildConfig.ad_reward_interstitial,
    callback = object : AdsCallback() { ... }
)
// Aliases with clearer naming are also available:
// loadRewardedInterstitialAd(...), showRewardedInterstitialAd(...)
```

---

### App Open Ad

**Resume Ad** — automatically shown when the app comes back from background:

```kotlin
// In Application.onCreate (after AdSdkInitializer.init)
AppOpenManager.getInstance().init(application, BuildConfig.ad_appopen_resume)

// Exclude specific activities from triggering resume ads
AppOpenManager.getInstance()
    .disableAppResumeWithActivity(SplashActivity::class.java)
```

**Splash Ad** — shown once during app launch:

```kotlin
private val appOpenHelper = AppOpenAdHelper(
    application,
    AppOpenAdConfig.create(
        adId    = BuildConfig.ad_appopen_resume,
        canShow = true
    ).apply {
        splashMinDelay  = 3_000L // wait at least 3 s before showing
        maxAdAgeHours   = 4
    }
)

// In SplashActivity
appOpenHelper.loadSplashAd(
    activity     = this,
    onAdReady    = { /* ad is ready, you can show it */ },
    onNextAction = { navigateToMain() },
    onFailed     = { navigateToMain() }
)

appOpenHelper.showSplashAd(
    activity     = this,
    onNextAction = { navigateToMain() },
    onClosed     = { /* ad was closed */ }
)
```

---

## In-App Purchases

Powered by Google Play Billing Library 8.x via `AppPurchase` singleton.

### 1. Define products

```kotlin
// PurchaseItem(itemId, type, trialId?)
val products = listOf(
    PurchaseItem("product_premium",      AppPurchase.TYPE_IAP.PURCHASE),     // one-time
    PurchaseItem("sub_monthly",          AppPurchase.TYPE_IAP.SUBSCRIPTION), // subscription
    PurchaseItem("sub_yearly_trial",     AppPurchase.TYPE_IAP.SUBSCRIPTION,  // subscription with trial
                 trialId = "free-trial-offer-id"),
)
```

`TYPE_IAP.PURCHASE` = one-time in-app purchase. `TYPE_IAP.SUBSCRIPTION` = recurring subscription.  
`trialId` must match the **Offer ID** of the free trial offer set up in Google Play Console.

### 2. Initialize (Application.onCreate)

```kotlin
AppPurchase.getInstance().initBilling(application, products)
```

The client connects to Google Play and queries product details automatically. In `DEBUG` / `appDev` builds, a test product (`android.test.purchased`) is added automatically.

### 3. Wait for billing to be ready

Use `setBillingListener` to know when the billing client has connected and purchase status has been verified:

```kotlin
// Without timeout — fires as soon as billing is ready
AppPurchase.getInstance().setBillingListener { resultCode ->
    if (resultCode == BillingClient.BillingResponseCode.OK) {
        // Billing is ready, purchase status known
    }
}

// With timeout (ms) — fires immediately if already ready, or after timeout if not
AppPurchase.getInstance().setBillingListener(billingListener = { resultCode ->
    // resultCode == ERROR means timed out before billing connected
    showPaywall()
}, timeout = 3000)
```

### 4. Listen for purchase results

```kotlin
AppPurchase.getInstance().setPurchaseListener(object : PurchaseListener {
    override fun onProductPurchased(productId: String?, transactionDetails: String) {
        // Grant access — called after purchase and acknowledgement
    }
    override fun displayErrorMessage(errorMsg: String) {
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
    }
    override fun onUserCancelBilling() {
        // User dismissed the purchase sheet
    }
})
```

### 5. Trigger a purchase

**One-time purchase:**

```kotlin
AppPurchase.getInstance().purchase(activity, "product_premium")
```

**Subscription:**

```kotlin
// New subscription
AppPurchase.getInstance().subscribe(activity, "sub_monthly")

// Upgrade / downgrade (proration)
val oldToken = AppPurchase.getInstance()
    .getOwnerIdSubs()
    .find { it.productId == "sub_monthly" }
    ?.purchaseToken

AppPurchase.getInstance().subscribe(
    activity         = activity,
    subsId           = "sub_yearly_trial",
    oldPurchaseToken = oldToken,
    replacementMode  = BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
)
```

### 6. Check purchase status

```kotlin
// Quick boolean — cached in memory, set after billing initializes
val isPro = AppPurchase.getInstance().isPurchased()

// List of owned one-time purchases with full purchase info
val ownedInApp: List<PurchaseResult> = AppPurchase.getInstance().getOwnerIdInApp()

// List of active subscriptions
val ownedSubs: List<PurchaseResult> = AppPurchase.getInstance().getOwnerIdSubs()

// ID of the last purchased product
val lastId = AppPurchase.getInstance().getIdPurchased()

// Check if billing client has connected
val ready = AppPurchase.getInstance().isAvailable()
```

### 7. Refresh purchase status manually

```kotlin
// Verify purchases from Play Store (called automatically on connect)
AppPurchase.getInstance().verifyPurchased(isCallback = true)

// Re-query and update isPurchased flag, then fire callback
AppPurchase.getInstance().setUpdatePurchaseListener {
    // Called when refresh is complete
}
AppPurchase.getInstance().updatePurchaseStatus()
```

### 8. Consumable products

For products that can be bought multiple times (e.g. coins, lives):

```kotlin
// Auto-consume every purchase as it happens
AppPurchase.getInstance().setConsumePurchase(true)

// Manually consume a specific product (re-enables purchasing it again)
AppPurchase.getInstance().consumePurchase("product_coins")
```

### 9. Get all products at once (recommended for paywall UI)

`getProductInfoList()` returns a flat, UI-ready list of every product fetched from Play Store. Call it after `BillingListener.onInitBillingFinished`.

```kotlin
AppPurchase.getInstance().setBillingListener { _ ->
    val products = AppPurchase.getInstance().getProductInfoList()

    val sub = products.firstOrNull { it.productId == "sub_monthly" }
    if (sub != null) {
        sub.hasTrial        // true
        sub.trialPeriod     // "P3D"
        sub.hasIntroPrice   // true
        sub.introPrice      // "131.000 ₫"  (first paid phase of the promo offer)
        sub.introCycles     // 2            (how many discounted months)
        sub.regularPrice    // "263.000 ₫"  (base plan price)
        sub.billingPeriod   // "P1M"
        sub.promoOfferId    // "promo-trial-5usd"
        sub.promoOfferToken // use when calling subscribe() with the promo
        sub.baseOfferToken  // use when calling subscribe() without promo
    }

    val inapp = products.firstOrNull { it.productId == "product_premium" }
    if (inapp != null) {
        inapp.price         // "263.000 ₫"
        inapp.priceMicros   // 263000000000
        inapp.currency      // "VND"
    }
}
```

**`BillingProductInfo` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `productId` | `String` | Product ID |
| `name` | `String` | Display name from Play Store |
| `type` | `Int` | `TYPE_IAP.PURCHASE` or `TYPE_IAP.SUBSCRIPTION` |
| `price` | `String` | Formatted price (INAPP only) |
| `priceMicros` | `Long` | Price in micros (INAPP only) |
| `currency` | `String` | ISO 4217 currency code |
| `regularPrice` | `String` | Regular recurring price (SUBS only) |
| `regularPriceMicros` | `Long` | Regular price in micros (SUBS only) |
| `billingPeriod` | `String` | Base plan period e.g. `"P1M"`, `"P1Y"` (SUBS only) |
| `introPrice` | `String` | Discounted introductory price, empty if no promo (SUBS only) |
| `introPriceMicros` | `Long` | Intro price in micros (SUBS only) |
| `introBillingPeriod` | `String` | Intro period e.g. `"P1M"` (SUBS only) |
| `introCycles` | `Int` | Number of discounted cycles e.g. `2` (SUBS only) |
| `trialPeriod` | `String` | Free trial period e.g. `"P3D"`, empty if none (SUBS only) |
| `promoOfferId` | `String?` | Offer ID of the promo offer, null if none (SUBS only) |
| `promoOfferToken` | `String` | Offer token — pass to `subscribe()` with promo (SUBS only) |
| `baseOfferToken` | `String` | Offer token — pass to `subscribe()` without promo (SUBS only) |
| `hasPromo` | `Boolean` | `promoOfferId != null` |
| `hasTrial` | `Boolean` | `trialPeriod.isNotEmpty()` |
| `hasIntroPrice` | `Boolean` | `introPriceMicros > 0` |

**Subscribe using the offer token from `BillingProductInfo`:**

```kotlin
val sub = AppPurchase.getInstance().getProductInfoList()
    .firstOrNull { it.productId == "sub_monthly" } ?: return

// With promo (trial + discount)
AppPurchase.getInstance().subscribeWithToken(activity, sub.productId, sub.promoOfferToken)

// Without promo (base plan only)
AppPurchase.getInstance().subscribeWithToken(activity, sub.productId, sub.baseOfferToken)
```

### 10. Read individual product details from Play Store

Use these when you only need a single value and already know the product ID.

> **Important:** always pass the **product ID** (e.g. `"sub_monthly"`), not the offer ID.

```kotlin
val billing = AppPurchase.getInstance()

// One-time purchase price ("263.000 ₫")
val price    = billing.getPrice("product_premium")

// Subscription regular price ("263.000 ₫")
val subPrice = billing.getPriceSub("sub_monthly")

// Introductory price — first PAID phase of the promo offer ("131.000 ₫")
// Optional second param targets a specific offer by its offer ID
val introPrice  = billing.getIntroductorySubPrice("sub_monthly")
val introPrice2 = billing.getIntroductorySubPrice("sub_monthly", "promo-trial-5usd")

// Billing period of the base plan ("P1M" = monthly, "P1Y" = yearly)
val period   = billing.getPeriod("sub_monthly")

// Free-trial period ("P3D" = 3 days), empty string if no trial
val trial    = billing.getTrialPeriod("sub_monthly")

// Product display name
val name     = billing.getName("product_premium", AppPurchase.TYPE_IAP.PURCHASE)

// Price in micros / 1_000_000 (e.g. 263000000000 → 263.0 for VND)
val micros   = billing.getPriceWithoutCurrency("product_premium", AppPurchase.TYPE_IAP.PURCHASE)

// ISO 4217 currency code ("VND", "USD", …)
val currency = billing.getCurrency("product_premium", AppPurchase.TYPE_IAP.PURCHASE)

// Formatted price with currency symbol
val formatted  = billing.getPriceWithCurrency("product_premium", AppPurchase.TYPE_IAP.PURCHASE)

// Formatted price after applying a sale factor (sale = 0.5 → half price)
val salePrice  = billing.getPriceWithCurrency("product_premium", AppPurchase.TYPE_IAP.PURCHASE, sale = 0.5)

// List of available product IDs confirmed by Play Store
val subIds   = billing.getSubscriptionProductIds()   // ["sub_monthly"]
val inAppIds = billing.getInAppProductIds()           // ["product_premium"]
val allIds   = billing.getAllProductIds()
```

### 11. Revenue tracking

```kotlin
// Enable automatic revenue tracking to Adjust / AppsFlyer on every purchase
AppPurchase.getInstance().setEnableTrackingRevenue(true)
```

When enabled, `LogEventManager.onTrackRevenuePurchase()` is called automatically after each successful purchase.

### PurchaseListener callback reference

| Callback | When fired |
|----------|-----------|
| `onProductPurchased(productId, transactionDetails)` | Purchase succeeded and acknowledged |
| `onUserCancelBilling()` | User dismissed the purchase sheet |
| `displayErrorMessage(msg)` | Any billing error (already owned, unavailable, network, etc.) |

Errors that trigger `displayErrorMessage`:

| Billing code | Message |
|--------------|---------|
| `ITEM_ALREADY_OWNED` | "You already own this item" (also re-queries ownership) |
| `ITEM_UNAVAILABLE` | "This item is not available" |
| `BILLING_UNAVAILABLE` | "Billing is not supported on this device" |
| `SERVICE_DISCONNECTED` / `SERVICE_UNAVAILABLE` | "Network error. Please try again" |
| `ERROR` | "Purchase failed. Please try again" |

### PurchaseItem reference

| Field | Type | Description |
|-------|------|-------------|
| `itemId` | `String` | Product ID from Google Play Console |
| `type` | `Int` | `TYPE_IAP.PURCHASE` or `TYPE_IAP.SUBSCRIPTION` |
| `trialId` | `String?` | Offer ID for the free trial offer (subscriptions only) |

---

## Consent Management (UMP / GDPR)

```kotlin
val consentManager = AdsConsentManager(activity)

// Request consent (suspend function — call inside a coroutine)
consentManager.requestUMP()

// Check if ads can be requested after consent
val canShow = consentManager.getCanRequestAd()

// Show the consent form again (e.g. from Settings)
lifecycleScope.launch {
    consentManager.loadAndShowConsentForm()
}
```

Enable debug mode during development to test consent flows on real devices:

```kotlin
AdsConsentConfig(enableUMP = true).apply {
    isEnableDebug = true
    testDevice    = "YOUR_HASHED_DEVICE_ID"  // from logcat
    isResetData   = true                      // always show form during testing
}
```

---

## Analytics

Analytics are configured via `AdSdkConfig` and fire automatically on ad events. To fire custom events:

```kotlin
// Log an ad impression value to Adjust
LogEventManager.logAdjustAdImpression(context, revenueUsd)

// Log a purchase event
LogEventManager.logPurchaseEvent(context, productId, priceUsd, currency)
```

---

## AdsCallback Reference

Override only the callbacks you need:

```kotlin
object : AdsCallback() {
    override fun onAdLoaded()                              { }
    override fun onAdFailedToLoad(error: ApAdError?)       { }
    override fun onAdFailedToShow(error: ApAdError?)       { }
    override fun onAdOpened()                              { }
    override fun onAdClosed()                              { }
    override fun onAdClicked()                             { }
    override fun onAdImpression()                          { }
    override fun onNextAction()                            { } // ad dismissed, proceed
    override fun onInterstitialLoad(ad: ApInterstitialAd?) { }
    override fun onRewardAdLoaded(ad: ApRewardAd?)         { }
    override fun onUserEarnedReward(item: ApRewardItem)    { }
    override fun onRewardedAdClosed(earnedReward: Boolean) { }
    override fun onNativeAdLoaded(ad: ApNativeAd)          { }
    override fun onBannerLoaded(view: AdManagerAdView?)    { }
    override fun onAdSplashReady()                         { }
}
```

---

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Install debug APK on connected device
./gradlew installDebug
```

---

## Build Variants

| Flavor | Purpose |
|--------|---------|
| `appDev` | AdMob test ad unit IDs, debug analytics |
| `appProd` | Production ad unit IDs |

---

## Key Dependencies

| Library | Version |
|---------|---------|
| Google Mobile Ads SDK | 25.2.0 |
| User Messaging Platform | 4.0.0 |
| Google Play Billing | 8.3.0 |
| Adjust SDK | 5.6.1 |
| AppsFlyer SDK | 6.17.5 |
| Facebook SDK | 18.2.3 |
| Arrow-kt | 2.2.1.1 |
| Jetpack Compose BOM | 2024.02.00 |
| Lottie | 6.7.1 |

---

## Architecture

- **Singleton Pattern** — Main managers (`AdSdkInitializer`, `AppPurchase`, `AppOpenManager`) provide app-wide access.
- **Wrapper Pattern** — `Ap*` prefix classes (`ApInterstitialAd`, `ApNativeAd`, `ApRewardAd`) abstract the Google Mobile Ads SDK.
- **Helper Pattern** — Kotlin lifecycle-aware helpers (`BannerAdHelper`, `NativeAdHelper`, `InterstitialAdHelper`, `AppOpenAdHelper`) extend `AdsHelper<C, P>` with coroutine support.
- **Configuration Pattern** — Centralized config objects (`AdSdkConfig`, `AdsConsentConfig`, `AdjustConfig`, `AppsflyerConfig`) control behavior.

---

## License

All rights reserved.
