# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LibAdsApplication is an Android ad management library/application that provides unified integration for AdMob ads, in-app purchases, and analytics (Adjust, AppsFlyer, Firebase). The codebase uses a mix of Java (legacy) and Kotlin (modern helpers).

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Install debug APK on device
./gradlew installDebug
```

## Architecture

### Core Patterns

1. **Singleton Pattern**: Main managers (`AzAds.getInstance()`, `AppPurchase.getInstance()`) use singletons for app-wide access.

2. **Wrapper Pattern**: Ad models are wrapped with `Ap*` prefix classes (`ApInterstitialAd`, `ApNativeAd`, `ApRewardAd`) in `ads/ads/wrapper/` to abstract the Google Mobile Ads SDK.

3. **Helper Pattern (Kotlin)**: Modern ad helpers extend `AdsHelper<C, P>` base class with lifecycle-aware coroutine support. Located in `ads/helper/`.

4. **Configuration Pattern**: Centralized config objects (`AzAdConfig`, `AdsConsentConfig`, `AdjustConfig`, `AppsflyerConfig`) manage settings.

5. **Waterfall Pattern**: Ad unit lists are loaded sequentially. The first loaded id wins;
native/banner ignore late callbacks from timed-out ids, and banner waterfall has a
10-second default timeout per id.

6. **Reward Safety**: Rewards must be granted only from `onUserEarnedReward`.
`onNextAction` is for continuing UI flow, and `onRewardedAdClosed(earnedReward)` reports
whether the SDK reward callback fired before close.

### Key Modules

| Directory | Purpose |
|-----------|---------|
| `ads/admob/` | AdMob integration, UMP consent management |
| `ads/ads/` | Ad type implementations (banner, native, interstitial, rewarded) |
| `ads/helper/` | Kotlin-based lifecycle-aware ad helpers with coroutines |
| `ads/billing/` | In-app purchase integration via Google Play Billing |
| `ads/event/` | Analytics: Adjust, AppsFlyer, Facebook, Firebase |
| `ads/config/` | Ad configuration and constants |
| `app/` | Sample application with activities demonstrating usage |

### Initialization Flow

Application startup in `MyApplication.java`:
1. Configure `AzAdConfig` (environment, test devices, consent, analytics)
2. Initialize AdMob via `AzAds.getInstance().init()`
3. Set up UMP consent management
4. Configure analytics (Adjust, AppsFlyer)

### State Management

- Uses `StateFlow`/`MutableStateFlow` for reactive state in Kotlin helpers
- Arrow-kt `Either<L, R>` for error handling
- Coroutines with lifecycle-aware scopes

## Build Configuration

- **SDK Versions**: compileSdk 36, minSdk 24, targetSdk 36
- **JVM Target**: 11
- **Gradle**: 8.13
- **Kotlin**: 2.1.0
- **Dependencies**: Managed via Version Catalog (`libs.*`)

## Key Entry Points

- `MyApplication.java` - Application initialization
- `AzAds.java` - Main ad management singleton
- `AdsHelper.kt` - Base class for all Kotlin ad helpers
- `Admob.java` - Core AdMob integration
- `AppPurchase.java` - In-app purchase handling
- `AdsConsentManager2.kt` - UMP GDPR consent
