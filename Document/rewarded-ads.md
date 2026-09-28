# Rewarded & Rewarded-Interstitial Ads

Two product types are available:
- `RewardAdManager` for low-level rewarded and rewarded-interstitial loading/showing.
- `RewardedAdHelper` and `RewardedInterstitialAdHelper` for lifecycle-aware stateful flows.

Loads are gated by `AdsManger` and counted in
[`AdLoadStats`](README.md#load-counting--logging--adloadstats) under `AdType.REWARDED`.

---

## Rewarded (manager)

```kotlin
// 1) load
RewardAdManager.loadRewardAd(
    context, BuildConfig.ad_rewarded,
    object : AdsCallback() {
        override fun onRewardAdLoaded(rewardAd: ApRewardAd?) { pendingAd = rewardAd }
        override fun onAdFailedToLoad(adError: ApAdError?) {}
    },
    ssvCustomData = null,    // optional server-side verification payload
)

// 2) show
RewardAdManager.showRewardAd(activity, pendingAd!!, object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) { grantReward() }
    override fun onNextAction() { /* continue whether or not reward earned */ }
    override fun onAdFailedToShow(adError: ApAdError?) {}
})

// waterfall: RewardAdManager.loadRewardAdList(context, listOf(idHigh, idLow), callback)
```

Always handle `onNextAction()` to continue your flow; grant the reward only in
`onUserEarnedReward`. `onRewardedAdClosed(earnedReward)` tells you whether the ad was
closed after the SDK emitted the earned-reward callback.
`ssvCustomData` is forwarded to AdMob server-side verification, which is useful when
your backend must verify the reward before crediting the user.
Common values: `userId`, `sessionId`, `orderId`, or `purchaseToken`.
`loadRewardAdList()` only needs a `Context`; keep the `Activity` for `showRewardAd()`.

### Rewarded helper (stateful)

```kotlin
private val reward by rewardedAd(
    adUnitId = BuildConfig.ad_rewarded,
    autoReloadAfterShow = true,
)

reward.forceShow(object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) { grantReward() }
    override fun onRewardedAdClosed(earnedReward: Boolean) { /* inspect close result */ }
    override fun onNextAction() { /* continue */ }
})

reward.adState // StateFlow<RewardedAdState>
```

Waterfall variant:

```kotlin
private val reward by rewardedAdWaterfall(adUnitIds = listOf(idHigh, idLow))
```

`forceShow(callback)` uses the callback as a one-shot listener; it is removed after the
show attempt reaches a terminal path so repeated button taps do not multiply reward
callbacks.

---

## Rewarded Interstitial

### One-liner (recommended)

Builds the config, creates the helper, and auto-loads on first access:

```kotlin
private val rewardInter by rewardedInterstitialAd(
    adUnitId = BuildConfig.ad_rewarded_inter,
    intervalBetweenAds = 30,          // optional
)

// show when ready (loads-then-shows if still loading; falls through if unavailable)
rewardInter.forceShow(object : AdsCallback() {
    override fun onUserEarnedReward(rewardItem: ApRewardItem) { grantReward() }
    override fun onRewardedAdClosed(earnedReward: Boolean) { /* true only if earned */ }
    override fun onNextAction() { /* continue */ }
})

rewardInter.isAdLoaded()
```

Variants & options:

```kotlin
// waterfall
private val rewardInter by rewardedInterstitialAdWaterfall(adUnitIds = listOf(idHigh, idLow))

// from a Fragment
private val rewardInter by rewardedInterstitialAd(
    activity = requireActivity(),
    adUnitId = BuildConfig.ad_rewarded_inter,
)
```
Optional params: `autoLoad`, `autoReloadAfterShow`, `loadTimeoutMs`, `ssvCustomData`,
`canShowAds`, `canReloadAds`, `adCallback`.

### Manager (low-level)
```kotlin
RewardAdManager.loadRewardInterstitialAd(context, BuildConfig.ad_rewarded_inter, callback)
RewardAdManager.loadRewardedInterstitialAd(context, BuildConfig.ad_rewarded_inter, callback)
RewardAdManager.showRewardedInterstitialAd(activity, pendingAd, callback)
```

### Config factory (if you build the helper yourself)
```kotlin
val config = RewardedInterstitialAdConfig.simple(
    BuildConfig.ad_rewarded_inter, intervalBetweenAds = 30,
)
RewardedInterstitialAdHelper(this, this, config)
```
(The fluent form `RewardedInterstitialAdConfig(id, true).setListId(…).setIntervalBetweenAds(…)`
still works.)
