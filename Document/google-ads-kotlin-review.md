# Google Mobile Ads Kotlin review

This review follows the official Google Mobile Ads Android guides and Kotlin examples.

## Recommended app flow

1. Configure `AdSdkConfig` in `Application`.
2. On every app launch, call `AdsConsentManager.requestUMP(...)` from the splash `Activity`.
3. Let UMP decide whether ads can be requested through `ConsentInformation.canRequestAds()`.
4. Initialize Mobile Ads once. The SDK guards duplicate initialization.
5. Preload full-screen ads before a natural transition, then continue navigation only from
   `onAdDismissedFullScreenContent` or `onAdFailedToShowFullScreenContent`.
6. Request a banner once. Configure refresh in AdMob instead of requesting again on every resume.
7. A Compose `rememberNativeAdPreload(...)` holder starts its initial request automatically and
   destroys its native ad when it leaves composition.
8. Load App Open ads ahead of foreground time, keep `isLoading`/`isShowing` guards, and reject ads
   older than four hours.

## Important behavior

- `IABTCF_PurposeConsents` is retained only for analytics. It is not used to authorize ad requests.
- UMP reset only runs when debug mode is enabled.
- `disableAppResumeWithActivity(...)` excludes one screen. Avoid global `disableAppResume()` for a
  screen unless another owner always calls `enableAppResume()`.
- The deprecated `openActivityAfterShowInterAds` option is retained for compatibility. When true,
  navigation starts from `onAdShowedFullScreenContent`; it never starts before `show()` succeeds.
- Banner and native helpers ignore duplicate in-flight requests.

## Official references

- [Google Kotlin examples](https://github.com/googleads/googleads-mobile-android-examples/tree/main/kotlin)
- [UMP SDK](https://developers.google.com/admob/android/privacy)
- [Interstitial](https://developers.google.com/admob/android/interstitial)
- [Rewarded](https://developers.google.com/admob/android/rewarded)
- [App Open](https://developers.google.com/admob/android/app-open)
- [Banner](https://developers.google.com/admob/android/banner)
- [Native](https://developers.google.com/admob/android/native/advanced)
