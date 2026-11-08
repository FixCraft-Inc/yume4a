package com.fixcraft.yume.provider

import android.content.Context
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.data.KitBundle
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException

/** Materializes only contained credential files for the native validation call. */
internal class ProviderNativeIdentityVerifier(
    context: Context,
    private val checkpoint: (Checkpoint) -> Unit,
) : YumeProviderNative.NativeIdentityVerifier {
    constructor(context: Context) : this(context, {})

    internal enum class Checkpoint { AfterFile, AfterValidation }
    private val root = (context.applicationContext ?: context).noBackupFilesDir.toPath()

    override fun fingerprint(files: List<KitFile>, originalConfig: ByteArray): String {
        checkCancellation()
        require(KitBundle.problem(files) == null)
        val indexed = files.associateBy { it.path }
        require(indexed.getValue(KitBundle.CONFIG_PATH).bytes.contentEquals(originalConfig))
        require(YumeLibrary.checkConfig(originalConfig) == YumeLibrary.ROLE_CLIENT)
        val document = JSONObject(String(originalConfig, Charsets.UTF_8))
        // This bounded adapter handles the provider client's direct client kit.
        require(!document.has("circuits") && !document.getJSONObject("endpoint").has("socks5_proxy"))
        val credentials = document.getJSONObject("credentials")
        val referenced = REFERENCES.map { key ->
            val reference = credentials.getJSONObject(key)
            require(reference.length() == 1 && reference.has("file"))
            val path = reference.getString("file")
            require(KitBundle.isSafePath(path) && path != KitBundle.CONFIG_PATH)
            indexed.getValue(path).also { require(!it.executable) }
        }
        require(referenced.map { it.path }.distinct().size == REFERENCES.size)
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        // Cleanup visits only paths owned by this call, never a provider store
        // or manual session. Programs and unrelated files are never written.
        val targets = ArrayList<Path>(REFERENCES.size)
        val directories = LinkedHashSet<Path>()
        val zeroes = ByteBuffer.allocate(8192)
        val directory = Files.createTempDirectory(root, "provider-validation-", PRIVATE_DIRECTORY)
        var primary: Throwable? = null
        try {
            for (file in referenced) {
                checkCancellation()
                val path = directory.resolve(file.path)
                val parent = path.parent
                if (parent != directory && directories.add(parent)) Files.createDirectory(parent, PRIVATE_DIRECTORY)
                targets.add(path)
                FileChannel.open(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PRIVATE_FILE).use { output ->
                    val input = ByteBuffer.wrap(file.bytes)
                    while (input.hasRemaining()) output.write(input)
                }
                checkpoint(Checkpoint.AfterFile)
            }
            checkCancellation()
            val identity = YumeLibrary.validateClientCredentials(directory.toFile(), originalConfig)
            checkpoint(Checkpoint.AfterValidation)
            checkCancellation()
            return identity
        } catch (error: Throwable) {
            val outcome = if (error is IOException && Thread.currentThread().isInterrupted) {
                CancellationException("credential validation cancelled")
            } else error
            primary = outcome
            throw outcome
        } finally {
            // FileChannel closes on interruption. Temporarily clear that flag
            // so cancellation still overwrites and removes owned plaintext.
            val interrupted = Thread.interrupted()
            try {
                cleanup(directory, targets, directories, zeroes)
            } catch (error: IOException) {
                if (primary == null) throw error
                primary.addSuppressed(error)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private fun cleanup(directory: Path, targets: List<Path>, directories: Set<Path>, zeroes: ByteBuffer) {
        var failure: IOException? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (_: IOException) {
                if (failure == null) failure = IOException("credential validation cleanup failed")
            }
        }
        for (path in targets.asReversed()) {
            attempt {
                if (Files.exists(path, NOFOLLOW_LINKS)) {
                    FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { output ->
                        var remaining = output.size().coerceAtMost(KitBundle.MAX_FILE_BYTES.toLong())
                        while (remaining > 0) {
                            zeroes.clear()
                            zeroes.limit(minOf(remaining, zeroes.capacity().toLong()).toInt())
                            while (zeroes.hasRemaining()) remaining -= output.write(zeroes)
                        }
                    }
                }
            }
            attempt { Files.deleteIfExists(path) }
        }
        for (path in directories.toList().asReversed()) attempt { Files.deleteIfExists(path) }
        attempt { Files.delete(directory) }
        failure?.let { throw it }
    }

    companion object {
        private val REFERENCES = listOf("composite_key", "access_psk", "admission_key", "server_trust", "server_identity", "server_mlkem")
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
