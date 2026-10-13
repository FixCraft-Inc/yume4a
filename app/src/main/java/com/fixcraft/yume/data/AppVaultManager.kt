package com.fixcraft.yume.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fixcraft.basefwx.Crypto
import com.fixcraft.yume.crypto.BaseFwxCompat
import com.fixcraft.yume.crypto.VaultKeyLayers
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppVaultManager {
    /** BaseFWX's pinned minimum, measured in UTF-8 bytes. */
    const val MIN_PASSWORD_UTF8_BYTES = 10
    private const val BOOT_PREFS_NAME = "yume_app_vault_boot"
    private const val KEY_PASSWORD_ENABLED = "password_enabled"
    private const val KEY_PASSWORD_WRAPPED_KEY_B64 = "password_wrapped_key_b64"
    private const val KEY_ANDROID_WRAPPED_KEY_B64 = "android_wrapped_key_b64"
    private const val VAULT_DIR_NAME = "app-vault"
    private const val WRAP_KEY_ALIAS = "yume_app_vault_wrap_key"
    private const val BOOT_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val BLOB_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val WRAPPED_VERSION = 1
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val DATA_KEY_LENGTH = 32
    private const val KEY_PASSWORD_LAYERED = "password_wrapped_key_layered"

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

    fun unlockWithPassword(context: Context, password: String): Result<Unit> = runCatching {
        synchronized(sessionLock) {
            replaceSessionKey(verifyPassword(context, password))
        }
    }

    fun enablePassword(context: Context, password: String): Result<Unit> = runCatching {
        require(passwordMeetsPolicy(password)) { "Password must be at least 10 UTF-8 bytes" }
        synchronized(sessionLock) {
            val key = requireSessionKey(context)
            try {
                persistPasswordKey(context, key, password)
            } finally {
                key.fill(0)
            }
        }
    }

    fun changePassword(context: Context, currentPassword: String, newPassword: String): Result<Unit> = runCatching {
        require(passwordMeetsPolicy(newPassword)) { "Password must be at least 10 UTF-8 bytes" }
        synchronized(sessionLock) {
            val key = verifyPassword(context, currentPassword)
            try {
                persistPasswordKey(context, key, newPassword)
                replaceSessionKey(key.copyOf())
            } finally {
                key.fill(0)
            }
        }
    }

    fun disablePassword(context: Context, currentPassword: String): Result<Unit> = runCatching {
        synchronized(sessionLock) {
            val key = verifyPassword(context, currentPassword)
            try {
                val wrapped = wrapWithAndroidKeystore(context, key)
                bootPrefs(context).edit()
                    .putBoolean(KEY_PASSWORD_ENABLED, false)
                    .putString(KEY_ANDROID_WRAPPED_KEY_B64, encode(wrapped))
                    .remove(KEY_PASSWORD_WRAPPED_KEY_B64)
                    .remove(KEY_PASSWORD_LAYERED)
                    .commitOrThrow()
                replaceSessionKey(key.copyOf())
            } finally {
                key.fill(0)
            }
        }
    }

    private fun persistPasswordKey(context: Context, key: ByteArray, password: String) {
        val wrapped = keyLayers(context).protect(key, password)
        try {
            persistLayeredKey(context, wrapped)
        } finally {
            wrapped.fill(0)
        }
    }

    private fun persistLayeredKey(context: Context, wrapped: ByteArray) {
        bootPrefs(context).edit()
            .putBoolean(KEY_PASSWORD_ENABLED, true)
            .putString(KEY_PASSWORD_WRAPPED_KEY_B64, encode(wrapped))
            .putBoolean(KEY_PASSWORD_LAYERED, true)
            // A parallel raw-key wrap would bypass the password layer.
            .remove(KEY_ANDROID_WRAPPED_KEY_B64)
            .commitOrThrow()
    }

    private fun replaceSessionKey(key: ByteArray) {
        sessionKey?.fill(0)
        sessionKey = key
    }

    fun lock() {
        synchronized(sessionLock) {
            sessionKey?.fill(0)
            sessionKey = null
        }
    }

    fun passwordMeetsPolicy(password: String): Boolean {
        val bytes = password.toByteArray(Charsets.UTF_8)
        return try {
            bytes.size >= MIN_PASSWORD_UTF8_BYTES
        } finally {
            bytes.fill(0)
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
        try {
            val file = blobFile(context, name)
            if (!file.exists()) return null
            val envelope = VaultBlobFile.read(file)
            val cipher = Cipher.getInstance(BLOB_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.iv))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(envelope.ciphertext)
        } finally {
            key.fill(0)
        }
    }

    fun writeBlob(context: Context, name: String, plaintext: ByteArray) {
        require(plaintext.size <= VaultBlobFile.MAX_BLOB_BYTES - 64) { "App vault payload is too large" }
        val key = requireSessionKey(context)
        try {
            val cipher = Cipher.getInstance(BLOB_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val envelope = VaultBlobFile.Envelope(cipher.iv, cipher.doFinal(plaintext))
            VaultBlobFile.write(blobFile(context, name), envelope)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Keeps the encrypted bytes of a blob that could not be read under a new
     * name before it is replaced, so a later tool or release can still read it.
     * The copy stays encrypted under its original name's binding.
     */
    fun preserveBlob(context: Context, name: String, suffix: String) {
        val source = blobFile(context, name)
        if (!source.exists()) return
        val target = blobFile(context, "$name-$suffix")
        java.nio.file.Files.copy(source.toPath(), target.toPath())
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    fun deleteBlob(context: Context, name: String) {
        val file = blobFile(context, name)
        check(!file.exists() || file.delete()) { "Could not remove app vault blob" }
    }

    private fun verifyPassword(context: Context, password: String): ByteArray {
        require(isPasswordEnabled(context)) { "Password protection is not enabled" }
        val stored = readPasswordWrappedKey(context)
            ?: throw IllegalStateException("Encrypted app key is missing")
        try {
            return keyLayers(context).open(stored, bootPrefs(context).getBoolean(KEY_PASSWORD_LAYERED, false), password) {
                persistLayeredKey(context, it)
            }
        } finally {
            stored.fill(0)
        }
    }

    private fun keyLayers(context: Context) = VaultKeyLayers(
        androidWrap = { wrapWithAndroidKeystore(context, it) },
        androidUnwrap = { unwrapWithAndroidKeystore(context, it) },
        passwordWrap = BaseFwxCompat::fwxAesEncryptRaw,
        passwordUnwrap = BaseFwxCompat::fwxAesDecryptRaw,
    )

    private fun requireSessionKey(context: Context): ByteArray {
        synchronized(sessionLock) {
            sessionKey?.let { return it.copyOf() }
            require(ensureReady(context)) { "Unlock required" }
            return sessionKey?.copyOf() ?: throw IllegalStateException("App vault is unavailable")
        }
    }

    private fun loadOrCreateAndroidProtectedKey(context: Context): ByteArray {
        readAndroidWrappedKey(context)?.let { wrapped ->
            return keyLayers(context).openAndroid(wrapped)
        }
        // Lost bootstrap preferences must not replace the key of existing data.
        check(!bootPrefs(context).contains(KEY_PASSWORD_WRAPPED_KEY_B64) &&
            vaultDirectory(context).listFiles().orEmpty().none { it.name.endsWith(".bin") }) {
            "App vault key is missing"
        }
        val key = Crypto.randomBytes(DATA_KEY_LENGTH)
        try {
            val wrapped = wrapWithAndroidKeystore(context, key)
            bootPrefs(context).edit()
                .putBoolean(KEY_PASSWORD_ENABLED, false)
                .putString(KEY_ANDROID_WRAPPED_KEY_B64, encode(wrapped))
                .remove(KEY_PASSWORD_WRAPPED_KEY_B64)
                .remove(KEY_PASSWORD_LAYERED)
                .commitOrThrow()
            return key
        } catch (error: Throwable) {
            key.fill(0)
            throw error
        }
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
        val expectedLength = 12L + ivLength + ciphertextLength
        require(ciphertextLength >= 16 && expectedLength == wrapped.size.toLong()) { "Wrapped app key is truncated" }
        val iv = wrapped.copyOfRange(12, 12 + ivLength)
        val ciphertext = wrapped.copyOfRange(12 + ivLength, wrapped.size)
        val cipher = Cipher.getInstance(BOOT_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(create = false), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    private fun readAndroidWrappedKey(context: Context): ByteArray? {
        val encoded = bootPrefs(context).getString(KEY_ANDROID_WRAPPED_KEY_B64, null)?.trim().orEmpty()
        if (encoded.isEmpty()) {
            return null
        }
        require(encoded.length <= 128 * 1024) { "Wrapped app key is too large" }
        return android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    }

    private fun readPasswordWrappedKey(context: Context): ByteArray? {
        val encoded = bootPrefs(context).getString(KEY_PASSWORD_WRAPPED_KEY_B64, null)?.trim().orEmpty()
        if (encoded.isEmpty()) {
            return null
        }
        require(encoded.length <= 128 * 1024) { "Wrapped app key is too large" }
        return android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Could not persist app vault key material" }
    }

    private fun bootPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(BOOT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun vaultDirectory(context: Context): File {
        val dir = File(context.applicationContext.noBackupFilesDir, VAULT_DIR_NAME)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create app vault directory" }
        return dir
    }

    private fun blobFile(context: Context, name: String): File {
        require(Regex("[A-Za-z0-9_-]+").matches(name)) { "Invalid app vault blob name" }
        return File(vaultDirectory(context), "$name.bin")
    }

    private fun getOrCreateWrapKey(create: Boolean = true): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "App vault wrapping key is missing" }
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
        return try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeJsonLocked(json: JSONObject) {
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        try {
            AppVaultManager.writeBlob(context, name, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }
}
