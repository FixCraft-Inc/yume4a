package com.fixcraft.yume.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.core.YumeException
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.core.YumeStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID

/** A kit as the application lists it. Nothing here is secret. */
data class KitSummary(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val addedAtEpochMs: Long,
) {
    val endpointLabel: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/** Why an import was refused, for the screen to say in the user's language. */
enum class KitRefusal {
    /** The code does not open the file, or the file was changed. */
    WrongCode,
    /** The code is not 25 characters of the code alphabet. */
    BadCode,
    TooLarge,
    /** The file opened but does not hold a client kit this library accepts. */
    NotAKit,
    /** The kit routes through circuits, which this application does not carry yet. */
    Circuits,
    /** The application's data is locked or could not be written. */
    Storage,
}

/** State of the encrypted kit index at startup. A corrupt index never deletes
 * or rewrites the individual kit blobs; the user can choose recovery. */
enum class KitIndexStatus { Healthy, Locked, Corrupt }

data class KitRecoveryCandidate(val id: String, val kit: KitSummary)

sealed interface KitImport {
    data class Imported(val kit: KitSummary) : KitImport
    data class Refused(val reason: KitRefusal, val detail: String = "") : KitImport
}

/**
 * A kit written out for one VPN run. libyume reads the credential files by
 * path, so they exist as files while the run lasts. [close] overwrites and
 * removes them.
 */
class KitSession internal constructor(
    val directory: File,
    val config: ByteArray,
    val description: KitDescription,
) : Closeable {
    override fun close() {
        KitStore.wipeDirectory(directory)
    }
}

/**
 * The kits a user imported. Each kit's files are one blob of the application
 * vault ([AppVaultManager]): AES-256-GCM under a key the Android Keystore
 * wraps, and the user's app-data password when one is set. The list of kits
 * is a second blob. Both live in `noBackupFilesDir`, so no backup takes them.
 */
class KitStore private constructor(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val index = VaultJsonMapStore(appContext, INDEX_NAME)
    private val lock = Any()

    private var loadedIndexStatus = KitIndexStatus.Healthy
    private val _kits = MutableStateFlow(loadKits())
    val kits: StateFlow<List<KitSummary>> = _kits.asStateFlow()
    private val _indexStatus = MutableStateFlow(loadedIndexStatus)
    val indexStatus: StateFlow<KitIndexStatus> = _indexStatus.asStateFlow()

    private val _selectedId = MutableStateFlow(
        runCatching { index.getString(KEY_SELECTED) }.getOrNull()
            ?.takeIf { id -> _kits.value.any { it.id == id } }
            ?: _kits.value.firstOrNull()?.id,
    )
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun get(id: String): KitSummary? = _kits.value.firstOrNull { it.id == id }

    /**
     * Opens a sealed kit with its code and stores it. [code] is wiped before
     * this returns. The library does the opening and decides whether the
     * kit's configuration is one it runs.
     */
    fun importSealed(sealed: ByteArray, code: CharArray, name: String): KitImport {
        val codeBytes = asciiCode(code)
        code.fill('\u0000')
        if (codeBytes == null) return KitImport.Refused(KitRefusal.BadCode)
        val files = try {
            YumeLibrary.openKit(sealed, codeBytes)
        } catch (error: YumeException) {
            return KitImport.Refused(
                when (error.status) {
                    YumeStatusCode.PERMISSION_DENIED -> KitRefusal.WrongCode
                    YumeStatusCode.INVALID_ARGUMENT -> KitRefusal.BadCode
                    YumeStatusCode.RESOURCE_EXHAUSTED -> KitRefusal.TooLarge
                    else -> KitRefusal.NotAKit
                },
                error.detail,
            )
        } finally {
            codeBytes.fill(0)
        }
        try {
            KitBundle.problem(files)?.let { return KitImport.Refused(KitRefusal.NotAKit, it) }
            val config = files.first { it.path == KitBundle.CONFIG_PATH }.bytes
            val description = try {
                if (YumeLibrary.checkConfig(config) != YumeLibrary.ROLE_CLIENT) {
                    return KitImport.Refused(KitRefusal.NotAKit, "the kit is not a client's")
                }
                val described = KitConfig.describe(config)
                if (described.usesCircuits) return KitImport.Refused(KitRefusal.Circuits)
                // What the phone will run must parse too.
                YumeLibrary.checkConfig(KitConfig.forDevice(config, null))
                described
            } catch (error: YumeException) {
                return KitImport.Refused(KitRefusal.NotAKit, describe(error))
            } catch (error: KitConfigException) {
                return KitImport.Refused(KitRefusal.NotAKit, error.message.orEmpty())
            }
            val kit = KitSummary(
                id = UUID.randomUUID().toString(),
                name = name.trim().take(MAX_NAME_LENGTH).ifBlank { description.host },
                host = description.host,
                port = description.port,
                addedAtEpochMs = System.currentTimeMillis(),
            )
            val bundle = KitBundle.encode(files)
            try {
                synchronized(lock) {
                    requireHealthyIndex()
                    AppVaultManager.writeBlob(appContext, blobName(kit.id), bundle)
                    persist(_kits.value + kit, _selectedId.value ?: kit.id)
                }
            } catch (error: Exception) {
                runCatching { AppVaultManager.deleteBlob(appContext, blobName(kit.id)) }
                return KitImport.Refused(KitRefusal.Storage, error.message.orEmpty())
            } finally {
                bundle.fill(0)
            }
            return KitImport.Imported(kit)
        } finally {
            files.forEach { it.bytes.fill(0) }
        }
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        val cleaned = name.trim().take(MAX_NAME_LENGTH)
        if (cleaned.isEmpty()) return@synchronized
        persist(_kits.value.map { if (it.id == id) it.copy(name = cleaned) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        val remaining = _kits.value.filterNot { it.id == id }
        requireHealthyIndex()
        require(_kits.value.any { it.id == id }) { "Kit is unavailable" }
        persist(remaining, if (_selectedId.value == id) remaining.firstOrNull()?.id else _selectedId.value)
        AppVaultManager.deleteBlob(appContext, blobName(id))
    }

    fun select(id: String?) = synchronized(lock) { selectLocked(id) }

    /** Lists valid kit blobs without changing the damaged index. */
    fun recoverableKits(): List<KitRecoveryCandidate> = synchronized(lock) {
        check(AppVaultManager.ensureReady(appContext)) { "Unlock required" }
        storedKitFiles().mapNotNull { file ->
            val id = file.name.removePrefix("kit-").removeSuffix(".bin")
            if (!KitIndexCodec.isValidId(id)) return@mapNotNull null
            val bundle = runCatching { AppVaultManager.readBlob(appContext, blobName(id)) }.getOrNull()
                ?: return@mapNotNull null
            try {
                val files = KitBundle.decode(bundle)
                try {
                    val config = files.first { it.path == KitBundle.CONFIG_PATH }.bytes
                    if (YumeLibrary.checkConfig(config) != YumeLibrary.ROLE_CLIENT) return@mapNotNull null
                    val description = KitConfig.describe(config)
                    if (description.usesCircuits) return@mapNotNull null
                    val deviceConfig = KitConfig.forDevice(config, null)
                    try {
                        if (YumeLibrary.checkConfig(deviceConfig) != YumeLibrary.ROLE_CLIENT) return@mapNotNull null
                    } finally {
                        deviceConfig.fill(0)
                    }
                    KitRecoveryCandidate(
                        id,
                        KitSummary(id, description.host, description.host, description.port, file.lastModified()),
                    )
                } finally {
                    files.forEach { it.bytes.fill(0) }
                }
            } catch (_: Exception) {
                null
            } finally {
                bundle.fill(0)
            }
        }
    }

    /** Rebuilds only the index entries the user selected; kit blobs are untouched. */
    fun recoverKits(candidates: List<KitRecoveryCandidate>): Result<Unit> = synchronized(lock) {
        runCatching {
            check(_indexStatus.value == KitIndexStatus.Corrupt) { "Kit index does not need recovery" }
            val current = recoverableKits().associateBy { it.id }
            val chosen = candidates.distinctBy { it.id }.map { candidate ->
                current[candidate.id]?.kit ?: error("Kit recovery candidate is unavailable")
            }
            // Readability and native validity were rechecked above. Replace the
            // index directly: a read/modify/write cannot open a corrupt index.
            val selected = chosen.firstOrNull()?.id
            // The damaged index is kept, still encrypted, before it is replaced.
            AppVaultManager.preserveBlob(appContext, INDEX_NAME, "damaged-${System.currentTimeMillis()}")
            index.replace(KitIndexCodec.encode(chosen, selected))
            _kits.value = chosen
            _selectedId.value = selected
            _indexStatus.value = KitIndexStatus.Healthy
        }
    }

    private fun storedKitFiles(): List<File> = File(appContext.noBackupFilesDir, "app-vault")
        .listFiles().orEmpty().filter { it.isFile && it.name.startsWith("kit-") && it.name.endsWith(".bin") }

    /**
     * Writes a kit's files under a new private directory for one run. Null
     * when the kit is gone or the application's data is locked.
     */
    fun openSession(id: String): KitSession? {
        val bundle = runCatching { AppVaultManager.readBlobIfAvailable(appContext, blobName(id)) }.getOrNull()
            ?: return null
        val files = try {
            KitBundle.decode(bundle)
        } catch (error: IllegalArgumentException) {
            return null
        } finally {
            bundle.fill(0)
        }
        return materializeSession(appContext, files)
    }

    /** Returns a copy of the stored yume.json for settings inspection. */
    fun readConfig(id: String): ByteArray? = synchronized(lock) {
        val bundle = runCatching {
            AppVaultManager.readBlobIfAvailable(appContext, blobName(id))
        }.getOrNull() ?: return@synchronized null
        try {
            val files = KitBundle.decode(bundle)
            return@synchronized files.firstOrNull { it.path == KitBundle.CONFIG_PATH }
                ?.bytes?.copyOf().also { files.forEach { file -> file.bytes.fill(0) } }
        } catch (_: Throwable) {
            return@synchronized null
        } finally {
            bundle.fill(0)
        }
    }

    /**
     * Validates a complete edited client document through libyume before replacing
     * the encrypted kit blob. Every file and every non-limit JSON member survives.
     */
    fun updateLimits(id: String, limits: TuningLimits): Result<Unit> = synchronized(lock) {
        runCatching {
            val bundle = AppVaultManager.readBlob(appContext, blobName(id))
                ?: error("kit unavailable")
            try {
                val files = KitBundle.decode(bundle).toMutableList()
                try {
                    val configFile = files.first { it.path == KitBundle.CONFIG_PATH }
                    val edited = KitConfig.withLimits(configFile.bytes, limits)
                    require(YumeLibrary.checkConfig(edited) == YumeLibrary.ROLE_CLIENT) {
                        "the edited kit is not a client"
                    }
                    // Validate the exact form the VPN service will hand to native code too.
                    require(YumeLibrary.checkConfig(KitConfig.forDevice(edited, null)) == YumeLibrary.ROLE_CLIENT) {
                        "the edited device kit is not a client"
                    }
                    configFile.bytes.fill(0)
                    files[files.indexOf(configFile)] = KitFile(configFile.path, configFile.executable, edited)
                    val replacement = KitBundle.encode(files)
                    try {
                        AppVaultManager.writeBlob(appContext, blobName(id), replacement)
                    } finally {
                        replacement.fill(0)
                    }
                } finally {
                    files.forEach { it.bytes.fill(0) }
                }
            } finally {
                bundle.fill(0)
            }
        }
    }

    private fun requireHealthyIndex() {
        check(_indexStatus.value == KitIndexStatus.Healthy) { "Kit index recovery required" }
    }

    private fun selectLocked(id: String?) {
        requireHealthyIndex()
        require(id == null || _kits.value.any { it.id == id }) { "Kit is unavailable" }
        index.putString(KEY_SELECTED, id)
        _selectedId.value = id
    }

    private fun persist(kits: List<KitSummary>, selected: String? = _selectedId.value) {
        requireHealthyIndex()
        // Keep entries and selection in one atomic encrypted write.
        index.replace(KitIndexCodec.encode(kits, selected))
        _kits.value = kits
        _selectedId.value = selected
    }

    private fun loadKits(): List<KitSummary> {
        return try {
            if (!AppVaultManager.ensureReady(appContext)) {
                loadedIndexStatus = KitIndexStatus.Locked
                return emptyList()
            }
            val snapshot = index.snapshot()
            if (snapshot.length() == 0 && storedKitFiles().isNotEmpty()) {
                error("Kit index is missing")
            }
            KitIndexCodec.decode(snapshot).also { loadedIndexStatus = KitIndexStatus.Healthy }
        } catch (_: Exception) {
            loadedIndexStatus = KitIndexStatus.Corrupt
            emptyList()
        }
    }

    private fun reloadAfterUnlock() = synchronized(lock) {
        if (_indexStatus.value == KitIndexStatus.Locked && AppVaultManager.isUnlocked()) {
            _kits.value = loadKits()
            _indexStatus.value = loadedIndexStatus
            _selectedId.value = runCatching { index.getString(KEY_SELECTED) }.getOrNull()
                ?.takeIf { id -> _kits.value.any { it.id == id } } ?: _kits.value.firstOrNull()?.id
        }
    }

    private fun describe(error: YumeException): String =
        if (error.pointer.isBlank()) error.detail else "${error.pointer}: ${error.detail}"

    companion object {
        private const val INDEX_NAME = "kits"
        private const val KEY_SELECTED = "selected_kit_id"
        private const val SESSION_DIR_NAME = "kit-sessions"
        private const val MAX_NAME_LENGTH = 48

        @Volatile
        private var instance: KitStore? = null

        fun get(context: Context): KitStore {
            val storeContext = context.applicationContext ?: context
            return (instance ?: synchronized(this) {
                instance ?: KitStore(storeContext).also { instance = it }
            }).also { it.reloadAfterUnlock() }
        }

        private fun blobName(id: String) = "kit-$id"

        /**
         * A kit code's bytes, or null when a character is not ASCII. Codes are
         * ASCII, and narrowing another character would name different bytes.
         */
        internal fun asciiCode(code: CharArray): ByteArray? {
            if (code.any { it.code > 0x7f }) return null
            return ByteArray(code.size) { index -> code[index].code.toByte() }
        }

        private fun sessionRoot(context: Context): File =
            File(context.noBackupFilesDir, SESSION_DIR_NAME)

        private fun randomName(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun makePrivateDirectory(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("cannot create the session directory")
            }
            Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        }

        /** One transient session owner shared by independent manual/provider stores. */
        internal fun openBundleSession(context: Context, bundle: ByteArray): KitSession =
            materializeSession(context, KitBundle.decode(bundle))
                ?: throw IllegalStateException("the session could not be materialized")

        private fun materializeSession(context: Context, files: List<com.fixcraft.yume.core.KitFile>): KitSession? {
            var directory: File? = null
            var config: ByteArray? = null
            try {
                val created = File(sessionRoot(context), randomName())
                directory = created
                makePrivateDirectory(created)
                for (file in files) {
                    if (file.path == KitBundle.CONFIG_PATH) {
                        config = file.bytes.copyOf()
                        continue
                    }
                    val target = File(created, file.path)
                    target.parentFile?.takeIf { it != created }?.let(::makePrivateDirectory)
                    FileOutputStream(target).use { it.write(file.bytes) }
                    Os.chmod(target.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
                }
                val document = config ?: throw IllegalStateException("the kit has no yume.json")
                return KitSession(created, document, KitConfig.describe(document)).also { config = null }
            } catch (error: Exception) {
                directory?.let(::wipeDirectory)
                return null
            } finally {
                config?.fill(0)
                files.forEach { it.bytes.fill(0) }
            }
        }

        /** Removes what an earlier run left, such as after the process was killed. */
        fun clearSessions(context: Context) {
            sessionRoot(context.applicationContext ?: context).listFiles()?.forEach(::wipeDirectory)
        }

        /** Overwrites each file before removing it. Flash storage may keep old blocks. */
        internal fun wipeDirectory(directory: File) {
            directory.walkBottomUp().forEach { entry ->
                if (entry.isFile) {
                    runCatching {
                        val size = entry.length().coerceAtMost(KitBundle.MAX_FILE_BYTES.toLong()).toInt()
                        FileOutputStream(entry).use { it.write(ByteArray(size)) }
                    }
                }
                entry.delete()
            }
        }
    }
}
