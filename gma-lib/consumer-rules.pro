# Consumer rules for the final application R8 pass.
#
# Do not keep the whole SDK package here. A blanket keep rule prevents shrinking,
# optimization and obfuscation for every class in gma-lib when an app consumes it.
# The SDKs used by this library already publish their own consumer rules.

# Preserve Kotlin generic/signature and runtime annotation metadata where it is
# referenced by library consumers. This does not keep class names or members.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Honor explicit AndroidX @Keep usage without disabling R8 for the rest of the SDK.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
