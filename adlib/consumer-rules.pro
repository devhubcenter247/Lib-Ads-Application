-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep names for classes that Android loads from app/library manifests.
-keepnames class * extends android.app.Application
-keepnames class * extends android.app.Activity
-keepnames class * extends android.app.Service
-keepnames class * extends android.content.BroadcastReceiver
-keepnames class * extends android.content.ContentProvider

# Respect androidx.annotation.Keep in apps that consume this ads module.
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Optional SDK integrations can reference classes that are absent in some consumers.
-dontwarn com.facebook.infer.annotation.**
-dontwarn com.appsflyer.internal.AFj1ySDK$Companion
-dontwarn com.bytedance.sdk.openadsdk.core.model.NetExtParams$RenderType
-dontwarn com.bytedance.sdk.openadsdk.core.settings.TTSdkSettings$FETCH_REQUEST_SOURCE
-dontwarn org.jetbrains.annotations.**
