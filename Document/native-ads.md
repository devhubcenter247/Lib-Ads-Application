# GMA Native Ads

GMA native ads có một flow duy nhất: `NativeAdConfig` → `NativeAdController` → `NativeAdManager`.

## Config

```kotlin
val config = NativeAdConfig(
    listId = listOf(BuildConfig.ad_native),
    canShowAds = true,
    canReloadAds = false,
    layoutId = R.layout.layout_native_medium,
).apply {
    setListTimeout(listOf(10_000L))
    configureRequest {
        enableSwipeGesture = false
    }
}
```

`listId` được thử lần lượt. `listTimeout` là timeout cho từng ad unit.

## Request thủ công trong Activity

Controller không tự request. Màn hình tự quyết định thời điểm gọi `request()`:

```kotlin
private val nativeAd by lazy {
    NativeAdController(
        context = this@SplashActivity,
        scope = lifecycleScope,
        config = config,
        lifecycleOwner = this@SplashActivity,
    )
}

// Sau khi UMP và placement checks hoàn tất:
nativeAd.request()

nativeAd.reload()
nativeAd.cancel()
```

State dùng chung:

```kotlin
nativeAd.state.collect { state ->
    when (state) {
        NativeAdState.Idle -> Unit
        NativeAdState.Loading -> Unit
        is NativeAdState.Loaded -> show(state.ad)
        is NativeAdState.Error -> continueWithoutAd()
        NativeAdState.Cancelled -> hideAd()
    }
}
```

Controller tự destroy ad khi `reload`, `cancel`, hoặc `ON_DESTROY`.

## Compose

```kotlin
val controller = rememberNativeAdController(config)

NativeAdCard(
    controller = controller,
    loading = { ShimmerNative() },
    nativeView = { ad ->
        NativeMediumCtaTop(nativeAdView = ad)
    },
)
```

Compose cũng không tự request. Gọi `controller.request()` từ flow của màn hình.

## Native banner

Native banner giữ config/view riêng vì đây là component layout khác, nhưng sử dụng
`NativeAdController` và `NativeAdManager` bên trong, không có loader riêng.

## Kiến trúc

| Thành phần | Trách nhiệm |
|---|---|
| `NativeAdConfig` | Placement, waterfall, timeout và request options |
| `NativeAdController` | Request thủ công, lifecycle và `NativeAdState` |
| `NativeAdManager` | Gọi GMA SDK, timeout waterfall và bind `NativeAdView` |
| `NativeAdCard` / `NativeAdView` | UI Compose |

## Preload theo tag (`NativeAds` + `createNativeAdHolder`)

Khi cần preload ở màn trước và tiêu thụ ở màn sau, dùng lớp placement theo tag:

```kotlin
// Màn trước
NativeAds.preload(NativeAdSpec(tag = TAG_HOME_NATIVE, adUnitIds = listOf(BuildConfig.ad_native)))

// Màn hiển thị — không cần Activity/Context
private val nativeHolder by createNativeAdHolder(
    tag = TAG_HOME_NATIVE,
    options = NativeAdPreloadHolderOptions(
        fallbackAdUnitIds = listOf(BuildConfig.ad_native),
        lifecycleOwner = this,
        autoRequestOnStart = true,
    ),
)
```

Cách render state trong XML/Compose: xem
[gma-lib.md, mục 5.3](gma-lib.md#53-xml--holder).
