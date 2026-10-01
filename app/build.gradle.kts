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
// Provenance of the shared YUME checkout this APK's native library is built
// from. Resolved from the checkout's git refs rather than hand-maintained:
// the previous hard-coded marker had drifted to a hash that exists in no YUME
// repository at all, so it identified nothing.
//
// This is a provenance label, not the cache key. The native task already
// hashes the actual source fileTree, which is what makes the build correct
// when the working tree is dirty.
fun resolveSharedYumeHead(): String {
    val gitDir = sharedYumeRoot.resolve(".git")
    if (!gitDir.isDirectory) return "unknown"
    val head = gitDir.resolve("HEAD").takeIf { it.isFile }?.readText()?.trim()
        ?: return "unknown"
    if (!head.startsWith("ref:")) return head  // detached HEAD
    val ref = head.removePrefix("ref:").trim()
    gitDir.resolve(ref).takeIf { it.isFile }?.let { return it.readText().trim() }
    // Packed-refs fallback. A plain loop, not forEachLine: that overload is
    // not inline, so a non-local return out of its lambda does not compile.
    val packed = gitDir.resolve("packed-refs").takeIf { it.isFile } ?: return "unknown"
    for (line in packed.readLines()) {
        val parts = line.trim().split(" ")
        if (parts.size == 2 && parts[1] == ref) return parts[0]
    }
    return "unknown"
}

val sharedYumeHead = resolveSharedYumeHead()
val basefwxEngineVersion = readTrimmedFile(sharedYumeRoot.resolve("basefwx/VERSION"), "Unknown")
val basefwxBuildUtc = System.getenv("BASEFWX_BUILD_UTC")
    ?: System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let(Instant::ofEpochSecond)?.toString()
    ?: "unknown"
val basefwxBuildOrigin = System.getenv("BASEFWX_BUILD_ORIGIN") ?: "android-app"
val basefwxGpgFingerprint = System.getenv("BASEFWX_GPG_FINGERPRINT") ?: ""

val basefwxJavaOutputDir = layout.buildDirectory.dir("generated/basefwx-java")
val basefwxJavaResourcesOutputDir = layout.buildDirectory.dir("generated/basefwx-java-resources")
val pinnedNdkVersion = "28.2.13676358"
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
        include("com/fixcraft/basefwx/RuntimeLog.java")
        include("com/fixcraft/basefwx/runtime/NativeLibraryLoader.java")
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
        // Direct references of files already copied above: FileCodecObfuscation,
        // B512FileCodec, LiveCipher, Pb512FileCodec, LengthPrefixedCodec,
        // KeyWrap and FwxAesCodec all name these two. Omitting them was a hard
        // javac break, not a missing optional feature.
        include("com/fixcraft/basefwx/PasswordPolicy.java")
        include("com/fixcraft/basefwx/PayloadKeySeparation.java")
        // Not transitively reached by the classes above, but part of the
        // BaseFWX Java parity surface this app is expected to mirror.
        include("com/fixcraft/basefwx/X25519.java")
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
    ndkVersion = pinnedNdkVersion

    defaultConfig {
        applicationId = "com.fixcraft.yume"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        // Track the shared transport identity exactly; dev6 refuses dev1.
        versionName = yumeProtocolVersion
        buildConfigField("String", "YUME_PROTOCOL_VERSION", "\"$yumeProtocolVersion\"")
        buildConfigField("String", "BASEFWX_ENGINE_VERSION", "\"$basefwxEngineVersion\"")
        // dev6 posture is fixed by the protocol. The shared packet ABI is
        // mandatory: there is no Kotlin-wire or tun2socks fallback when
        // packet_bulk_v1 is unavailable, so neither is a build option.
        buildConfigField("String", "YUME_PROTECTION_POLICY", "\"Exact YUME $yumeProtocolVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_DIRECT_BATTERY_OPTIMIZATION_REQUEST", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_DIRECT_BATTERY_OPTIMIZATION_REQUEST", "false")
        }
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
            path = file("src/main/jni/shared-yume/Android.mk")
        }
    }

    buildTypes {
        release {
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

// Build the public YUME C ABI directly from the signed sibling checkout. The
// app receives libyume.so and a narrow JNI ownership adapter; no protocol or
// crypto sources are synchronized into this project.
val yumeSharedAbiOutputDir = layout.buildDirectory.dir("yume-shared-abi")
val yumeSharedAbiBuildScript = sharedYumeRoot.resolve("tools/build_android_shared_abi.sh")

val configuredAbis: List<String> = (project.findProperty("yumeAbi") as? String)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val configuredMinSdk: String = (android.defaultConfig.minSdk ?: 28).toString()

fun resolveNdkRoot(): String? {
    // A reproducible native build must use the pinned NDK revision. Do not
    // select the lexicographically-largest installed directory: that can pick
    // 9.x over 28.x and silently change compiler/runtime behavior.
    val envCandidate: String? = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
    if (envCandidate != null) {
        val envDir: File = File(envCandidate)
        val revision = envDir.resolve("source.properties").takeIf { it.isFile }
            ?.readLines()?.firstOrNull { it.startsWith("Pkg.Revision") }
            ?.substringAfter('=')?.trim()
        if (envDir.isDirectory && revision == pinnedNdkVersion) return envCandidate
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
        val revision = ndkDirFile.resolve("source.properties").takeIf { it.isFile }
            ?.readLines()?.firstOrNull { it.startsWith("Pkg.Revision") }
            ?.substringAfter('=')?.trim()
        if (ndkDirFile.isDirectory && revision == pinnedNdkVersion) return ndkDir
    }

    val sdkDir: String? = props.getProperty("sdk.dir")
    if (sdkDir.isNullOrBlank()) return null
    val pinnedNdk = File(sdkDir).resolve("ndk").resolve(pinnedNdkVersion)
    return pinnedNdk.takeIf { it.isDirectory }?.absolutePath
}

val buildYumeSharedAbi by tasks.registering {
    description = "Cross-compiles the signed shared YUME client and packet ABI for Android."
    group = "yume native"
    inputs.file(yumeSharedAbiBuildScript)
    inputs.file(sharedYumeRoot.resolve("include/yume/yume.h"))
    inputs.file(sharedYumeRoot.resolve("vcpkg.json"))
    inputs.files(fileTree(sharedYumeRoot.resolve("src")) {
        include("**/*.cpp", "**/*.hpp", "**/*.h", "**/*.map", "**/CMakeLists.txt")
    })
    inputs.files(fileTree(sharedYumeRoot.resolve("basefwx/cpp")) {
        include("**/*.cpp", "**/*.hpp", "**/*.h", "**/CMakeLists.txt", "vcpkg.json")
    })
    inputs.files(fileTree(sharedYumeRoot.resolve("cmake")) { include("**/*.cmake", "**/*.in") })
    inputs.file(sharedYumeRoot.resolve("CMakeLists.txt"))
    inputs.property("abis", configuredAbis.joinToString(","))
    inputs.property("minSdk", configuredMinSdk)
    inputs.property("ndkVersion", pinnedNdkVersion)
    inputs.property("yumeCommit", sharedYumeHead)
    outputs.dir(yumeSharedAbiOutputDir)
    doLast {
        val ndkPath = resolveNdkRoot()
            ?: error(
                "libyume.so requires the Android NDK. Install via Android Studio " +
                "(SDK Manager → SDK Tools → NDK) or set ANDROID_NDK_HOME."
            )
        val vcpkgRoot = System.getenv("VCPKG_ROOT")
            ?.let(::File)
            ?.takeIf { it.resolve("scripts/buildsystems/vcpkg.cmake").isFile }
            ?: rootProject.file("../../vcpkg")
        require(vcpkgRoot.resolve("scripts/buildsystems/vcpkg.cmake").isFile) {
            "Set VCPKG_ROOT to a vcpkg checkout; required for Android libyume dependencies."
        }
        val command = mutableListOf(
            "bash",
            yumeSharedAbiBuildScript.absolutePath,
            "--ndk", ndkPath,
            "--vcpkg", vcpkgRoot.absolutePath,
            "--api", configuredMinSdk,
            "--out", yumeSharedAbiOutputDir.get().asFile.absolutePath,
        )
        for (abi in configuredAbis) {
            command += listOf("--abi", abi)
        }
        val proc = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
        val rc = proc.waitFor()
        if (rc != 0) {
            val latestBuildLog = configuredAbis
                .map { abi ->
                    yumeSharedAbiOutputDir.get().asFile
                        .resolve(".build")
                        .resolve(abi)
                        .resolve("yume-abi-build.log")
                }
                .filter { it.isFile }
                .maxByOrNull { it.lastModified() }
            val diagnostic = latestBuildLog?.let { log ->
                val tail = log.useLines { lines -> lines.toList().takeLast(120) }
                    .joinToString(System.lineSeparator())
                "\nNative build log: ${log.absolutePath}\n$tail"
            }.orEmpty()
            error("Shared YUME Android ABI build failed with exit code $rc$diagnostic")
        }
    }
}

// Hook into the non-clean externalNativeBuild lifecycle ONLY. AGP creates
// per-variant per-ABI tasks named like "externalNativeBuildDebug",
// "configureNdkBuildDebug[Arm64-v8a]" etc.; we attach to all of them
// so each ABI's Android.mk sees the pre-built OpenSSL libs ready.
// externalNativeBuildClean* is intentionally excluded: making clean depend on
// the shared ABI would compile it immediately before deleting its outputs.
//
// Deliberately NOT hooked into preBuild — unit tests (compileDebugKotlin
// and friends) reach preBuild, and forcing OpenSSL compilation on every
// local `./gradlew :app:testDebugUnitTest` invocation would brick the
// laptop. The native build is only triggered by ./gradlew assemble*
// or by Android Studio's run/debug flow.
afterEvaluate {
    tasks.matching {
        !it.name.contains("Clean", ignoreCase = true) &&
            (it.name.startsWith("externalNativeBuild") ||
                it.name.startsWith("configureNdkBuild") ||
                it.name.startsWith("generateJsonModelNdkBuild"))
    }.configureEach {
        dependsOn(buildYumeSharedAbi)
    }
}

// AGP's ndk-build clean tasks parse Android.mk before deleting anything.
// Imported libyume modules then fail if a previously configured, unselected
// ABI no longer has its staged .so. All native intermediates are generated
// beneath app/build or app/.cxx, so the Gradle Delete task can clean them
// directly without invoking ndk-build against missing prebuilt inputs.
tasks.matching { it.name.startsWith("externalNativeBuildClean") }.configureEach {
    enabled = false
}
tasks.named("clean").configure {
    doLast {
        project.delete(layout.projectDirectory.dir(".cxx"))
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
