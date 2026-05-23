package com.fixcraft.yume.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fixcraft.yume.R
import com.fixcraft.yume.crypto.BaseFwxCompat
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.transport.AuthKeyImportResolver
import com.fixcraft.yume.transport.YumeAuthKeyValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SecureMaterialType(val wireValue: String) {
    AnonymCa("anonym_ca"),
    AuthKey("auth_key");

    companion object {
        fun fromWireValue(value: String): SecureMaterialType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class SecureMaterialSummary(
    val id: String,
    val displayName: String,
    val type: SecureMaterialType,
    val sourceLabel: String,
    val fingerprint: String,
    val importedEncrypted: Boolean = false,
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val subtitle: String
        get() = buildList {
            sourceLabel.trim()
                .takeIf { it.isNotEmpty() && !it.equals(displayName.trim(), ignoreCase = true) }
                ?.let(::add)
            fingerprint.takeIf { it.isNotBlank() }?.let { add("ID $it") }
        }.joinToString("  ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("display_name", displayName)
        .put("type", type.wireValue)
        .put("source_label", sourceLabel)
        .put("fingerprint", fingerprint)
        .put("imported_encrypted", importedEncrypted)
        .put("created_at_epoch_ms", createdAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): SecureMaterialSummary? {
            val type = SecureMaterialType.fromWireValue(json.optString("type")) ?: return null
            val id = json.optString("id").trim()
            if (id.isEmpty()) {
                return null
            }
            return SecureMaterialSummary(
                id = id,
                displayName = json.optString("display_name").trim(),
                type = type,
                sourceLabel = json.optString("source_label").trim(),
                fingerprint = json.optString("fingerprint"),
                importedEncrypted = json.optBoolean("imported_encrypted", false),
                createdAtEpochMs = json.optLong("created_at_epoch_ms", System.currentTimeMillis()),
            )
        }
    }
}

class SecureMaterialStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val metadataStore = VaultJsonMapStore(appContext, VAULT_NAME)
    private val secureDir = File(appContext.noBackupFilesDir, SECURE_DIR_NAME).apply { mkdirs() }

    private val _userMaterials = MutableStateFlow(loadUserMaterials())
    val userMaterials: StateFlow<List<SecureMaterialSummary>> = _userMaterials.asStateFlow()

    init {
        upgradeLegacyAuthKeyStorage(_userMaterials.value)
    }

    fun caMaterials(materials: List<SecureMaterialSummary> = _userMaterials.value): List<SecureMaterialSummary> {
        return listOf(defaultAnonymCaSummary()) + materials.filter { it.type == SecureMaterialType.AnonymCa }
    }

    fun authKeyMaterials(materials: List<SecureMaterialSummary> = _userMaterials.value): List<SecureMaterialSummary> {
        return materials.filter { it.type == SecureMaterialType.AuthKey }
    }

    fun getSummary(id: String): SecureMaterialSummary? {
        if (id == DEFAULT_ANONYM_CA_ID) {
            return defaultAnonymCaSummary()
        }
        return _userMaterials.value.firstOrNull { it.id == id }
    }

    fun hasMaterial(type: SecureMaterialType, id: String): Boolean {
        return getSummary(id)?.type == type
    }

    fun readMaterialText(id: String): String {
        if (id == DEFAULT_ANONYM_CA_ID) {
            return normalizePem(readDefaultAnonymCaPem())
        }
        val summary = _userMaterials.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Material not found")
        val bytes = decryptFromFile(fileFor(summary.id))
        return when (summary.type) {
            SecureMaterialType.AnonymCa -> normalizePem(bytes.toString(Charsets.UTF_8))
            SecureMaterialType.AuthKey -> readAuthKeyPem(summary, bytes, userPassword = null)
        }
    }

    fun readAuthKeyPem(id: String, userPassword: String?): String {
        val summary = _userMaterials.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Material not found")
        require(summary.type == SecureMaterialType.AuthKey) { "Material is not an auth key" }
        return readAuthKeyPem(summary, decryptFromFile(fileFor(summary.id)), userPassword)
    }

    fun saveImportedAuthKeyText(
        displayName: String,
        rawText: String,
        encrypted: Boolean,
        password: String,
        sourceLabel: String = displayName,
    ): SecureMaterialSummary {
        val validatedPem = AuthKeyImportResolver.resolveText(rawText, encrypted, password)
        val storedBytes = if (encrypted) {
            rawText.toByteArray(Charsets.UTF_8)
        } else {
            validatedPem.toByteArray(Charsets.UTF_8)
        }
        return saveMaterialBytesInternal(
            type = SecureMaterialType.AuthKey,
            displayName = displayName,
            storedBytes = storedBytes,
            sourceLabel = sourceLabel,
            importedEncrypted = encrypted,
            fingerprintSourcePem = validatedPem,
        )
    }

    fun saveImportedAuthKeyBytes(
        displayName: String,
        bytes: ByteArray,
        encrypted: Boolean,
        password: String,
        sourceLabel: String = displayName,
    ): SecureMaterialSummary {
        val validatedPem = AuthKeyImportResolver.resolveBytes(bytes, encrypted, password)
        val storedBytes = if (encrypted) bytes else validatedPem.toByteArray(Charsets.UTF_8)
        return saveMaterialBytesInternal(
            type = SecureMaterialType.AuthKey,
            displayName = displayName,
            storedBytes = storedBytes,
            sourceLabel = sourceLabel,
            importedEncrypted = encrypted,
            fingerprintSourcePem = validatedPem,
        )
    }

    fun saveMaterialText(
        type: SecureMaterialType,
        displayName: String,
        rawPemText: String,
        sourceLabel: String = displayName,
        importedEncrypted: Boolean = false,
    ): SecureMaterialSummary {
        val normalizedPem = normalizeAndValidate(type, rawPemText)
        return saveMaterialBytesInternal(
            type = type,
            displayName = displayName,
            storedBytes = normalizedPem.toByteArray(Charsets.UTF_8),
            sourceLabel = sourceLabel,
            importedEncrypted = importedEncrypted,
            fingerprintSourcePem = normalizedPem,
        )
    }

    fun saveMaterialBytes(
        type: SecureMaterialType,
        displayName: String,
        bytes: ByteArray,
        sourceLabel: String = displayName,
        importedEncrypted: Boolean = false,
    ): SecureMaterialSummary {
        val normalizedPem = normalizeAndValidate(type, bytes.toString(Charsets.UTF_8))
        return saveMaterialBytesInternal(
            type = type,
            displayName = displayName,
            storedBytes = normalizedPem.toByteArray(Charsets.UTF_8),
            sourceLabel = sourceLabel,
            importedEncrypted = importedEncrypted,
            fingerprintSourcePem = normalizedPem,
        )
    }

    fun deleteMaterial(id: String): SecureMaterialSummary? {
        if (id == DEFAULT_ANONYM_CA_ID) {
            return null
        }
        val existing = _userMaterials.value.firstOrNull { it.id == id } ?: return null
        fileFor(existing.id).delete()
        persistUserMaterials(_userMaterials.value.filterNot { it.id == id })
        return existing
    }

    private fun strings() = appStrings(appContext, AppSettingsStore.readLanguage(appContext))

    private fun saveMaterialBytesInternal(
        type: SecureMaterialType,
        displayName: String,
        storedBytes: ByteArray,
        sourceLabel: String,
        importedEncrypted: Boolean,
        fingerprintSourcePem: String,
    ): SecureMaterialSummary {
        val strings = strings()
        val normalizedName = displayName.trim().ifEmpty {
            if (type == SecureMaterialType.AnonymCa) strings.importedCaFallbackName else strings.importedAuthKeyFallbackName
        }
        val normalizedSource = sourceLabel.trim().ifEmpty { strings.importedLabel }
        val summary = SecureMaterialSummary(
            id = UUID.randomUUID().toString(),
            displayName = normalizedName,
            type = type,
            sourceLabel = normalizedSource,
            fingerprint = shortFingerprint(fingerprintSourcePem),
            importedEncrypted = importedEncrypted,
        )
        encryptToFile(fileFor(summary.id), storedBytes)
        val updated = listOf(summary) + _userMaterials.value
        persistUserMaterials(updated)
        return summary
    }

    private fun defaultAnonymCaSummary(): SecureMaterialSummary {
        val pem = normalizePem(readDefaultAnonymCaPem())
        val strings = strings()
        return SecureMaterialSummary(
            id = DEFAULT_ANONYM_CA_ID,
            displayName = strings.embeddedAnonymCaName,
            type = SecureMaterialType.AnonymCa,
            sourceLabel = strings.builtIn,
            fingerprint = shortFingerprint(pem),
            isDefault = true,
            createdAtEpochMs = 0L,
        )
    }

    private fun readDefaultAnonymCaPem(): String {
        return appContext.resources.openRawResource(R.raw.default_anonym_ca).bufferedReader().use { it.readText() }
    }

    private fun normalizeAndValidate(type: SecureMaterialType, rawPemText: String): String {
        val normalizedPem = normalizePem(rawPemText)
        if (normalizedPem.isBlank()) {
            throw IllegalArgumentException(strings().materialTextEmpty(strings().materialTypeLabel(type)))
        }
        when (type) {
            SecureMaterialType.AnonymCa -> validateCaPem(normalizedPem)
            SecureMaterialType.AuthKey -> validateAuthKeyPem(normalizedPem)
        }
        return normalizedPem
    }

    private fun validateCaPem(pem: String) {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)))
        if (certificates.isEmpty()) {
            throw IllegalArgumentException("PEM does not contain a valid X.509 certificate")
        }
    }

    private fun validateAuthKeyPem(pem: String) {
        YumeAuthKeyValidator.validatePrivateKeyPem(pem)
    }

    private fun normalizePem(rawPemText: String): String {
        val trimmed = rawPemText.trim()
        return if (trimmed.isEmpty()) "" else "$trimmed\n"
    }

    private fun loadUserMaterials(): List<SecureMaterialSummary> {
        migrateLegacyPrefsIfNeeded()
        val raw = metadataStore.getString(KEY_USER_MATERIALS_JSON).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val strings = strings()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val summary = SecureMaterialSummary.fromJson(array.getJSONObject(index))
                    if (summary != null) {
                        add(
                            summary.copy(
                                displayName = summary.displayName.ifBlank {
                                    if (summary.type == SecureMaterialType.AnonymCa) {
                                        strings.importedCaFallbackName
                                    } else {
                                        strings.importedAuthKeyFallbackName
                                    }
                                },
                                sourceLabel = summary.sourceLabel.ifBlank { strings.importedLabel },
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistUserMaterials(materials: List<SecureMaterialSummary>) {
        val payload = JSONArray().apply {
            materials.forEach { put(it.toJson()) }
        }
        metadataStore.putString(KEY_USER_MATERIALS_JSON, payload.toString())
        _userMaterials.value = materials
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (!metadataStore.isEmpty()) {
            return
        }
        val raw = legacyPrefs.getString(KEY_USER_MATERIALS_JSON, null) ?: return
        metadataStore.putString(KEY_USER_MATERIALS_JSON, raw)
        legacyPrefs.edit().clear().apply()
    }

    private fun encryptToFile(file: File, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(FILE_VERSION)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
        }
    }

    private fun decryptFromFile(file: File): ByteArray {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val version = input.readInt()
            if (version != FILE_VERSION) {
                throw IllegalStateException("Unsupported secure material version")
            }
            val ivSize = input.readInt()
            if (ivSize <= 0 || ivSize > 32) {
                throw IllegalStateException("Invalid secure material IV")
            }
            val iv = ByteArray(ivSize)
            input.readFully(iv)
            val ciphertextSize = input.readInt()
            if (ciphertextSize <= 0) {
                throw IllegalStateException("Invalid secure material payload")
            }
            val ciphertext = ByteArray(ciphertextSize)
            input.readFully(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            return cipher.doFinal(ciphertext)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Auth-key PEMs are decrypted while the device is already
            // unlocked so VPN reconnects can run without a per-connect
            // biometric prompt. Treat the device unlock state as the local
            // auth signal; changing this to true requires a UX flow that can
            // surface biometric confirmation before background decrypts.
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun fileFor(id: String): File = File(secureDir, "$id.bin")

    private fun unwrapLegacyStoredAuthKeyBytes(ciphertextPlaintext: ByteArray): ByteArray {
        if (!BaseFwxCompat.looksLikeRawFwxAes(ciphertextPlaintext)) {
            return ciphertextPlaintext
        }
        val password = AppVaultManager.derivePassword(appContext, AUTH_KEY_STORAGE_PURPOSE)
        val unwrapped = runCatching { BaseFwxCompat.fwxAesDecryptRaw(ciphertextPlaintext, password) }.getOrNull()
            ?: return ciphertextPlaintext
        val normalizedPem = runCatching {
            val candidate = normalizePem(unwrapped.toString(Charsets.UTF_8))
            validateAuthKeyPem(candidate)
            candidate
        }.getOrNull() ?: return ciphertextPlaintext
        return normalizedPem.toByteArray(Charsets.UTF_8)
    }

    private fun upgradeLegacyAuthKeyStorage(materials: List<SecureMaterialSummary>) {
        materials.asSequence()
            .filter { it.type == SecureMaterialType.AuthKey }
            .forEach { summary ->
                runCatching {
                    val file = fileFor(summary.id)
                    if (!file.isFile) {
                        return@runCatching
                    }
                    val stored = decryptFromFile(file)
                    val normalized = unwrapLegacyStoredAuthKeyBytes(stored)
                    if (!normalized.contentEquals(stored)) {
                        encryptToFile(file, normalized)
                    }
                }
            }
    }

    private fun readAuthKeyPem(summary: SecureMaterialSummary, storedBytes: ByteArray, userPassword: String?): String {
        val normalizedStoredBytes = unwrapLegacyStoredAuthKeyBytes(storedBytes)
        val plaintextPem = runCatching {
            val candidate = normalizePem(normalizedStoredBytes.toString(Charsets.UTF_8))
            validateAuthKeyPem(candidate)
            candidate
        }.getOrNull()
        if (!summary.importedEncrypted) {
            return plaintextPem ?: throw IllegalArgumentException("Stored auth key is not a PEM private key")
        }
        if (plaintextPem != null) {
            return plaintextPem
        }
        val normalizedPassword = userPassword?.trim().orEmpty()
        require(normalizedPassword.isNotEmpty()) { "Password required for encrypted auth key" }
        return AuthKeyImportResolver.resolveBytes(normalizedStoredBytes, encrypted = true, password = normalizedPassword)
    }

    private fun shortFingerprint(pemText: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pemText.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    companion object {
        const val DEFAULT_ANONYM_CA_ID = "embedded-anonym-ca"

        private const val PREFS_NAME = "yume_secure_materials"
        private const val KEY_USER_MATERIALS_JSON = "user_materials_json"
        private const val SECURE_DIR_NAME = "secure-materials"
        private const val VAULT_NAME = "secure_materials"
        private const val FILE_VERSION = 1
        private const val KEY_ALIAS = "yume_secure_materials_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AUTH_KEY_STORAGE_PURPOSE = "secure-material-auth-key"

        @Volatile
        private var instance: SecureMaterialStore? = null

        fun get(context: Context): SecureMaterialStore {
            return instance ?: synchronized(this) {
                instance ?: SecureMaterialStore(context).also { instance = it }
            }
        }
    }
}
