package com.fixcraft.yume.crypto

/** Android app-data protection only; this does not participate in YUME wire crypto. */
internal class VaultKeyLayers(
    private val androidWrap: (ByteArray) -> ByteArray,
    private val androidUnwrap: (ByteArray) -> ByteArray,
    private val passwordWrap: (ByteArray, String) -> ByteArray,
    private val passwordUnwrap: (ByteArray, String) -> ByteArray,
) {
    fun protect(key: ByteArray, password: String): ByteArray {
        val inner = passwordWrap(key, password)
        try {
            return androidWrap(inner)
        } finally {
            inner.fill(0)
        }
    }

    /** Migration commits before returning the unchanged data key. No fallback
     * to the old reader is allowed after a layered authentication failure. */
    fun open(
        stored: ByteArray,
        layered: Boolean,
        password: String,
        migrate: (ByteArray) -> Unit,
    ): ByteArray {
        val inner = if (layered) androidUnwrap(stored) else stored.copyOf()
        try {
            val key = passwordUnwrap(inner, password)
            try {
                require(key.size == DATA_KEY_BYTES) { "Encrypted app key is invalid" }
                if (!layered) {
                    val replacement = androidWrap(inner)
                    try {
                        migrate(replacement)
                    } finally {
                        replacement.fill(0)
                    }
                }
                return key
            } catch (error: Throwable) {
                key.fill(0)
                throw error
            }
        } finally {
            inner.fill(0)
        }
    }

    fun openAndroid(stored: ByteArray): ByteArray {
        val key = androidUnwrap(stored)
        if (key.size != DATA_KEY_BYTES) {
            key.fill(0)
            error("Encrypted app key is invalid")
        }
        return key
    }

    companion object {
        const val DATA_KEY_BYTES = 32
    }
}
