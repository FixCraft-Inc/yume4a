# Stack traces from production crashes need to be deobfuscatable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI: anything reachable from native code must keep its name and signature.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# yume_android_jni.cpp registers YumeNative's entries and calls these two
# interfaces by name and signature.
-keep class com.fixcraft.yume.core.YumeNative { *; }
-keep interface com.fixcraft.yume.core.SocketProtector { *; }
-keep interface com.fixcraft.yume.core.KitSink { *; }

# Vault/JSON storage uses class names to round-trip enums and sealed values.
-keepclassmembers enum * { *; }

# BouncyCastle reflective lookups by algorithm name. R8 cannot trace strings
# like "Ed25519"/"Argon2"/"Kyber"; without keep rules the provider crashes at
# runtime trying to load the engines.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider { *; }
-keep class org.bouncycastle.pqc.crypto.crystals.kyber.** { *; }
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters { *; }
-keep class org.bouncycastle.crypto.util.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**
-dontnote org.bouncycastle.**

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

# Kotlin coroutines internals reachable from suspending lambdas.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# Compose runtime / serialization helpers can have synthetic accessors that
# are only entered via inlined call sites; keep all of them by default.
-dontwarn androidx.compose.**
