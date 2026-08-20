# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.autosignin.config.** { *; }
-keep class com.autosignin.model.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**

# Keep data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
