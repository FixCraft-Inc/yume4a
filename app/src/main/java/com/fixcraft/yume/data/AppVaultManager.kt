package com.fixcraft.yume.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.crypto.BaseFwxCompat
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppVaultManager {
    private const val BOOT_PREFS_NAME = "yume_app_vault_boot"
    private const val KEY_PASSWORD_ENABLED = "password_enabled"
    private const val KEY_PASSWORD_WRAPPED_KEY_B64 = "password_wrapped_key_b64"
    private const val KEY_ANDROID_WRAPPED_KEY_B64 = "android_wrapped_key_b64"
    private const val VAULT_DIR_NAME = "app-vault"
    private const val WRAP_KEY_ALIAS = "yume_app_vault_wrap_key"
    private const val BOOT_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val BLOB_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val BLOB_VERSION = 1
    private const val WRAPPED_VERSION = 1
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val DATA_KEY_LENGTH = 32

    private val sessionLock = Any()
    private var sessionKey: ByteArray? = null

    fun isPasswordEnabled(context: Context): Boolean {
        return bootPrefs(context).getBoolean(KEY_PASSWORD_ENABLED, false)
    }

    fun isUnlocked(): Boolean = synchronized(sessionLock) { sessionKey != null }

    fun ensureReady(context: Context): Boolean {
        synchronized(sessionLock) {
            sessionKey?.let { return true }
            if (isPasswordEnabled(context)) {
                return false
            }
            sessionKey = loadOrCreateAndroidProtectedKey(context)
            return true
        }
    }

    fun unlockWithPassword(context: Context, password: String): Result<Unit> {
        return runCatching {
            synchronized(sessionLock) {
                require(isPasswordEnabled(context)) { "Password protection is not enabled" }
                val wrapped = readPasswordWrappedKey(context)
                    ?: throw IllegalStateException("Encrypted app key is missing")
                val unwrapped = BaseFwxCompat.fwxAesDecryptRaw(wrapped, password)
                require(unwrapped.size == DATA_KEY_LENGTH) { "Encrypted app key is invalid" }
                sessionKey = unwrapped
            }
        }
    }

    fun enablePassword(context: Context, password: String): Result<Unit> {
        return runCatching {
            require(password.isNotBlank()) { "Password required" }
            synchronized(sessionLock) {
                val key = requireSessionKey(context)
                val wrapped = BaseFwxCompat.fwxAesEncryptRaw(key, password)
                bootPrefs(context).edit()
                    .putBoolean(KEY_PASSWORD_ENABLED, true)
                    .putString(KEY_PASSWORD_WRAPPED_KEY_B64, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
                    .remove(KEY_ANDROID_WRAPPED_KEY_B64)
                    .apply()
            }
        }
    }

    fun changePassword(context: Context, currentPassword: String, newPassword: String): Result<Unit> {
        return runCatching {
            require(newPassword.isNotBlank()) { "New password required" }
            val key = verifyPassword(context, currentPassword)
            synchronized(sessionLock) {
                val wrapped = BaseFwxCompat.fwxAesEncryptRaw(key, newPassword)
                bootPrefs(context).edit()
                    .putBoolean(KEY_PASSWORD_ENABLED, true)
                    .putString(KEY_PASSWORD_WRAPPED_KEY_B64, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
                    .remove(KEY_ANDROID_WRAPPED_KEY_B64)
                    .apply()
                sessionKey = key
            }
        }
    }

    fun disablePassword(context: Context, currentPassword: String): Result<Unit> {
        return runCatching {
            val key = verifyPassword(context, currentPassword)
            synchronized(sessionLock) {
                val wrapped = wrapWithAndroidKeystore(context, key)
                bootPrefs(context).edit()
                    .putBoolean(KEY_PASSWORD_ENABLED, false)
                    .putString(KEY_ANDROID_WRAPPED_KEY_B64, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
                    .remove(KEY_PASSWORD_WRAPPED_KEY_B64)
                    .apply()
                sessionKey = key
            }
        }
    }

    fun lock() {
        synchronized(sessionLock) {
            sessionKey?.fill(0)
            sessionKey = null
        }
    }

    fun readBlobIfAvailable(context: Context, name: String): ByteArray? {
        if (!ensureReady(context)) {
            return null
        }
        return readBlob(context, name)
    }

    fun readBlob(context: Context, name: String): ByteArray? {
        val key = requireSessionKey(context)
        val file = blobFile(context, name)
        if (!file.isFile) {
            return null
        }
        DataInputStream(file.inputStream().buffered()).use { input ->
            val version = input.readInt()
            require(version == BLOB_VERSION) { "Unsupported app vault version" }
            val ivLength = input.readInt()
            require(ivLength in 1..32) { "Invalid app vault IV" }
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val ciphertextLength = input.readInt()
            require(ciphertextLength > 0) { "Invalid app vault payload" }
            val ciphertext = ByteArray(ciphertextLength)
            input.readFully(ciphertext)
            val cipher = Cipher.getInstance(BLOB_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(ciphertext)
        }
    }

    fun writeBlob(context: Context, name: String, plaintext: ByteArray) {
        val key = requireSessionKey(context)
        val file = blobFile(context, name)
        val cipher = Cipher.getInstance(BLOB_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(BLOB_VERSION)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
        }
    }

    fun deleteBlob(context: Context, name: String) {
        blobFile(context, name).delete()
    }

    fun derivePassword(context: Context, purpose: String): String {
        val key = requireSessionKey(context)
        return try {
            val derived = Crypto.hkdfSha256(key, purpose.toByteArray(Charsets.UTF_8), 32)
            android.util.Base64.encodeToString(derived, android.util.Base64.NO_WRAP)
        } finally {
            key.fill(0)
        }
    }

    private fun verifyPassword(context: Context, password: String): ByteArray {
        require(isPasswordEnabled(context)) { "Password protection is not enabled" }
        val wrapped = readPasswordWrappedKey(context)
            ?: throw IllegalStateException("Encrypted app key is missing")
        val key = BaseFwxCompat.fwxAesDecryptRaw(wrapped, password)
        require(key.size == DATA_KEY_LENGTH) { "Encrypted app key is invalid" }
        return key
    }

    private fun requireSessionKey(context: Context): ByteArray {
        synchronized(sessionLock) {
            sessionKey?.let { return it.copyOf() }
            require(ensureReady(context)) { "Unlock required" }
            return sessionKey?.copyOf() ?: throw IllegalStateException("App vault is unavailable")
        }
    }

    private fun loadOrCreateAndroidProtectedKey(context: Context): ByteArray {
        readAndroidWrappedKey(context)?.let { wrapped ->
            return unwrapWithAndroidKeystore(context, wrapped)
        }
        val key = Crypto.randomBytes(DATA_KEY_LENGTH)
        val wrapped = wrapWithAndroidKeystore(context, key)
        bootPrefs(context).edit()
            .putBoolean(KEY_PASSWORD_ENABLED, false)
            .putString(KEY_ANDROID_WRAPPED_KEY_B64, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
            .remove(KEY_PASSWORD_WRAPPED_KEY_B64)
            .apply()
        return key
    }

    private fun wrapWithAndroidKeystore(context: Context, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(BOOT_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(12 + iv.size + ciphertext.size).apply {
            writeInt(this, 0, WRAPPED_VERSION)
            writeInt(this, 4, iv.size)
            writeInt(this, 8, ciphertext.size)
            System.arraycopy(iv, 0, this, 12, iv.size)
            System.arraycopy(ciphertext, 0, this, 12 + iv.size, ciphertext.size)
        }
    }

    private fun unwrapWithAndroidKeystore(context: Context, wrapped: ByteArray): ByteArray {
        require(wrapped.size >= 12) { "Wrapped app key is truncated" }
        val version = readInt(wrapped, 0)
        require(version == WRAPPED_VERSION) { "Wrapped app key version mismatch" }
        val ivLength = readInt(wrapped, 4)
        val ciphertextLength = readInt(wrapped, 8)
        require(ivLength in 1..32) { "Wrapped app key IV is invalid" }
        val expectedLength = 12 + ivLength + ciphertextLength
        require(wrapped.size >= expectedLength) { "Wrapped app key is truncated" }
        val iv = wrapped.copyOfRange(12, 12 + ivLength)
        val ciphertext = wrapped.copyOfRange(12 + ivLength, expectedLength)
        val cipher = Cipher.getInstance(BOOT_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    private fun readAndroidWrappedKey(context: Context): ByteArray? {
        val encoded = bootPrefs(context).getString(KEY_ANDROID_WRAPPED_KEY_B64, null)?.trim().orEmpty()
        if (encoded.isEmpty()) {
            return null
        }
        return android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    }

    private fun readPasswordWrappedKey(context: Context): ByteArray? {
        val encoded = bootPrefs(context).getString(KEY_PASSWORD_WRAPPED_KEY_B64, null)?.trim().orEmpty()
        if (encoded.isEmpty()) {
            return null
        }
        return android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    }

    private fun bootPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(BOOT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun blobFile(context: Context, name: String): File {
        val dir = File(context.applicationContext.noBackupFilesDir, VAULT_DIR_NAME).apply { mkdirs() }
        return File(dir, "$name.bin")
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(target: ByteArray, offset: Int): Int {
        return ((target[offset].toInt() and 0xFF) shl 24) or
            ((target[offset + 1].toInt() and 0xFF) shl 16) or
            ((target[offset + 2].toInt() and 0xFF) shl 8) or
            (target[offset + 3].toInt() and 0xFF)
    }
}

internal class VaultJsonMapStore(
    private val context: Context,
    private val name: String,
) {
    private val lock = Any()

    fun getString(key: String): String? = synchronized(lock) {
        val json = readJsonLocked()
        if (json.has(key) && !json.isNull(key)) json.optString(key) else null
    }

    fun putString(key: String, value: String?) = synchronized(lock) {
        val json = readJsonLocked()
        if (value == null) {
            json.remove(key)
        } else {
            json.put(key, value)
        }
        writeJsonLocked(json)
    }

    fun remove(key: String) = synchronized(lock) {
        val json = readJsonLocked()
        json.remove(key)
        writeJsonLocked(json)
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        JSONObject(readJsonLocked().toString())
    }

    fun replace(json: JSONObject) = synchronized(lock) {
        writeJsonLocked(json)
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        readJsonLocked().length() == 0
    }

    private fun readJsonLocked(): JSONObject {
        val plaintext = AppVaultManager.readBlobIfAvailable(context, name) ?: return JSONObject()
        val raw = String(plaintext, Charsets.UTF_8)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun writeJsonLocked(json: JSONObject) {
        AppVaultManager.writeBlob(context, name, json.toString().toByteArray(Charsets.UTF_8))
    }
}
