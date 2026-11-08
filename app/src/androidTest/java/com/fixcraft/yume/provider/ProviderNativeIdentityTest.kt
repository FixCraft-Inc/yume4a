package com.fixcraft.yume.provider

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.core.YumeException
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.core.YumeNative
import com.fixcraft.yume.core.YumeStatusCode
import com.fixcraft.yume.data.KitBundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Requires the explicit private, ephemeral native-fixture handoff described in the client guide. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresNativeFixtures

/** Real sealed kits and JNI validation; identity results are never stubbed. */
@RequiresNativeFixtures
@RunWith(AndroidJUnit4::class)
class ProviderNativeIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoot = File(context.noBackupFilesDir, "provider-native-fixtures")
    private val names = listOf("alpha", "beta", "manual")

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val directory = Files.createTempDirectory(base.noBackupFilesDir.toPath(), "native-identity-test-").toFile()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }

    private class Fixture(val name: String, val metadata: JSONObject, val sealed: ByteArray, val code: CharArray) : AutoCloseable {
        val server = ProviderCodec.Server("edge", "Fixture edge", metadata.getString("host"), metadata.getInt("port"), metadata.getString("server_key_sha256"))
        val identity: String = metadata.getString("client_identity")
        val grant = ProviderCodec.Grant(
            ProviderCodec.GrantRequest("https://$name.example", "native-fixture-device", "native-fixture-request", "access", "edge", "vpn", null),
            "native-fixture-grant", 1, 1, 1, Long.MAX_VALUE, identity, metadata.getString("kit_sha256"), metadata.getInt("kit_size"),
        )
        fun opened(): List<KitFile> {
            val input = ByteArray(code.size) { code[it].code.toByte() }
            return try { YumeLibrary.openKit(sealed, input) } finally { input.fill(0) }
        }
        override fun close() { sealed.fill(0); code.fill('\u0000') }
    }

    private fun fixture(name: String): Fixture {
        val metadata = JSONObject(File(fixtureRoot, "$name.json").readText())
        return Fixture(name, metadata, File(fixtureRoot, "$name.kit").readBytes(), File(fixtureRoot, "$name.code").readText().trim().toCharArray())
    }

    private fun config(files: List<KitFile>): ByteArray = files.single { it.path == KitBundle.CONFIG_PATH }.bytes
    private fun wipe(files: List<KitFile>) = files.forEach { it.bytes.fill(0) }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun isolated(action: (IsolatedContext) -> Unit) {
        val isolated = IsolatedContext(context)
        val sentinel = File(isolated.directory, "manual-and-other-provider").apply { writeText("preserved") }
        try {
            action(isolated)
            for (descriptor in File("/proc/self/fd").listFiles()!!) {
                val target = try { Os.readlink(descriptor.absolutePath) } catch (_: ErrnoException) { continue }
                assertFalse("validation retained an owned file descriptor", target.startsWith(isolated.directory.absolutePath + "/"))
            }
            assertEquals("preserved", sentinel.readText())
            assertEquals(listOf(sentinel.name), isolated.directory.listFiles()!!.map { it.name }.sorted())
        } finally {
            assertTrue(isolated.directory.deleteRecursively())
        }
    }

    @Test fun bothOperatorsAndManualKitUseTheActualPrivateKeyIdentity() = isolated { app ->
        val identities = names.map { name ->
            fixture(name).use { fixture ->
                assertEquals(fixture.grant.kitSize, fixture.sealed.size)
                assertEquals(fixture.grant.kitSha256, digest(fixture.sealed))
                YumeProviderNative.validator(app).validate(fixture.sealed, fixture.code, fixture.grant, fixture.server).use { validated ->
                    assertTrue(fixture.code.all { it == '\u0000' })
                    val files = KitBundle.decode(validated.bundle)
                    try {
                        val expected = fixture.metadata.getJSONObject("files")
                        assertEquals(expected.length(), files.size)
                        for (file in files) assertEquals(expected.getString(file.path), digest(file.bytes))
                        assertEquals(fixture.identity, ProviderNativeIdentityVerifier(app).fingerprint(files, config(files)))
                    } finally { wipe(files) }
                }
                fixture.identity
            }
        }
        assertEquals(3, identities.toSet().size)
    }

    @Test fun missingMalformedAndZeroCredentialsNeverReturnAValidatedBundle() = isolated { app ->
        fixture("alpha").use { fixture ->
            val original = fixture.opened()
            try {
                val refs = JSONObject(String(config(original), Charsets.UTF_8)).getJSONObject("credentials")
                for (key in refs.keys()) {
                    val path = refs.getJSONObject(key).getString("file")
                    assertThrows(ProviderCodec.Refusal::class.java) {
                        YumeProviderNative.validator(app).validateOpened(original.filter { it.path != path }, fixture.identity, fixture.server)
                    }
                    val changed = original.map { if (it.path == path) KitFile(path, false, "malformed".toByteArray()) else it }
                    try {
                        assertThrows(ProviderCodec.Refusal::class.java) {
                            YumeProviderNative.validator(app).validateOpened(changed, fixture.identity, fixture.server)
                        }
                    } finally { changed.single { it.path == path }.bytes.fill(0) }
                }
                for (key in listOf("access_psk", "admission_key")) {
                    val path = refs.getJSONObject(key).getString("file")
                    val changed = original.map { if (it.path == path) KitFile(path, false, ByteArray(32)) else it }
                    assertThrows(ProviderCodec.Refusal::class.java) {
                        YumeProviderNative.validator(app).validateOpened(changed, fixture.identity, fixture.server)
                    }
                }
            } finally { wipe(original) }
        }
    }

    @Test fun anotherPrivateKeyCannotClaimTheOriginalGrantOrServer() = isolated { app ->
        fixture("alpha").use { alpha -> fixture("beta").use { beta ->
            val first = alpha.opened()
            val second = beta.opened()
            try {
                fun reference(files: List<KitFile>, name: String): String = JSONObject(String(config(files), Charsets.UTF_8))
                    .getJSONObject("credentials").getJSONObject(name).getString("file")
                val local = reference(first, "composite_key")
                val substituted = first.map { if (it.path == local) KitFile(local, false, second.single { f -> f.path == reference(second, "composite_key") }.bytes) else it }
                // The accompanying public file stays alpha's. Native derives beta
                // from its private key, then the provider grant equality refuses it.
                assertEquals(beta.identity, ProviderNativeIdentityVerifier(app).fingerprint(substituted, config(substituted)))
                assertEquals("binding", assertThrows(ProviderCodec.Refusal::class.java) {
                    YumeProviderNative.validator(app).validateOpened(substituted, alpha.identity, alpha.server)
                }.code)
                assertEquals("binding", assertThrows(ProviderCodec.Refusal::class.java) {
                    YumeProviderNative.validator(app).validateOpened(first, alpha.identity, beta.server)
                }.code)
            } finally { wipe(first); wipe(second) }
        } }
    }

    @Test fun unsafeReferencesAndExecutableCredentialsFailBeforeMaterialization() = isolated { app ->
        fixture("alpha").use { fixture ->
            val original = fixture.opened()
            try {
                for (escape in listOf("/data/local/tmp/key", "../key", "credentials/../../key", "credentials\\key")) {
                    val document = JSONObject(String(config(original), Charsets.UTF_8))
                    document.getJSONObject("credentials").getJSONObject("composite_key").put("file", escape)
                    val bytes = document.toString().toByteArray()
                    val changed = original.map { if (it.path == KitBundle.CONFIG_PATH) KitFile(it.path, false, bytes) else it }
                    try {
                        assertThrows(Exception::class.java) { ProviderNativeIdentityVerifier(app).fingerprint(changed, bytes) }
                        assertThrows(ProviderCodec.Refusal::class.java) { YumeProviderNative.validator(app).validateOpened(changed, fixture.identity, fixture.server) }
                    } finally { bytes.fill(0) }
                }
                val key = JSONObject(String(config(original), Charsets.UTF_8)).getJSONObject("credentials").getJSONObject("composite_key").getString("file")
                val executable = original.map { KitFile(it.path, it.path == key || it.executable, it.bytes) }
                assertThrows(IllegalArgumentException::class.java) { ProviderNativeIdentityVerifier(app).fingerprint(executable, config(executable)) }
            } finally { wipe(original) }
        }
    }

    @Test fun nativeRefusalsClearOutputAndEnforceProtectedFiles() = isolated { app ->
        fixture("alpha").use { fixture ->
            val files = fixture.opened()
            val directory = File(app.directory, "direct").apply { check(mkdir()); Os.chmod(absolutePath, 448) }
            try {
                for (file in files.filter { !it.executable && it.path != KitBundle.CONFIG_PATH }) {
                    val path = File(directory, file.path)
                    path.parentFile!!.mkdirs()
                    Os.chmod(path.parentFile!!.absolutePath, 448)
                    path.writeBytes(file.bytes)
                    Os.chmod(path.absolutePath, 384)
                }
                assertEquals(fixture.identity, YumeLibrary.validateClientCredentials(directory, config(files)))
                val refs = JSONObject(String(config(files), Charsets.UTF_8)).getJSONObject("credentials")
                val key = File(directory, refs.getJSONObject("composite_key").getString("file"))
                val original = key.readBytes()
                val fingerprint = ByteArray(32) { 0x55 }
                val result = arrayOfNulls<String>(YumeNative.RESULT_SIZE)
                Os.chmod(key.absolutePath, 420)
                assertTrue(YumeNative.nativeValidateClientCredentials(directory.absolutePath, config(files), fingerprint, result) != YumeStatusCode.OK)
                assertArrayEquals(ByteArray(32), fingerprint)
                Os.chmod(key.absolutePath, 384)
                val moved = File(directory, "outside-key").apply { writeBytes(original); Os.chmod(absolutePath, 384) }
                assertTrue(key.delete())
                Files.createSymbolicLink(key.toPath(), moved.toPath())
                assertThrows(YumeException::class.java) { YumeLibrary.validateClientCredentials(directory, config(files)) }
                Files.delete(key.toPath())
                assertThrows(YumeException::class.java) { YumeLibrary.validateClientCredentials(directory, config(files)) }
                original.fill(0)
            } finally {
                wipe(files)
                assertTrue(directory.deleteRecursively())
            }
        }
    }

    @Test fun cancellationAndFileFailureRemoveOnlyOwnedPlaintext() = isolated { app ->
        fixture("alpha").use { fixture ->
            val original = fixture.opened()
            try {
                var wrote = false
                val failed = ProviderNativeIdentityVerifier(app) { phase ->
                    if (phase == ProviderNativeIdentityVerifier.Checkpoint.AfterFile) {
                        wrote = true
                        throw IOException("injected write failure")
                    }
                }
                assertThrows(IOException::class.java) { failed.fingerprint(original, config(original)) }
                assertTrue(wrote)
                var validated = false
                val cancelled = ProviderNativeIdentityVerifier(app) { phase ->
                    if (phase == ProviderNativeIdentityVerifier.Checkpoint.AfterValidation) {
                        validated = true
                        Thread.currentThread().interrupt()
                    }
                }
                try {
                    assertThrows(CancellationException::class.java) { cancelled.fingerprint(original, config(original)) }
                    assertTrue(validated)
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted() }
            } finally { wipe(original) }
        }
    }
}
