# Preserve Gson models because application data is serialized locally through reflection.
-keep class com.masahhisabat.app.data.** { *; }
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OpenCV exposes Java classes and native methods that must retain their names.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep generic signatures and runtime annotations used by Gson and Android libraries.
-keepattributes Signature,*Annotation*
