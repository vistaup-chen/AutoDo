# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.autotask.config.** { *; }
-keep class com.autotask.model.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn okio.**

# Gson 反射使用的泛型/内部类
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
