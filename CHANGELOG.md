# Changelog — `com.lib:gmasdk`

## 1.3.0

Stability release. Update from 1.2.4 is source-compatible; read **Behaviour changes** and
**Migration** before shipping.

### Fixed

- **Interstitial (`InterstitialAdHelper`)**
  - `onNextAction` fired twice (before `show()` and again on dismiss / async show failure), so
    apps navigated twice. It now fires once, before `show()`, like `InterstitialAdManager`.
  - When the screen was finished in `onNextAction`, the full-screen flag stayed set (app-open
    resume blocked for the session) and the impression's revenue was never logged.
- **Splash interstitial** reaches the listener with exactly one terminal callback
  (`onReady` / `onNextAction` / `onFailed`); a failed waterfall also fired `onNextAction` when the
  timeout elapsed.
- **IAP**: a consumable whose pending payment completed while the app was running (or a promo
  code) was consumed without reaching the app. It now goes to `setRecoveredConsumableListener`.
- **Rewarded helpers**: a screen destroyed while the ad was showing dropped the reward for
  listeners registered with `registerAdListener`.
- **Premium users** no longer preload rewarded, rewarded-interstitial or app-open ads; becoming
  premium stops the SDK preloaders and releases buffered full-screen ads.
- Ads requested before `MobileAds.initialize` (consent-first apps) now wait for the SDK
  (`InterstitialAdHelper`, `AppOpenAdHelper`, `BannerAds`).
- Billing no longer stays `Disconnected` after its reconnect attempts run out; it retries when the
  app returns to the foreground.
- No `!!` left in the library (possible NPEs on public mutable fields).

### Behaviour changes

| Area | Before | Now |
|------|--------|-----|
| Native / banner preload TTL (`NativeAdSpec.ttlMs`, `BannerAdSpec.ttlMs`) | 4 h | 55 min |
| Interstitial / rewarded ad held by a helper or `FullScreenAdLruCache` | kept until shown | dropped after 55 min, then reloaded |
| `InterstitialAdHelper.waitLoadAndShow` / `forceShow` blocking wait | `loadTimeout` (30 s) | at most 8 s; a late fill stays cached |
| Unshown interstitial when its screen is destroyed | dropped | returned to the shared `InterstitialAdPreload` store |
| Purchases | queried at start and after a purchase | also on return to foreground (≥ 60 s apart) |
| `ConsentManager.requestUMP()` | only updates consent | also calls `initializeIfNeeded()` when ads may be requested |
| Low memory in background (`onTrimMemory ≥ TRIM_MEMORY_BACKGROUND`) | — | buffered native / banner ads are destroyed |
| Secondary processes | ads, attribution, Firebase initialized in every process | main process only |
| Logcat in release | revenue values and app-open lifecycle logged | through `AppLogger` (off unless enabled) |

### Added

- `AdSdkConfig.interstitialShowDelayMs` (default 800 ms, unchanged behaviour).
- `ApAdBase.loadedAtMs`.

### Migration

- Listen to `InterstitialAdListener.onDismissed` (not a second `onNextAction`) for work that
  must run after the interstitial closes.
- Apps selling consumables: set `AppPurchase.setRecoveredConsumableListener` before
  `initBilling` and grant the item there.
- Apps that need the old TTL pass `ttlMs` explicitly to `NativeAdSpec` / `BannerAdSpec`.
