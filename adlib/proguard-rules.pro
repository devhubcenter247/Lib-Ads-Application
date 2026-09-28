# Add project specific ProGuard rules here.

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Preserve entry-point names while still allowing R8 to optimize method bodies.
-keepnames class * extends android.app.Application
-keepnames class * extends android.app.Activity
-keepnames class * extends android.app.Service
-keepnames class * extends android.content.BroadcastReceiver
-keepnames class * extends android.content.ContentProvider

-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

-dontwarn com.facebook.infer.annotation.**
-dontwarn com.appsflyer.internal.AFj1ySDK$Companion
-dontwarn com.bytedance.sdk.openadsdk.core.model.NetExtParams$RenderType
-dontwarn com.bytedance.sdk.openadsdk.core.settings.TTSdkSettings$FETCH_REQUEST_SOURCE
-dontwarn org.jetbrains.annotations.**
