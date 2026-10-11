# GuideLens ProGuard/R8 rules — ready for when minification is enabled.
# TFLite interpreter + support library
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class * { @org.tensorflow.lite.* *; }
-dontwarn org.tensorflow.lite.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Kotlin metadata / coroutines
-keep class kotlin.Metadata { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-dontwarn kotlinx.coroutines.**
