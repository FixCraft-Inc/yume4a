package com.fixcraft.yume.provider

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * One app-layer owner of provider records. The consumer supplies encryption and
 * keys through [ProviderSecretBackend]. It never reads or changes manual kits.
 * The consumer keeps both directories below noBackupFilesDir. A store holds its
 * exclusive writer lock until close.
 *
 * Immutable encrypted records are synced before the encrypted manifest replaces
 * its predecessor. The manifest is the only enrollment publication point. The
 * separate, non-secret active-epoch index makes removal durable while locked;
 * absence from that index is a tombstone for every previous enrollment epoch.
 * Browser attempts belong in memory and must not be passed as metadata.
 */
class ProviderStore(
    private val controlDirectory: File,
    private val backend: ProviderSecretBackend,
    private val checkpoint: (Checkpoint) -> Unit = {},
) : Closeable {

    class Epoch internal constructor(val value: String) {
        override fun equals(other: Any?): Boolean = other is Epoch && value == other.value
        override fun hashCode(): Int = value.hashCode()
    }

    /** The caller owns these plaintext buffers and must close the snapshot. */
    class Snapshot internal constructor(
        val metadata: ByteArray,
        val bundles: Map<String, ByteArray>,
    ) : Closeable {
        override fun close() {
            metadata.fill(0)
            bundles.values.forEach { it.fill(0) }
        }
    }

    /** Fault-injection points for storage crash/recovery qualification. No success override. */
    enum class Checkpoint {
        BeforeStage, AfterStageSync, BeforeManifest, AfterManifestSync, AfterTombstoneSync,
    }

    private data class Record(val name: String, val size: Int, val digest: ByteArray)
    private data class Manifest(val epoch: Epoch, val metadata: Record, val bundles: Map<String, Record>) {
        fun names(): Set<String> = bundles.values.map { it.name }.toSet() + metadata.name
    }

    private val monitor = Any()
    private val vaultDirectory = backend.directory
    private val controlFile = File(controlDirectory, "active-epochs")
    private val writerFile: RandomAccessFile
    private val writerLock: FileLock
    private var closed = false

    init {
        ensureDirectory(controlDirectory)
        if (vaultDirectory.exists()) require(Files.isDirectory(vaultDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Invalid provider vault directory"
        }
        val lockFile = File(controlDirectory, "writer.lock")
        require(!Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isRegularFile(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid provider lock file" }
        val handle = RandomAccessFile(lockFile, "rw")
        try {
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            check(lock != null) { "Provider storage already has a writer" }
            writerFile = handle
            writerLock = lock
            // Validate rather than silently replace an unreadable removal index.
            activeEpochs()
        } catch (error: Throwable) {
            handle.close()
            throw error
        }
    }

    /** Adds an explicit enrollment. Re-adding a removed origin gets a new epoch. */
    fun add(provider: String): Epoch = synchronized(monitor) {
        ensureOpen()
        val key = providerKey(provider)
        val active = activeEpochs()
        active[key]?.let { return@synchronized it }
        check(active.size < MAX_PROVIDERS) { "Provider storage limit reached" }
        // Refuse to reattach ciphertext from a removed enrollment.
        eraseProvider(key)
        val epoch = Epoch(UUID.randomUUID().toString())
        persistEpochs(active + (key to epoch))
        epoch
    }

    /** Active epoch values. Reads only the non-secret epoch index. */
    internal fun epochs(): Set<String> = synchronized(monitor) {
        ensureOpen()
        activeEpochs().values.map { it.value }.toSet()
    }

    fun epoch(provider: String): Epoch? = synchronized(monitor) {
        ensureOpen()
        activeEpochs()[providerKey(provider)]
    }

    /** Enumerates only authenticated enrollment metadata, without exposing tokens. */
    internal fun origins(): List<String> = synchronized(monitor) {
        ensureOpen()
        requireUnlocked()
        activeEpochs().map { (key, epoch) ->
            val manifest = readManifest(key, epoch) ?: throw IOException("Incomplete provider enrollment")
            val bytes = readRecord(manifest.metadata, MAX_METADATA_BYTES)
            try {
                val provider = org.json.JSONObject(String(bytes, Charsets.UTF_8)).getString("provider")
                check(providerKey(provider) == key) { "Provider metadata binding failed" }
                provider
            } finally { bytes.fill(0) }
        }
    }

    /** No null-on-lock fallback: a locked or corrupt vault cannot become empty state. */
    fun read(provider: String, epoch: Epoch): Snapshot? = synchronized(monitor) {
        ensureOpen()
        val key = providerKey(provider)
        requireEpoch(key, epoch)
        requireUnlocked()
        val manifest = readManifest(key, epoch) ?: return@synchronized null
        var metadata: ByteArray? = null
        val bundles = linkedMapOf<String, ByteArray>()
        try {
            val loadedMetadata = readRecord(manifest.metadata, MAX_METADATA_BYTES)
            metadata = loadedMetadata
            manifest.bundles.forEach { (id, record) -> bundles[id] = readRecord(record, MAX_BUNDLE_BYTES) }
            Snapshot(loadedMetadata, bundles)
        } catch (error: Throwable) {
            metadata?.fill(0)
            bundles.values.forEach { it.fill(0) }
            throw error
        }
    }

    /**
     * Replaces one provider's complete committed snapshot. Inputs remain owned by
     * the caller. Callers serialize this final commit with operation cancellation;
     * isCurrent also prevents already-cancelled work from publishing after I/O.
     * A failure after manifest replacement is ambiguous: recovery reads either
     * complete generation, and the client must not announce Ready on that failure.
     */
    fun commit(
        provider: String,
        epoch: Epoch,
        metadata: ByteArray,
        bundles: Map<String, ByteArray>,
        isCurrent: () -> Boolean = { true },
    ) = synchronized(monitor) {
        ensureOpen()
        val key = providerKey(provider)
        requireEpoch(key, epoch)
        requireUnlocked()
        require(metadata.size <= MAX_METADATA_BYTES) { "Provider metadata exceeds storage limit" }
        require(bundles.size <= MAX_GRANTS && bundles.keys.all { IDENTIFIER.matches(it) }) {
            "Invalid provider bundle set"
        }
        require(bundles.values.all { it.size <= MAX_BUNDLE_BYTES }) { "Provider bundle exceeds storage limit" }
        val active = activeEpochs()
        var total = bundles.size
        active.filterKeys { it != key }.forEach { (other, otherEpoch) ->
            total += readManifest(other, otherEpoch)?.bundles?.size ?: 0
        }
        check(total <= MAX_GRANTS) { "Provider grant storage limit reached" }
        check(isCurrent()) { "Provider operation cancelled" }
        checkpoint(Checkpoint.BeforeStage)
        val staged = mutableListOf<String>()
        var publicationAttempted = false
        try {
            fun stage(bytes: ByteArray): Record {
                val name = prefix(key) + UUID.randomUUID().toString()
                staged += name
                backend.writeBlob(name, bytes)
                return Record(name, bytes.size, sha256(bytes))
            }
            val metadataRecord = stage(metadata)
            val bundleRecords = bundles.mapValues { (_, bytes) -> stage(bytes) }
            syncDirectory(vaultDirectory)
            syncDirectory(vaultDirectory.parentFile!!)
            checkpoint(Checkpoint.AfterStageSync)
            requireEpoch(key, epoch)
            check(isCurrent()) { "Provider operation cancelled" }
            checkpoint(Checkpoint.BeforeManifest)
            check(isCurrent()) { "Provider operation cancelled" }
            val encoded = encodeManifest(Manifest(epoch, metadataRecord, bundleRecords))
            try {
                // Once replacement is attempted, retain both generations on any
                // failure: a renamed manifest may already refer to the new files.
                publicationAttempted = true
                backend.writeBlob(manifestName(key), encoded)
                syncDirectory(vaultDirectory)
            } finally {
                encoded.fill(0)
            }
            checkpoint(Checkpoint.AfterManifestSync)
            cleanStaging(key, staged.toSet())
        } catch (error: Throwable) {
            if (!publicationAttempted) {
                staged.forEach { backend.deleteBlob(it) }
                if (vaultDirectory.exists()) syncDirectory(vaultDirectory)
            }
            throw error
        }
    }

    /**
     * Persists the tombstone before erasing any authority. No vault unlock or
     * network access is required. Callers invalidate jobs and stop transport at
     * this boundary, then may try bounded revocation with their in-memory copy.
     */
    fun remove(provider: String) = synchronized(monitor) {
        ensureOpen()
        val key = providerKey(provider)
        val active = activeEpochs()
        if (key in active) persistEpochs(active - key)
        checkpoint(Checkpoint.AfterTombstoneSync)
        eraseProvider(key)
    }

    /**
     * Discards only provider-owned uncommitted ciphertext. Run after unlock and
     * before exposing enrollment state. A missing referenced record is an error;
     * recovery never invents empty metadata or revives a removed epoch.
     */
    fun recover() = synchronized(monitor) {
        ensureOpen()
        val active = activeEpochs()
        controlDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(".epochs-") && it.name.endsWith(".tmp") }
            .forEach { check(it.delete()) { "Could not remove provider epoch staging" } }
        syncDirectory(controlDirectory)
        eraseInactive(active.keys)
        if (!backend.ensureReady()) return@synchronized
        active.forEach { (key, epoch) ->
            val manifest = readManifest(key, epoch)
            if (manifest != null) {
                val metadata = readRecord(manifest.metadata, MAX_METADATA_BYTES)
                metadata.fill(0)
                manifest.bundles.values.forEach { record -> readRecord(record, MAX_BUNDLE_BYTES).fill(0) }
            }
            cleanStaging(key, manifest?.names().orEmpty())
        }
    }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        try {
            writerLock.release()
        } finally {
            writerFile.close()
        }
    }

    private fun activeEpochs(): Map<String, Epoch> {
        if (!Files.exists(controlFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        require(Files.isRegularFile(controlFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid provider epoch index" }
        require(controlFile.length() in 8..MAX_CONTROL_BYTES.toLong()) { "Invalid provider epoch index" }
        return DataInputStream(controlFile.inputStream().buffered()).use { input ->
            require(input.readInt() == CONTROL_MAGIC) { "Invalid provider epoch index" }
            val count = input.readInt()
            require(count in 0..MAX_PROVIDERS) { "Invalid provider epoch index" }
            val result = linkedMapOf<String, Epoch>()
            repeat(count) {
                val key = input.readUTF()
                val epoch = input.readUTF()
                require(HASH.matches(key) && UUID_TEXT.matches(epoch) && key !in result) { "Invalid provider epoch index" }
                result[key] = Epoch(epoch)
            }
            require(input.read() == -1) { "Invalid provider epoch index" }
            result
        }
    }

    private fun persistEpochs(active: Map<String, Epoch>) {
        val temporary = File.createTempFile(".epochs-", ".tmp", controlDirectory)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(CONTROL_MAGIC)
                output.writeInt(active.size)
                active.toSortedMap().forEach { (key, epoch) -> output.writeUTF(key); output.writeUTF(epoch.value) }
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), controlFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(controlDirectory)
        } finally {
            temporary.delete()
        }
    }

    private fun requireEpoch(key: String, epoch: Epoch) {
        check(activeEpochs()[key] == epoch) { "Provider enrollment removed" }
    }

    private fun requireUnlocked() {
        check(backend.ensureReady()) { "Provider vault locked" }
    }

    private fun readManifest(key: String, epoch: Epoch): Manifest? {
        checkBlobFile(manifestName(key), MAX_MANIFEST_BYTES)
        val bytes = backend.readBlob(manifestName(key)) ?: return null
        try {
            require(bytes.size <= MAX_MANIFEST_BYTES) { "Invalid provider manifest" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MANIFEST_MAGIC) { "Invalid provider manifest" }
                val storedEpoch = Epoch(input.readUTF())
                require(storedEpoch == epoch) { "Provider manifest epoch mismatch" }
                fun record(limit: Int): Record {
                    val name = input.readUTF()
                    val size = input.readInt()
                    val digest = ByteArray(32)
                    input.readFully(digest)
                    require(name.startsWith(prefix(key)) && UUID_TEXT.matches(name.removePrefix(prefix(key))) && size in 0..limit) {
                        "Invalid provider record"
                    }
                    return Record(name, size, digest)
                }
                val metadata = record(MAX_METADATA_BYTES)
                val count = input.readInt()
                require(count in 0..MAX_GRANTS) { "Invalid provider manifest" }
                val bundles = linkedMapOf<String, Record>()
                repeat(count) {
                    val id = input.readUTF()
                    require(IDENTIFIER.matches(id) && id !in bundles) { "Invalid provider bundle identifier" }
                    bundles[id] = record(MAX_BUNDLE_BYTES)
                }
                require(input.read() == -1) { "Invalid provider manifest" }
                Manifest(storedEpoch, metadata, bundles)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun encodeManifest(manifest: Manifest): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeUTF(manifest.epoch.value)
            fun record(value: Record) {
                output.writeUTF(value.name)
                output.writeInt(value.size)
                output.write(value.digest)
            }
            record(manifest.metadata)
            output.writeInt(manifest.bundles.size)
            manifest.bundles.toSortedMap().forEach { (id, value) -> output.writeUTF(id); record(value) }
        }
        return bytes.toByteArray()
    }

    private fun readRecord(record: Record, limit: Int): ByteArray {
        checkBlobFile(record.name, limit)
        val bytes = backend.readBlob(record.name) ?: throw IOException("Provider record missing")
        if (bytes.size != record.size || bytes.size > limit || !MessageDigest.isEqual(sha256(bytes), record.digest)) {
            bytes.fill(0)
            throw IOException("Provider record mismatch")
        }
        return bytes
    }

    private fun checkBlobFile(name: String, limit: Int) {
        val file = File(vaultDirectory, "$name.bin")
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 28L..limit + 64L) {
                "Invalid provider record file"
            }
        }
    }

    private fun cleanStaging(key: String, keep: Set<String>) {
        val files = vaultDirectory.listFiles() ?: return
        files.filter { file -> file.name.startsWith(prefix(key)) || file.name.startsWith(".${prefix(key)}") }
            .forEach { file ->
                val name = file.name.removeSuffix(".bin")
                if (name != manifestName(key) && name !in keep) {
                    check(file.delete()) { "Could not remove provider staging" }
                }
            }
        syncDirectory(vaultDirectory)
    }

    private fun eraseProvider(key: String) {
        val files = vaultDirectory.listFiles() ?: return
        files.filter { it.name.startsWith(prefix(key)) || it.name.startsWith(".${prefix(key)}") }.forEach { file ->
            check(file.delete()) { "Could not remove provider authority" }
        }
        syncDirectory(vaultDirectory)
    }

    private fun eraseInactive(active: Set<String>) {
        val files = vaultDirectory.listFiles() ?: return
        files.filter { it.name.removePrefix(".").startsWith(BLOB_PREFIX) }.forEach { file ->
            val key = file.name.removePrefix(".").removePrefix(BLOB_PREFIX).take(64)
            if (HASH.matches(key) && key !in active) check(file.delete()) { "Could not finish provider removal" }
        }
        syncDirectory(vaultDirectory)
    }

    private fun ensureOpen() = check(!closed) { "Provider storage closed" }

    companion object {
        const val MAX_PROVIDERS = 8
        const val MAX_GRANTS = 32
        const val MAX_METADATA_BYTES = 256 * 1024
        const val MAX_BUNDLE_BYTES = 1024 * 1024 + 4096
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private const val MAX_CONTROL_BYTES = 2048
        private const val CONTROL_MAGIC = 0x59504531
        private const val MANIFEST_MAGIC = 0x59504d31
        private const val BLOB_PREFIX = "provider-"
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun providerKey(provider: String): String {
            ProviderCodec.origin(provider)
            return sha256(provider.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
        }

        private fun prefix(key: String) = "$BLOB_PREFIX$key-"
        private fun manifestName(key: String) = prefix(key) + "manifest"
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun ensureDirectory(directory: File) {
            if (!directory.exists()) {
                check(directory.mkdir()) { "Cannot create provider storage directory" }
                syncDirectory(directory.parentFile!!)
            }
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid provider storage directory" }
        }

        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Invalid provider storage directory" }
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
