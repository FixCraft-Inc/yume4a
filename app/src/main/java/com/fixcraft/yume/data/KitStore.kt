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
import org.json.JSONArray
import org.json.JSONObject
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

    private val _kits = MutableStateFlow(loadKits())
    val kits: StateFlow<List<KitSummary>> = _kits.asStateFlow()

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
        val codeBytes = ByteArray(code.size) { index -> code[index].code.toByte() }
        code.fill('\u0000')
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
                    AppVaultManager.writeBlob(appContext, blobName(kit.id), bundle)
                    persist(_kits.value + kit)
                    if (_selectedId.value == null) selectLocked(kit.id)
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
        persist(remaining)
        AppVaultManager.deleteBlob(appContext, blobName(id))
        if (_selectedId.value == id) selectLocked(remaining.firstOrNull()?.id)
    }

    fun select(id: String?) = synchronized(lock) { selectLocked(id) }

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
        val directory = File(sessionRoot(appContext), randomName())
        try {
            makePrivateDirectory(directory)
            var config: ByteArray? = null
            for (file in files) {
                if (file.path == KitBundle.CONFIG_PATH) {
                    config = file.bytes.copyOf()
                    continue
                }
                val target = File(directory, file.path)
                target.parentFile?.takeIf { it != directory }?.let(::makePrivateDirectory)
                FileOutputStream(target).use { it.write(file.bytes) }
                Os.chmod(target.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            }
            val document = config ?: throw IllegalStateException("the kit has no yume.json")
            return KitSession(directory, document, KitConfig.describe(document))
        } catch (error: Exception) {
            wipeDirectory(directory)
            return null
        } finally {
            files.forEach { it.bytes.fill(0) }
        }
    }

    private fun selectLocked(id: String?) {
        val chosen = id?.takeIf { wanted -> _kits.value.any { it.id == wanted } }
        _selectedId.value = chosen
        runCatching { index.putString(KEY_SELECTED, chosen) }
    }

    private fun persist(kits: List<KitSummary>) {
        val array = JSONArray()
        for (kit in kits) {
            array.put(
                JSONObject()
                    .put("id", kit.id)
                    .put("name", kit.name)
                    .put("host", kit.host)
                    .put("port", kit.port)
                    .put("added", kit.addedAtEpochMs),
            )
        }
        index.putString(KEY_KITS, array.toString())
        _kits.value = kits
    }

    private fun loadKits(): List<KitSummary> {
        val raw = runCatching { index.getString(KEY_KITS) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { position ->
                val item = array.optJSONObject(position) ?: return@mapNotNull null
                val id = item.optString("id")
                val host = item.optString("host")
                val port = item.optInt("port", 0)
                if (id.isBlank() || host.isBlank() || port !in 1..65535) return@mapNotNull null
                KitSummary(
                    id = id,
                    name = item.optString("name").ifBlank { host },
                    host = host,
                    port = port,
                    addedAtEpochMs = item.optLong("added", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun describe(error: YumeException): String =
        if (error.pointer.isBlank()) error.detail else "${error.pointer}: ${error.detail}"

    companion object {
        private const val INDEX_NAME = "kits"
        private const val KEY_KITS = "kits_json"
        private const val KEY_SELECTED = "selected_kit_id"
        private const val SESSION_DIR_NAME = "kit-sessions"
        private const val MAX_NAME_LENGTH = 48

        @Volatile
        private var instance: KitStore? = null

        fun get(context: Context): KitStore {
            val storeContext = context.applicationContext ?: context
            return instance ?: synchronized(this) {
                instance ?: KitStore(storeContext).also { instance = it }
            }
        }

        private fun blobName(id: String) = "kit-$id"

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
