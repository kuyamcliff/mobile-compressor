# Native engine: JNI registers methods by name in JNI_OnLoad (RegisterNatives),
# and calls NativeJobListener.onEvent / constructs NativeEngineException.
-keep class com.kuyamcliff.compressor.engine.NativeEngine { *; }
-keep interface com.kuyamcliff.compressor.engine.NativeJobListener { *; }
-keep class * implements com.kuyamcliff.compressor.engine.NativeJobListener { void onEvent(java.lang.String, java.lang.String); }
-keep class com.kuyamcliff.compressor.engine.NativeEngineException { <init>(java.lang.String); }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kuyamcliff.compressor.**$$serializer { *; }
-keepclassmembers class com.kuyamcliff.compressor.** { *** Companion; }
-keepclasseswithmembers class com.kuyamcliff.compressor.** { kotlinx.serialization.KSerializer serializer(...); }

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
