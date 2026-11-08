import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

plugins {
    id("com.android.library")
}

group = "com.fixcraft.yume"
version = file("VERSION").readText().trim()

val providerVersion = version.toString()
require(providers.gradleProperty("yumeProviderVersion").orNull == providerVersion) {
    "Explicit shared provider version binding required"
}
val expectedProviderSource = providers.gradleProperty("yumeProviderSourceSha256").orNull
require(expectedProviderSource != null && Regex("[0-9a-f]{64}").matches(expectedProviderSource)) {
    "Explicit shared provider source binding required"
}

abstract class GenerateProviderProvenance : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Input abstract val componentVersion: Property<String>
    @get:Input abstract val expectedSource: Property<String>
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val root = sourceRoot.get().asFile.toPath()
        val files = sourceFiles.files.associate { file ->
            val path = file.toPath()
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Provider input must be a regular file" }
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
            val bits = mapOf(PosixFilePermission.OWNER_READ to 256, PosixFilePermission.OWNER_WRITE to 128,
                PosixFilePermission.OWNER_EXECUTE to 64, PosixFilePermission.GROUP_READ to 32,
                PosixFilePermission.GROUP_WRITE to 16, PosixFilePermission.GROUP_EXECUTE to 8,
                PosixFilePermission.OTHERS_READ to 4, PosixFilePermission.OTHERS_WRITE to 2,
                PosixFilePermission.OTHERS_EXECUTE to 1)
            root.relativize(path).toString().replace(File.separatorChar, '/') to
                mapOf("mode" to permissions.sumOf { bits.getValue(it) }, "sha256" to digest(file.readBytes()), "size" to file.length())
        }.toSortedMap()
        val canonical = files.entries.joinToString("") { (path, entry) ->
            "$path\u0000${entry.getValue("mode")}\u0000${entry.getValue("sha256")}\n"
        }
        val actual = digest(canonical.toByteArray(Charsets.UTF_8))
        check(actual == expectedSource.get()) { "Shared provider source binding differs" }
        val output = outputDirectory.get().asFile.resolve("provider-library/provenance.json")
        check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create provider provenance output" }
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("schema" to 1,
            "component" to "com.fixcraft.yume:provider-library", "version" to componentVersion.get(),
            "source_sha256" to actual, "files" to files))) + "\n")
    }
}

val generateProviderProvenance = tasks.register<GenerateProviderProvenance>("generateProviderProvenance") {
    sourceRoot.set(layout.projectDirectory)
    sourceFiles.from(files("build.gradle.kts", "VERSION", "README.md"), fileTree("src"))
    componentVersion.set(providerVersion)
    expectedSource.set(expectedProviderSource!!)
    outputDirectory.set(layout.buildDirectory.dir("generated/provider-provenance"))
}

android {
    namespace = "com.fixcraft.yume.providerlibrary"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    testFixtures { enable = true }
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateProviderProvenance, GenerateProviderProvenance::outputDirectory)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("net.openid:appauth:0.11.1")
    // The version AppAuth already resolves. Declared for direct Custom Tabs use.
    implementation("androidx.browser:browser:1.3.0")
    testFixturesImplementation("net.openid:appauth:0.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
