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

val sharedYumeRoot = rootProject.file("../../yume")
val yumeProtocolVersion = readQuotedConstant(
    sharedYumeRoot.resolve("src/core/version.hpp"),
    "kVersion",
    "1.0",
)
val basefwxEngineVersion = readQuotedConstant(
    sharedYumeRoot.resolve("basefwx/cpp/include/basefwx/constants.hpp"),
    "kEngineVersion",
    "3.6.4",
)

val basefwxJavaOutputDir = layout.buildDirectory.dir("generated/basefwx-java")
val syncBasefwxJava by tasks.registering(Sync::class) {
    from(rootProject.file("../../yume/basefwx/java/src/main/java")) {
        include("com/fixcraft/basefwx/Base64Codec.java")
        include("com/fixcraft/basefwx/Codec.java")
        include("com/fixcraft/basefwx/Crypto.java")
        include("com/fixcraft/basefwx/Constants.java")
        include("com/fixcraft/basefwx/Format.java")
    }
    into(basefwxJavaOutputDir)
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
            "\"Systemwide Android VpnService routing through tun2socks into the local Yume SOCKS5 gateway; the upstream control socket is protected to avoid loops.\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/hev-socks5-tunnel/Android.mk")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(basefwxJavaOutputDir.get().asFile)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncBasefwxJava)
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
