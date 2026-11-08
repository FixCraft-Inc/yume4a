package com.fixcraft.yume.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.data.AppVaultManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real Android Keystore and filesystem checks; reopening is not process death. */
@RunWith(AndroidJUnit4::class)
class ProviderStoreTest {
    private lateinit var context: IsolatedContext

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val identity = UUID.randomUUID().toString()
        private val directory = File(base.noBackupFilesDir, "provider-test-$identity").apply { check(mkdir()) }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$name-provider-test-$identity"
            preferences += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun erase() {
            check(directory.deleteRecursively())
            preferences.forEach { check(deleteSharedPreferences(it)) }
        }
    }

    @Before
    fun prepare() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        AppVaultManager.lock()
        assertTrue(AppVaultManager.ensureReady(context))
    }

    @After
    fun clean() {
        AppVaultManager.lock()
        context.erase()
    }

    @Test
    fun committedRecordsSurviveReopenAndSnapshotsWipeTheirOwnedBuffers() {
        val secret = "account-token-that-must-not-appear-in-a-file".toByteArray()
        val bundle = "transport-bundle-separate-from-account-token".toByteArray()
        val epoch = yumeProviderStore(context).use { store ->
            val epoch = store.add(ALPHA)
            store.commit(ALPHA, epoch, secret, mapOf("grant-1" to bundle))
            epoch
        }
        AppVaultManager.lock()
        yumeProviderStore(context).use { store ->
            store.recover()
            val loaded = store.read(ALPHA, epoch)!!
            assertArrayEquals(secret, loaded.metadata)
            assertArrayEquals(bundle, loaded.bundles.getValue("grant-1"))
            loaded.close()
            assertTrue(loaded.metadata.all { it == 0.toByte() })
            assertTrue(loaded.bundles.values.all { bytes -> bytes.all { it == 0.toByte() } })
        }
        context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertEquals(384, Os.stat(file.absolutePath).st_mode and 511)
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(secret.toString(Charsets.ISO_8859_1)))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(bundle.toString(Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun offlineRemovalPreservesOtherProviderAndManualVaultBlob() {
        val manual = byteArrayOf(71, 72, 73)
        AppVaultManager.writeBlob(context, "kit-manual-provider-test", manual)
        yumeProviderStore(context).use { store ->
            val alpha = store.add(ALPHA)
            val beta = store.add(BETA)
            store.commit(ALPHA, alpha, byteArrayOf(1), mapOf("alpha-kit" to byteArrayOf(2)))
            store.commit(BETA, beta, byteArrayOf(3), mapOf("beta-kit" to byteArrayOf(4)))
            store.remove(ALPHA)
            assertNull(store.epoch(ALPHA))
            assertThrows(IllegalStateException::class.java) { store.read(ALPHA, alpha) }
            assertThrows(IllegalStateException::class.java) { store.commit(ALPHA, alpha, byteArrayOf(9), emptyMap()) }
            store.read(BETA, beta)!!.use { assertArrayEquals(byteArrayOf(4), it.bundles.getValue("beta-kit")) }
            assertArrayEquals(manual, AppVaultManager.readBlob(context, "kit-manual-provider-test"))
            val readded = store.add(ALPHA)
            assertTrue(alpha != readded)
            assertNull(store.read(ALPHA, readded))
        }
    }

    @Test
    fun lockedVaultCannotBeReadOrReplacedButRemovalStillErasesOnlySelectedAuthority() {
        val password = "provider-test-password"
        yumeProviderStore(context).use { store ->
            val alpha = store.add(ALPHA)
            val beta = store.add(BETA)
            store.commit(ALPHA, alpha, byteArrayOf(1), emptyMap())
            store.commit(BETA, beta, byteArrayOf(2), emptyMap())
            AppVaultManager.enablePassword(context, password).getOrThrow()
            AppVaultManager.lock()
            assertFalse(AppVaultManager.ensureReady(context))
            assertThrows(IllegalStateException::class.java) { store.read(ALPHA, alpha) }
            assertThrows(IllegalStateException::class.java) { store.commit(ALPHA, alpha, byteArrayOf(3), emptyMap()) }
            store.remove(ALPHA)
            assertNull(store.epoch(ALPHA))
            assertThrows(IllegalStateException::class.java) { store.read(ALPHA, alpha) }
            AppVaultManager.unlockWithPassword(context, password).getOrThrow()
            store.recover()
            store.read(BETA, beta)!!.use { assertArrayEquals(byteArrayOf(2), it.metadata) }
        }
    }

    @Test
    fun failedStagingAndCancelledPublicationKeepPreviousCommittedSnapshot() {
        for (phase in listOf(ProviderStore.Checkpoint.BeforeStage, ProviderStore.Checkpoint.AfterStageSync, ProviderStore.Checkpoint.BeforeManifest)) {
            var fail = false
            val epoch = ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { current -> if (fail && current == phase) throw IOException("injected storage failure") }.use { store ->
                val epoch = store.add(ALPHA)
                store.commit(ALPHA, epoch, byteArrayOf(1), mapOf("old" to byteArrayOf(2)))
                fail = true
                assertThrows(IOException::class.java) { store.commit(ALPHA, epoch, byteArrayOf(3), mapOf("new" to byteArrayOf(4))) }
                epoch
            }
            yumeProviderStore(context).use { store ->
                store.recover()
                store.read(ALPHA, epoch)!!.use { old ->
                    assertArrayEquals(byteArrayOf(1), old.metadata)
                    assertEquals(setOf("old"), old.bundles.keys)
                }
                assertThrows(IllegalStateException::class.java) {
                    store.commit(ALPHA, epoch, byteArrayOf(5), emptyMap(), isCurrent = { false })
                }
                store.remove(ALPHA)
            }
        }
    }

    @Test
    fun cancellationAfterStagingCannotPublishAndDiskWriteFailureKeepsOldGeneration() {
        var current = true
        ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { phase ->
            if (phase == ProviderStore.Checkpoint.AfterStageSync) current = false
        }.use { store ->
            val epoch = store.add(ALPHA)
            store.commit(ALPHA, epoch, byteArrayOf(1), emptyMap())
            current = true
            assertThrows(IllegalStateException::class.java) {
                store.commit(ALPHA, epoch, byteArrayOf(2), emptyMap(), isCurrent = { current })
            }
            store.read(ALPHA, epoch)!!.use { assertArrayEquals(byteArrayOf(1), it.metadata) }
            val vault = File(context.noBackupFilesDir, "app-vault")
            Os.chmod(vault.absolutePath, 320) // 0500: an actual filesystem write refusal.
            try {
                assertThrows(IOException::class.java) {
                    store.commit(ALPHA, epoch, byteArrayOf(3), emptyMap())
                }
            } finally {
                Os.chmod(vault.absolutePath, 448) // 0700.
            }
            store.read(ALPHA, epoch)!!.use { assertArrayEquals(byteArrayOf(1), it.metadata) }
        }
    }

    @Test
    fun failureAfterDurableCommitKeepsCompleteNewSnapshotOnReopen() {
        var fail = false
        val epoch = ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { phase ->
            if (fail && phase == ProviderStore.Checkpoint.AfterManifestSync) throw IOException("injected completion failure")
        }.use { store ->
            val epoch = store.add(ALPHA)
            store.commit(ALPHA, epoch, byteArrayOf(1), mapOf("old" to byteArrayOf(2)))
            fail = true
            assertThrows(IOException::class.java) { store.commit(ALPHA, epoch, byteArrayOf(3), mapOf("new" to byteArrayOf(4))) }
            epoch
        }
        yumeProviderStore(context).use { store ->
            store.recover()
            store.read(ALPHA, epoch)!!.use { loaded ->
                assertArrayEquals(byteArrayOf(3), loaded.metadata)
                assertEquals(setOf("new"), loaded.bundles.keys)
                assertArrayEquals(byteArrayOf(4), loaded.bundles.getValue("new"))
            }
        }
    }

    @Test
    fun durableTombstoneFinishesDeletionAfterInterruptedRemoval() {
        var fail = false
        val epoch = ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { phase ->
            if (fail && phase == ProviderStore.Checkpoint.AfterTombstoneSync) throw IOException("injected cleanup failure")
        }.use { store ->
            val epoch = store.add(ALPHA)
            store.commit(ALPHA, epoch, byteArrayOf(1), mapOf("kit" to byteArrayOf(2)))
            fail = true
            assertThrows(IOException::class.java) { store.remove(ALPHA) }
            epoch
        }
        yumeProviderStore(context).use { store ->
            assertNull(store.epoch(ALPHA))
            assertThrows(IllegalStateException::class.java) { store.read(ALPHA, epoch) }
            store.recover()
            assertTrue(File(context.noBackupFilesDir, "app-vault").listFiles()!!.none { it.name.startsWith("provider-") })
        }
    }

    @Test
    fun corruptRecordAndCorruptEpochIndexFailClosed() {
        val epoch = yumeProviderStore(context).use { store ->
            val epoch = store.add(ALPHA)
            store.commit(ALPHA, epoch, byteArrayOf(1), mapOf("kit" to byteArrayOf(2)))
            epoch
        }
        val record = File(context.noBackupFilesDir, "app-vault").listFiles()!!.first { !it.name.endsWith("manifest.bin") }
        val original = record.readBytes()
        record.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        yumeProviderStore(context).use { store ->
            assertThrows(Exception::class.java) { store.read(ALPHA, epoch) }
            assertThrows(Exception::class.java) { store.recover() }
        }
        File(context.noBackupFilesDir, "provider-state/active-epochs").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { yumeProviderStore(context) }
    }

    @Test
    fun oneWriterAndGlobalProviderAndGrantBoundsAreEnforced() {
        yumeProviderStore(context).use { store ->
            assertThrows(IllegalStateException::class.java) { yumeProviderStore(context) }
            val epochs = (0 until 8).associate { index ->
                val provider = "https://provider$index.example"
                provider to store.add(provider)
            }
            assertThrows(IllegalStateException::class.java) { store.add("https://ninth.example") }
            val grants = (0 until 32).associate { "grant-$it" to byteArrayOf(it.toByte()) }
            val first = epochs.entries.first()
            store.commit(first.key, first.value, byteArrayOf(1), grants)
            val second = epochs.entries.elementAt(1)
            assertThrows(IllegalStateException::class.java) {
                store.commit(second.key, second.value, byteArrayOf(2), mapOf("extra" to byteArrayOf(3)))
            }
            assertNull(store.read(second.key, second.value))
            assertNotNull(store.epoch(first.key))
        }
    }

    companion object {
        private const val ALPHA = "https://alpha.example"
        private const val BETA = "https://beta.example"
    }
}
