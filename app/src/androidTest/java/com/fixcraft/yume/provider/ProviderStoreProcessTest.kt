package com.fixcraft.yume.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.data.AppVaultManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Explicit two-invocation process-death gate. Run this class once with
 * p3CrashPhase=before-manifest or after-manifest, expect process death, then run
 * it again with p3RecoverPhase set to the same value. No argument means the
 * ordinary connected suite checks the backup exclusion without killing itself.
 */
@RunWith(AndroidJUnit4::class)
class ProviderStoreProcessTest {
    private class ProcessContext(base: Context, private val phase: String, create: Boolean) : ContextWrapper(base) {
        val directory = File(base.noBackupFilesDir, "provider-process-$phase")
        init {
            if (create) check(directory.mkdir()) { "Process-test state already exists" }
            check(directory.isDirectory) { "Process-test state missing" }
        }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$name-provider-process-$phase", mode)
        fun erase() {
            check(directory.deleteRecursively()) { "Process-test cleanup failed" }
            check(deleteSharedPreferences("yume_app_vault_boot-provider-process-$phase")) {
                "Process-test preference cleanup failed"
            }
        }
    }

    @Test
    fun processDeathAndRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val crash = arguments.getString("p3CrashPhase")
        val recover = arguments.getString("p3RecoverPhase")
        require(crash == null || recover == null) { "Choose one process-test operation" }
        if (crash == null && recover == null) {
            assertEquals(0, instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            return
        }
        val phase = crash ?: recover!!
        require(phase in setOf(BEFORE, AFTER)) { "Unknown process-test phase" }
        val context = ProcessContext(instrumentation.targetContext, phase, create = crash != null)
        AppVaultManager.lock()
        assertTrue(AppVaultManager.ensureReady(context))
        if (crash != null) performCrash(context, phase) else verifyRecovery(context, phase)
    }

    private fun performCrash(context: ProcessContext, phase: String) {
        var armed = false
        ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { checkpoint ->
            val selected = if (phase == BEFORE) ProviderStore.Checkpoint.BeforeManifest else ProviderStore.Checkpoint.AfterManifestSync
            if (armed && checkpoint == selected) {
                // A synced non-secret marker proves the process reached the
                // requested storage boundary before its actual termination.
                val marker = File(context.directory, "death-checkpoint")
                FileOutputStream(marker).use { stream ->
                    stream.write(phase.toByteArray(Charsets.US_ASCII))
                    stream.fd.sync()
                }
                syncDirectory(context.directory)
                Process.killProcess(Process.myPid())
                throw AssertionError("Process termination returned")
            }
        }.use { store ->
            val alpha = store.add(ALPHA)
            val beta = store.add(BETA)
            store.commit(ALPHA, alpha, OLD_METADATA, mapOf("old-grant" to OLD_BUNDLE))
            store.commit(BETA, beta, BETA_METADATA, mapOf("beta-grant" to BETA_BUNDLE))
            AppVaultManager.writeBlob(context, MANUAL_NAME, MANUAL_BUNDLE)
            syncDirectory(File(context.directory, "app-vault"))
            syncDirectory(context.directory)
            armed = true
            store.commit(ALPHA, alpha, NEW_METADATA, mapOf("new-grant" to NEW_BUNDLE))
            throw AssertionError("Process-test checkpoint did not terminate")
        }
    }

    private fun verifyRecovery(context: ProcessContext, phase: String) {
        assertEquals(phase, File(context.directory, "death-checkpoint").readText(Charsets.US_ASCII))
        yumeProviderStore(context).use { store ->
            store.recover()
            val alpha = store.epoch(ALPHA)
            val beta = store.epoch(BETA)
            assertNotNull(alpha)
            assertNotNull(beta)
            store.read(ALPHA, alpha!!)!!.use { snapshot ->
                val committed = phase == AFTER
                assertArrayEquals(if (committed) NEW_METADATA else OLD_METADATA, snapshot.metadata)
                val grant = if (committed) "new-grant" else "old-grant"
                assertEquals(setOf(grant), snapshot.bundles.keys)
                assertArrayEquals(if (committed) NEW_BUNDLE else OLD_BUNDLE, snapshot.bundles.getValue(grant))
            }
            store.read(BETA, beta!!)!!.use { snapshot ->
                assertArrayEquals(BETA_METADATA, snapshot.metadata)
                assertEquals(setOf("beta-grant"), snapshot.bundles.keys)
                assertArrayEquals(BETA_BUNDLE, snapshot.bundles.getValue("beta-grant"))
            }
            val manual = AppVaultManager.readBlob(context, MANUAL_NAME)!!
            try {
                assertArrayEquals(MANUAL_BUNDLE, manual)
            } finally {
                manual.fill(0)
            }
            val vaultFiles = File(context.directory, "app-vault").listFiles()!!.toList()
            // Two manifests, one metadata and one bundle each, plus manual data.
            assertEquals(7, vaultFiles.size)
            assertFalse(vaultFiles.any { it.name.endsWith(".tmp") })
            store.remove(ALPHA)
            store.read(BETA, beta)!!.use { assertArrayEquals(BETA_METADATA, it.metadata) }
        }
        AppVaultManager.lock()
        context.erase()
    }

    companion object {
        private const val BEFORE = "before-manifest"
        private const val AFTER = "after-manifest"
        private const val ALPHA = "https://process-alpha.example"
        private const val BETA = "https://process-beta.example"
        private const val MANUAL_NAME = "kit-manual-process-test"
        private val OLD_METADATA = byteArrayOf(11, 12)
        private val OLD_BUNDLE = byteArrayOf(13, 14)
        private val NEW_METADATA = byteArrayOf(21, 22)
        private val NEW_BUNDLE = byteArrayOf(23, 24)
        private val BETA_METADATA = byteArrayOf(31, 32)
        private val BETA_BUNDLE = byteArrayOf(33, 34)
        private val MANUAL_BUNDLE = byteArrayOf(41, 42)

        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
