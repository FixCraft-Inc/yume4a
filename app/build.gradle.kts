import java.time.Instant

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
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "YUME_PROTOCOL_VERSION", "\"$yumeProtocolVersion\"")
        buildConfigField("String", "BASEFWX_ENGINE_VERSION", "\"$basefwxEngineVersion\"")
        buildConfigField("String", "YUME_PROTECTION_POLICY", "\"Auto (highest available)\"")
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
