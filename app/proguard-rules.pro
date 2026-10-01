# Keep JNI entry points referenced from NativeTerminal.kt.
-keepclasseswithmembernames class ai.opencode.term.native.NativeTerminal {
    native <methods>;
}

# kotlinx.serialization: keep generated serializers.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class ai.opencode.term.**$$serializer { *; }
-keepclassmembers class ai.opencode.term.** {
    *** Companion;
}
-keepclasseswithmembers class ai.opencode.term.** {
    kotlinx.serialization.KSerializer serializer(...);
}
