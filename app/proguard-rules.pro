# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.nebulaai.app.**$$serializer { *; }
-keepclassmembers class com.nebulaai.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.nebulaai.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
