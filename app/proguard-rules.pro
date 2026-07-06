# Add project specific ProGuard rules here.

# Keep Camera2 result keys
-keep class android.hardware.camera2.** { *; }

# Keep DngCreator
-keep class android.media.** { *; }

# OpenCV
-keep class org.opencv.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
