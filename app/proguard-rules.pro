# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.autotask.config.** { *; }
-keep class com.autotask.model.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**

# Keep data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
