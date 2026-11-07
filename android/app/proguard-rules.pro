# Keep OkHttp + Okio internals
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# AndroidX Security / EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# CameraX & ZXing
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# WorkManager & Kotlin Coroutines
-keep class androidx.work.** { *; }
-keep class kotlinx.coroutines.** { *; }

# LinkPhone models and process classes
-keep class dev.linkphone.app.** { *; }
