# Used only by builds with -PyumeMlKitTranslation=true, which add Google ML Kit.

# ML Kit / Firebase use ComponentRegistrar discovered through MlKitInitProvider
# at startup. R8 sees no direct call site for the no-arg ctor and strips it,
# which yields NoSuchMethodException on app launch. Keep every Registrar's
# default constructor (and the registrar classes themselves).
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.common.internal.** { *; }
-keep class com.google.mlkit.common.sdkinternal.** { *; }
-keep class com.google.mlkit.nl.translate.internal.** { *; }
-keep class com.google.mlkit.nl.translate.** { *; }

# Play Services Tasks framework: ML Kit walks Task callbacks reflectively.
-keep class com.google.android.gms.tasks.** { *; }

-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.firebase.**
