# R8/ProGuard keep rules for PhoneTTS. Shrinking is currently disabled (see app/build.gradle.kts),
# but these are the rules that MUST be present before enabling it, or the app breaks at runtime.

# ServiceLoader discovers engines by concrete class name from META-INF/services — R8 must not
# strip or rename the EngineProvider implementations, or every engine silently disappears.
-keep class * implements com.phonetts.core.engine.EngineProvider { *; }
-keepnames class * implements com.phonetts.core.engine.EngineProvider

# kotlinx.serialization: keep generated serializers + the @Serializable model classes.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.phonetts.**$$serializer { *; }
-keepclassmembers class com.phonetts.** {
    *** Companion;
}
-keepclasseswithmembers class com.phonetts.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime + PDFBox use reflection/JNI internally; keep their entry points conservatively.
-keep class ai.onnxruntime.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
