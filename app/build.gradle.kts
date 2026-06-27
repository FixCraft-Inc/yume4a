import java.io.File
import java.lang.ProcessBuilder
import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun readQuotedConstant(file: java.io.File, name: String, fallback: String): String {
    if (!file.isFile) return fallback
    val pattern = Regex("""$name(?:\[\])?\s*=\s*"([^"]+)"""")
    return pattern.find(file.readText())?.groupValues?.get(1) ?: fallback
}

fun readTrimmedFile(file: java.io.File, fallback: String): String {
    if (!file.isFile) return fallback
    return file.readText().trim().ifEmpty { fallback }
}

val sharedYumeRoot = rootProject.file("../../yume")
val yumeProtocolVersion = readQuotedConstant(
    sharedYumeRoot.resolve("src/core/version.hpp"),
    "kVersion",
    "Unknown",
)
val basefwxEngineVersion = readTrimmedFile(sharedYumeRoot.resolve("basefwx/VERSION"), "Unknown")
val basefwxBuildUtc = System.getenv("BASEFWX_BUILD_UTC") ?: Instant.now().toString()
val basefwxBuildOrigin = System.getenv("BASEFWX_BUILD_ORIGIN") ?: "android-app"
val basefwxGpgFingerprint = System.getenv("BASEFWX_GPG_FINGERPRINT") ?: ""

val basefwxJavaOutputDir = layout.buildDirectory.dir("generated/basefwx-java")
val basefwxJavaResourcesOutputDir = layout.buildDirectory.dir("generated/basefwx-java-resources")
val syncBasefwxJava by tasks.registering(Sync::class) {
    from(rootProject.file("../../yume/basefwx/java/src/main/java")) {
        include("com/fixcraft/basefwx/Base64Codec.java")
        include("com/fixcraft/basefwx/Codec.java")
        include("com/fixcraft/basefwx/Crypto.java")
        include("com/fixcraft/basefwx/Constants.java")
        include("com/fixcraft/basefwx/Format.java")
        include("com/fixcraft/basefwx/VersionInfo.java")
        // basefwx 3.6.4 introduced an optional native AES-GCM dispatch in
        // Crypto.aesGcm{Encrypt,Decrypt}WithIvInto that references the
        // CryptoBackend abstraction. Pull those helper classes in so the
        // app compiles. At runtime, NativeCryptoBackend.tryCreate() returns
        // null because libbasefwxcrypto.so isn't shipped with the APK, so
        // the JCA path runs exactly like it did pre-3.6.4 — no behavior
        // change on Android.
        include("com/fixcraft/basefwx/CryptoBackend.java")
        include("com/fixcraft/basefwx/CryptoBackends.java")
        include("com/fixcraft/basefwx/JavaCryptoBackend.java")
        include("com/fixcraft/basefwx/NativeCryptoBackend.java")
        include("com/fixcraft/basefwx/NativeLibraryLoader.java")
        include("com/fixcraft/basefwx/RuntimeLog.java")
        // 3.7.x sync expansion: BaseFwx.java became syncable after the
        // image-carrier API was split out into BaseFwxImage.java (which
        // pulls in java.awt — desktop-only). With BaseFwx now AWT-clean,
        // we can stop duplicating its crypto logic inside the Android
        // BaseFwxCompat.kt shadow class and route directly to the synced
        // classes. The transitively-reachable dependencies of BaseFwx are
        // included below.
        //
        // What stays desktop-only and is NOT synced:
        //   BaseFwxImage.java       (java.awt for PNG carrier codec)
        //   MediaCipher.java        (java.awt for jmg image cipher)
        //   FwxAESBenchmark.java    (CLI bench tool)
        //   cli/* (CLI entry points; java.awt + filesystem deps)
        //
        // FwxAESJNI.java is synced for COMPILE only: FwxAES.java
        // references `new FwxAESJNI(...)` from two branches gated on
        // `native_ != null`. The native_ backend comes from
        // NativeCryptoBackend.tryCreate(), which returns null on
        // Android (libbasefwxcrypto.so is not in the APK), so the
        // FwxAESJNI class is never loaded at runtime — but javac still
        // needs the symbol to resolve the new-expression.
        include("com/fixcraft/basefwx/BaseFwx.java")
        include("com/fixcraft/basefwx/FwxAES.java")
        include("com/fixcraft/basefwx/FwxAESJNI.java")
        include("com/fixcraft/basefwx/FwxAESPureJava.java")
        include("com/fixcraft/basefwx/KeyWrap.java")
        include("com/fixcraft/basefwx/PQ.java")
        include("com/fixcraft/basefwx/EcKeys.java")
        include("com/fixcraft/basefwx/LiveCipher.java")
        include("com/fixcraft/basefwx/UnsupportedKdfException.java")
        // 3.7.x de-monolith: BaseFwx delegates to these AWT-free helpers.
        // javac needs them at compile time even though they are not public API.
        include("com/fixcraft/basefwx/BaseFwxUtil.java")
        include("com/fixcraft/basefwx/FwxAesCodec.java")
        include("com/fixcraft/basefwx/An7Codec.java")
        include("com/fixcraft/basefwx/TextCodecs.java")
        include("com/fixcraft/basefwx/FileCodecs.java")
        include("com/fixcraft/basefwx/FileCodecKdf.java")
        include("com/fixcraft/basefwx/FileCodecObfuscation.java")
        include("com/fixcraft/basefwx/FileCodecMetadata.java")
        include("com/fixcraft/basefwx/FileCodecIo.java")
        include("com/fixcraft/basefwx/B512FileCodec.java")
        include("com/fixcraft/basefwx/Pb512FileCodec.java")
        include("com/fixcraft/basefwx/LengthPrefixedCodec.java")
        // BaseFWX 3.7.0 fwxAES imports the Java plugin SPI even when
        // no plugin is registered. These are AWT-free API/registry
        // classes and must be synced with FwxAesCodec.java.
        include("com/fixcraft/basefwx/plugin/BasefwxPlugin.java")
        include("com/fixcraft/basefwx/plugin/BasefwxPluginException.java")
        include("com/fixcraft/basefwx/plugin/BasefwxPluginFactory.java")
        include("com/fixcraft/basefwx/plugin/BasefwxPluginRegistry.java")
    }
    into(basefwxJavaOutputDir)
}
val syncBasefwxJavaResources by tasks.registering(Sync::class) {
    from(rootProject.file("../../yume/basefwx/java/src/main/resources")) {
        include("basefwx-build.properties")
        expand(
            "basefwxVersion" to basefwxEngineVersion,
            "basefwxBuildUtc" to basefwxBuildUtc,
            "basefwxBuildOrigin" to basefwxBuildOrigin,
            "basefwxGpgFingerprint" to basefwxGpgFingerprint,
        )
    }
    into(basefwxJavaResourcesOutputDir)
}

android {
    namespace = "com.fixcraft.yume"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.fixcraft.yume"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "YUME_PROTOCOL_VERSION", "\"$yumeProtocolVersion\"")
        buildConfigField("String", "BASEFWX_ENGINE_VERSION", "\"$basefwxEngineVersion\"")
        buildConfigField("String", "YUME_PROTECTION_POLICY", "\"Auto (highest available)\"")
        // Packet-native is gated by server packet_bulk_v1 support and falls
        // back to tun2socks stable when unavailable. Set true here so any
        // future buildType added without an explicit override inherits the
        // same behavior as debug/release rather than silently disabling it.
        buildConfigField("boolean", "ENABLE_NATIVE_DIRECT_BETA", "true")
        buildConfigField(
            "String",
            "YUME_ROUTING_MODE",
            "\"Systemwide Android VpnService routing through the active Yume tunnel backend; the upstream control socket is protected to avoid loops.\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val requestedAbis = (project.findProperty("yumeAbi") as? String)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    splits {
        abi {
            isEnable = true
            reset()
            include(*requestedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/yume-route-core/Android.mk")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_NATIVE_DIRECT_BETA", "true")
        }
        release {
            // Despite the legacy flag name, this enables the packet-native
            // fast path. Runtime code falls back to tun2socks stable if the
            // authenticated server does not negotiate packet_bulk_v1.
            buildConfigField("boolean", "ENABLE_NATIVE_DIRECT_BETA", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "META-INF/maven/**",
                "META-INF/androidx/**/LICENSE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "DebugProbesKt.bin",
                "kotlin/**.kotlin_metadata",
                "kotlin-tooling-metadata.json",
                "**/*.kotlin_builtins",
                "**/*.proto",
                // BouncyCastle PQC algorithms we never instantiate. The classes
                // themselves get stripped by R8; these blobs need an explicit
                // exclude because they live under org/bouncycastle/ as resources.
                "org/bouncycastle/pqc/crypto/picnic/lowmcL*.bin.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages_de.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages_de.properties",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.directories.apply {
                clear()
                add(basefwxJavaOutputDir.get().asFile.absolutePath)
            }
            resources.directories.apply {
                clear()
                add(basefwxJavaResourcesOutputDir.get().asFile.absolutePath)
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

tasks.named("preBuild") {
    dependsOn(syncBasefwxJava, syncBasefwxJavaResources)
}

// libyumetransport.so (the non-blocking TLS pipe) statically links a
// pre-built OpenSSL 3.0.x. Building OpenSSL inside ndk-build is awkward
// (Configure scripts, perl, autoconf), so we build it outside via
// app/src/main/cpp/build-openssl.sh, ONCE per ABI, cached under
// app/build/openssl/<abi>/. The Android.mk file imports the resulting
// libssl.a + libcrypto.a as PREBUILT_STATIC_LIBRARY targets.
//
// This task is wired into the externalNativeBuild lifecycle below so
// AGP runs it before ndk-build. First build per ABI takes ~3-5 min on
// remote-builder; subsequent builds skip (idempotent).
val openSslOutputDir = layout.buildDirectory.dir("openssl")
val openSslBuildScript = rootProject.file("app/src/main/cpp/build-openssl.sh")

val configuredAbis: List<String> = (project.findProperty("yumeAbi") as? String)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val configuredMinSdk: String = (android.defaultConfig.minSdk ?: 28).toString()

fun resolveNdkRoot(): String? {
    // Env vars first (CI, CLI invocations). Then local.properties'
    // ndk.dir, then sdk.dir + ndk/<largest version>. We deliberately
    // do NOT depend on AGP's android.ndkDirectory because that
    // property's API has churned across AGP versions and isn't
    // available in Kotlin DSL strict mode on this project's AGP.
    val envCandidate: String? = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
    if (envCandidate != null) {
        val envDir: File = File(envCandidate)
        if (envDir.isDirectory) return envCandidate
    }

    val localProps: File = rootProject.file("local.properties")
    if (!localProps.isFile) return null
    val props = Properties()
    val stream = localProps.inputStream()
    try {
        props.load(stream)
    } finally {
        stream.close()
    }

    val ndkDir: String? = props.getProperty("ndk.dir")
    if (!ndkDir.isNullOrBlank()) {
        val ndkDirFile: File = File(ndkDir)
        if (ndkDirFile.isDirectory) return ndkDir
    }

    val sdkDir: String? = props.getProperty("sdk.dir")
    if (sdkDir.isNullOrBlank()) return null
    val ndkRoot: File = File(sdkDir).resolve("ndk")
    if (!ndkRoot.isDirectory) return null
    val candidates: Array<File>? = ndkRoot.listFiles()
    val newest: File? = candidates
        ?.filter { entry: File -> entry.isDirectory }
        ?.maxByOrNull { entry: File -> entry.name }
    return newest?.absolutePath
}

val buildOpenSslPerAbi by tasks.registering {
    description = "Builds OpenSSL 3.0.x static libs for each Android ABI used by libyumetransport.so."
    group = "yume native"
    inputs.file(openSslBuildScript)
    outputs.dir(openSslOutputDir)
    doLast {
        val ndkPath = resolveNdkRoot()
            ?: error(
                "libyumetransport.so requires the Android NDK. Install via Android Studio " +
                "(SDK Manager → SDK Tools → NDK) or set ANDROID_NDK_HOME."
            )
        val outRoot = openSslOutputDir.get().asFile.absolutePath
        for (abi in configuredAbis) {
            logger.lifecycle("Building OpenSSL for $abi (cached under $outRoot/$abi if already present)...")
            val proc = ProcessBuilder(
                "bash",
                openSslBuildScript.absolutePath,
                abi,
                ndkPath,
                configuredMinSdk,
                outRoot,
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start()
            val rc = proc.waitFor()
            if (rc != 0) {
                error("OpenSSL build for $abi failed with exit code $rc")
            }
        }
    }
}

// Hook into the externalNativeBuild lifecycle ONLY. AGP creates
// per-variant per-ABI tasks named like "externalNativeBuildDebug",
// "configureNdkBuildDebug[Arm64-v8a]" etc.; we attach to all of them
// so each ABI's Android.mk sees the pre-built OpenSSL libs ready.
//
// Deliberately NOT hooked into preBuild — unit tests (compileDebugKotlin
// and friends) reach preBuild, and forcing OpenSSL compilation on every
// local `./gradlew :app:testDebugUnitTest` invocation would brick the
// laptop. The native build is only triggered by ./gradlew assemble*
// or by Android Studio's run/debug flow.
afterEvaluate {
    tasks.matching {
        it.name.startsWith("externalNativeBuild") ||
            it.name.startsWith("configureNdkBuild") ||
            it.name.startsWith("generateJsonModelNdkBuild")
    }.configureEach {
        dependsOn(buildOpenSslPerAbi)
    }
}

tasks.matching { it.name == "mergeDexRelease" || it.name == "mergeDexDebug" }.configureEach {
    doFirst {
        val variant = if (name.endsWith("Release")) "release" else "debug"
        layout.buildDirectory
            .dir("intermediates/d8_metadata/$variant/$name")
            .get()
            .asFile
            .mkdirs()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.google.material)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.conscrypt.android)
    implementation(libs.google.mlkit.translate)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
