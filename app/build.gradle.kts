import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun readQuotedConstant(file: java.io.File, name: String, fallback: String): String {
    if (!file.isFile) {
        return fallback
    }
    val pattern = Regex("""$name(?:\[\])?\s*=\s*"([^"]+)"""")
    return pattern.find(file.readText())?.groupValues?.get(1) ?: fallback
}

fun readTrimmedFile(file: java.io.File, fallback: String): String {
    if (!file.isFile) {
        return fallback
    }
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
        // Tun2Socks remains the default release path. Native-direct stays
        // user-selectable as a beta transport so it can be tested without
        // silently changing existing users away from the stable backend.
        buildConfigField("boolean", "ENABLE_NATIVE_DIRECT_BETA", "true")
        buildConfigField(
            "String",
            "YUME_ROUTING_MODE",
            "\"Systemwide Android VpnService routing through the active Yume tunnel backend; the upstream control socket is protected to avoid loops.\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            java.setSrcDirs(listOf(basefwxJavaOutputDir.get().asFile))
            resources.setSrcDirs(listOf(basefwxJavaResourcesOutputDir.get().asFile))
        }
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
