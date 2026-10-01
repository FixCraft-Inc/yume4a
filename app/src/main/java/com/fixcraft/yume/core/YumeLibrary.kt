package com.fixcraft.yume.core

/** The `yume_status` codes this application tells apart (yume/yume.h). */
object YumeStatusCode {
    const val OK = 0
    const val INVALID_ARGUMENT = -1
    const val INVALID_STATE = -4
    const val TIMEOUT = -5
    const val WOULD_BLOCK = -6
    const val NOT_FOUND = -7
    const val PERMISSION_DENIED = -8
    const val RESOURCE_EXHAUSTED = -10
    const val CANCELLED = -11
    const val UNSUPPORTED = -14
}

/** A call libyume refused. [pointer] names the configuration member at fault, when one is. */
class YumeException(
    val status: Int,
    val statusName: String,
    val pointer: String,
    val detail: String,
) : Exception(if (detail.isBlank()) statusName else detail)

/** What the linked library is, as `yume_get_compatibility` reports it. */
data class YumeBuild(
    val productVersion: String,
    val protocolName: String,
    val suite: String,
    val cryptoBackend: String,
    val secureChannel: String,
    val frontDoor: String,
    val carrier: String,
    val sessionComponent: String,
    val sessionSecurity: String,
    val evidenceProfile: String,
    val abiVersion: Int,
    val protocolVersion: Int,
    val configSchema: Int,
)

/** One file of a kit. [bytes] may hold a private key: wipe it after use. */
class KitFile(val path: String, val executable: Boolean, val bytes: ByteArray)

object YumeLibrary {
    const val ROLE_CLIENT = 1

    /** Null when the library refuses to describe itself, which a mismatched build causes. */
    fun build(): YumeBuild? {
        val values = YumeNative.nativeCompatibility() ?: return null
        if (values.size < 14) return null
        fun text(index: Int) = values[index].orEmpty()
        return YumeBuild(
            productVersion = text(0),
            protocolName = text(1),
            suite = text(2),
            cryptoBackend = text(3),
            secureChannel = text(4),
            frontDoor = text(5),
            carrier = text(6),
            sessionComponent = text(7),
            sessionSecurity = text(8),
            evidenceProfile = text(9),
            abiVersion = text(10).toIntOrNull() ?: 0,
            protocolVersion = text(11).toIntOrNull() ?: 0,
            configSchema = text(12).toIntOrNull() ?: 0,
        )
    }

    fun statusName(code: Int): String = YumeNative.nativeStatusName(code)

    /** Checks a schema-1 document with libyume's strict parser and returns its role. */
    fun checkConfig(json: ByteArray): Int {
        val role = IntArray(1)
        val result = arrayOfNulls<String>(YumeNative.RESULT_SIZE)
        val status = YumeNative.nativeCheckConfig(json, role, result)
        if (status != YumeStatusCode.OK) throw failure(status, result)
        return role[0]
    }

    /**
     * Opens a sealed kit. [code] is wiped by the caller. The returned files
     * hold the kit's keys: the caller wipes each one once it is stored.
     */
    fun openKit(sealed: ByteArray, code: ByteArray): List<KitFile> {
        val files = ArrayList<KitFile>()
        val result = arrayOfNulls<String>(YumeNative.RESULT_SIZE)
        val status = try {
            YumeNative.nativeOpenKit(sealed, code, { path, executable, bytes ->
                files += KitFile(path, executable, bytes)
            }, result)
        } catch (error: Throwable) {
            files.forEach { it.bytes.fill(0) }
            throw error
        }
        if (status != YumeStatusCode.OK) {
            files.forEach { it.bytes.fill(0) }
            throw failure(status, result)
        }
        return files
    }

    internal fun failure(status: Int, result: Array<String?>): YumeException =
        YumeException(
            status = status,
            statusName = statusName(status),
            pointer = result.getOrNull(0).orEmpty(),
            detail = result.getOrNull(1).orEmpty(),
        )
}
