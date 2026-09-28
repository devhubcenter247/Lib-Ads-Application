# Unity Ads mediation

The library includes the Unity Ads SDK and the Google Mobile Ads Unity adapter:

```kotlin
api("com.unity3d.ads:unity-ads:4.19.0")
api("com.google.ads.mediation:unity:4.19.0.0")
```

These versions are compatible with Google Mobile Ads SDK `25.4.0`. The library min SDK is 26,
which satisfies Unity adapter's API 23 minimum.

## Required dashboard setup

Code integration alone does not enable Unity demand. For each Android app:

1. Create the project and bidding placements in the Unity Ads dashboard.
2. In AdMob, open the mediation group and add **Unity Ads (Bidding)** as an ad source.
3. Map the Unity **Game ID** and **Placement ID** to the correct AdMob ad unit.
4. Add Unity Ads to the GDPR and US-state-regulations ad partners lists in AdMob.
5. Add Unity's authorized seller entries to the app's `app-ads.txt`.

Use bidding for new integrations. Unity waterfall placements can no longer be created or edited
after January 31, 2026.

## Privacy

Pass choices collected by the app's CMP before initializing/requesting ads. Do not guess a value:

```kotlin
AdsManager.setUnityPrivacy(
    context = applicationContext,
    gdprConsent = consentFromCmp,
    privacyConsent = privacyConsentFromCmp,
)
```

`gdprConsent` maps to Unity metadata `gdpr.consent`; `privacyConsent` maps to
`privacy.consent`. A null value is not written.

## Loading context

Unity requires an `Activity` context to load Banner, Interstitial, Rewarded, and Native ads. Create
helpers from an Activity or use the `AdsManager` facade. Avoid creating full-screen helpers from
`applicationContext` when Unity is enabled. Native preload keeps only a weak reference to this
Activity and releases it with the placement buffer.

## Testing

1. Enable test mode or register the device in the Unity Ads dashboard.
2. Register the same test device with Google Mobile Ads.
3. Open Ad Inspector and enable single-ad-source testing for **Unity Ads (Bidding)**.
4. Disable Unity and AdMob test mode before release.

Useful Unity adapter errors:

- `101`: missing or invalid Game ID / Placement ID mapping.
- `102`: Unity returned no fill.
- `105`: the load context was not an Activity.
- `108`: more than one load was started for the same Unity placement.
