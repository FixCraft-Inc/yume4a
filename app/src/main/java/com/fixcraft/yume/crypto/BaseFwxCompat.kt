package com.fixcraft.yume.crypto

import com.fixcraft.basefwx.BaseFwx

/**
 * The BaseFWX calls this application makes. BaseFWX wraps the vault key under
 * the optional app-data password and does nothing for the connection, whose
 * cryptography is libyume's.
 */
internal object BaseFwxCompat {
    fun fwxAesEncryptRaw(plaintext: ByteArray, password: String): ByteArray =
        BaseFwx.fwxAesEncryptRaw(plaintext, password, false)

    fun fwxAesDecryptRaw(blob: ByteArray, password: String): ByteArray =
        BaseFwx.fwxAesDecryptRaw(blob, password, false)
}
