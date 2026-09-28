# Native Banner Ads

A "native banner" is a compact native ad rendered into a small, banner-sized layout.
Two backends are supported, each with its own inflater:

| Backend | Loader | Inflater |
|---|---|---|
| AdMob native | [Native Ads](native-ads.md) (`NativeAdController` / `NativeAdManager`) | `AdmobNativeBannerManager.inflate(...)` |
| Meta Audience Network | `NativeBannerAd` (Facebook SDK) | `NativeBannerAdManager.inflateAd(...)` |

AdMob native-banner **loads count under `AdType.NATIVE`** in
[`AdLoadStats`](README.md#load-counting--logging--adloadstats) (it uses the native loader).

---

## AdMob native banner

### GMA Next-Gen module (`gma-lib`)

`gma-lib` has its own `NativeBannerHelper` running on the native holder/tag buffer; see
[gma-lib.md, section 5.5](gma-lib.md#55-native-banner-xml). The rest of this section is `adlib`.

The layout root must be a `NativeAdView` containing: `ad_app_icon`, `ad_headline`,
`ad_body` (optional), `ad_call_to_action` (optional).

For AdMob native-banner, ids are tried in order by `NativeAdManager`; timeout callbacks
cannot replace a later winning ad.

---

## Meta native banner

The Meta native-banner Compose holder currently loads a single placement id. Use a
separate fallback strategy in app code if you need a Meta placement waterfall.

```kotlin
val nativeBannerAd = NativeBannerAd(context, META_PLACEMENT_ID)
nativeBannerAd.loadAd(
    nativeBannerAd.buildLoadAdConfig()
        .withAdListener(object : NativeAdListener {
            override fun onAdLoaded(ad: Ad?) {
                NativeBannerAdManager.inflateAd(
                    context = this@MyActivity,
                    nativeBannerAd = nativeBannerAd,
                    container = binding.nativeBannerContainer,   // NativeAdLayout
                    layoutId = R.layout.layout_native_banner_default,
                )
            }
            override fun onError(ad: Ad?, error: AdError?) {}
            /* … */
        })
        .build()
)
```

The layout root must be a `LinearLayout` with: `ad_choices_container`,
`native_ad_sponsored_label`, `native_ad_title`, `native_ad_social_context`,
`native_icon_view`, `native_ad_call_to_action`.
