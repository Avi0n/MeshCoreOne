# MeshCore JNI: Swift shim resolves callbacks by method name. Keep names/signatures.
-keep interface one.meshcore.shared.meshcore.jni.MeshBridgeNativeCallbacks { *; }
-keep class * implements one.meshcore.shared.meshcore.jni.MeshBridgeNativeCallbacks { *; }
# swift-java generated JNI classes: method names must match the static .so.
-keep class one.meshcore.bridge.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# ML Kit language-id / translate register through Firebase ComponentRegistrar.
# R8 dropping that registrar makes LanguageIdentification.getClient() NPE on a
# null Factory when a chat with messages seeds detection.
-keep class com.google.mlkit.nl.languageid.** { *; }
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_language_id** { *; }
-keep class com.google.android.gms.internal.mlkit_language_id_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
# Component discovery invokes public no-arg constructors through reflection.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
