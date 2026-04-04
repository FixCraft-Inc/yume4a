package com.fixcraft.yume.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.fixcraft.yume.R
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

enum class SecureMaterialType(val wireValue: String, val label: String) {
    AnonymCa("anonym_ca", "Anonym CA"),
    AuthKey("auth_key", "Auth key");

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
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val subtitle: String
        get() = buildString {
            append(sourceLabel)
            if (fingerprint.isNotBlank()) {
                append(" • ")
                append(fingerprint)
            }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("display_name", displayName)
        .put("type", type.wireValue)
        .put("source_label", sourceLabel)
        .put("fingerprint", fingerprint)
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
                displayName = json.optString("display_name").ifBlank {
                    if (type == SecureMaterialType.AnonymCa) "Imported CA" else "Imported auth key"
                },
                type = type,
                sourceLabel = json.optString("source_label").ifBlank { "Imported" },
                fingerprint = json.optString("fingerprint"),
                createdAtEpochMs = json.optLong("created_at_epoch_ms", System.currentTimeMillis()),
            )
        }
    }
}

class SecureMaterialStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureDir = File(appContext.noBackupFilesDir, SECURE_DIR_NAME).apply { mkdirs() }

    private val _userMaterials = MutableStateFlow(loadUserMaterials())
    val userMaterials: StateFlow<List<SecureMaterialSummary>> = _userMaterials.asStateFlow()

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
        return normalizePem(bytes.toString(Charsets.UTF_8))
    }

    fun saveMaterialText(
        type: SecureMaterialType,
        displayName: String,
        rawPemText: String,
        sourceLabel: String = displayName,
    ): SecureMaterialSummary {
        val normalizedName = displayName.trim().ifEmpty {
            if (type == SecureMaterialType.AnonymCa) "Imported CA" else "Imported auth key"
        }
        val normalizedSource = sourceLabel.trim().ifEmpty { "Imported" }
        val normalizedPem = normalizeAndValidate(type, rawPemText)
        val summary = SecureMaterialSummary(
            id = UUID.randomUUID().toString(),
            displayName = normalizedName,
            type = type,
            sourceLabel = normalizedSource,
            fingerprint = shortFingerprint(normalizedPem),
        )
        encryptToFile(fileFor(summary.id), normalizedPem.toByteArray(Charsets.UTF_8))
        val updated = listOf(summary) + _userMaterials.value
        persistUserMaterials(updated)
        return summary
    }

    fun saveMaterialBytes(
        type: SecureMaterialType,
        displayName: String,
        bytes: ByteArray,
        sourceLabel: String = displayName,
    ): SecureMaterialSummary {
        return saveMaterialText(type, displayName, bytes.toString(Charsets.UTF_8), sourceLabel)
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

    private fun defaultAnonymCaSummary(): SecureMaterialSummary {
        val pem = normalizePem(readDefaultAnonymCaPem())
        return SecureMaterialSummary(
            id = DEFAULT_ANONYM_CA_ID,
            displayName = "Embedded anonym CA",
            type = SecureMaterialType.AnonymCa,
            sourceLabel = "Built in",
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
            throw IllegalArgumentException("${type.label} text is empty")
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
        val beginPattern = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
        val endPattern = Regex("-----END [A-Z0-9 ]*PRIVATE KEY-----")
        if (!beginPattern.containsMatchIn(pem) || !endPattern.containsMatchIn(pem)) {
            throw IllegalArgumentException("PEM private key expected")
        }
    }

    private fun normalizePem(rawPemText: String): String {
        val trimmed = rawPemText.trim()
        return if (trimmed.isEmpty()) "" else "$trimmed\n"
    }

    private fun loadUserMaterials(): List<SecureMaterialSummary> {
        val raw = prefs.getString(KEY_USER_MATERIALS_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val summary = SecureMaterialSummary.fromJson(array.getJSONObject(index))
                    if (summary != null) {
                        add(summary)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistUserMaterials(materials: List<SecureMaterialSummary>) {
        val payload = JSONArray().apply {
            materials.forEach { put(it.toJson()) }
        }
        prefs.edit {
            putString(KEY_USER_MATERIALS_JSON, payload.toString())
        }
        _userMaterials.value = materials
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
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun fileFor(id: String): File = File(secureDir, "$id.bin")

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
        private const val FILE_VERSION = 1
        private const val KEY_ALIAS = "yume_secure_materials_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        @Volatile
        private var instance: SecureMaterialStore? = null

        fun get(context: Context): SecureMaterialStore {
            return instance ?: synchronized(this) {
                instance ?: SecureMaterialStore(context).also { instance = it }
            }
        }
    }
}
