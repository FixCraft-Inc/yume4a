package com.fixcraft.yume.transport

import com.fixcraft.basefwx.Base64Codec
import com.fixcraft.basefwx.Codec
import com.fixcraft.basefwx.Constants
import com.fixcraft.yume.crypto.BaseFwxCompat
import java.nio.charset.StandardCharsets

internal object AuthKeyImportResolver {
    fun resolveText(content: String, encrypted: Boolean, password: String): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val normalized = normalizePemCandidate(content)
        return resolveCandidate(
            plainAttempt = { validatePem(normalized) },
            encryptedBytes = bytes,
            encryptedText = content,
            encryptedHint = encrypted,
            password = password,
        )
    }

    fun resolveBytes(bytes: ByteArray, encrypted: Boolean, password: String): String {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
        val normalized = runCatching { validatePem(normalizePemCandidate(text.orEmpty())) }.getOrNull()
        return resolveCandidate(
            plainAttempt = { normalized ?: throw IllegalArgumentException("PEM private key expected") },
            encryptedBytes = bytes,
            encryptedText = text,
            encryptedHint = encrypted,
            password = password,
        )
    }

    private fun resolveCandidate(
        plainAttempt: () -> String,
        encryptedBytes: ByteArray,
        encryptedText: String?,
        encryptedHint: Boolean,
        password: String,
    ): String {
        runCatching(plainAttempt).getOrNull()?.let { return it }
        if (password.isNotBlank()) {
            return decryptToPem(
                encryptedBytes = encryptedBytes,
                encryptedText = encryptedText,
                password = password,
            )
        }
        if (encryptedHint || looksEncrypted(encryptedBytes, encryptedText)) {
            throw IllegalArgumentException("Password required for encrypted auth key")
        }
        throw IllegalArgumentException("Unsupported auth key format. Import a PEM private key.")
    }

    private fun decryptToPem(
        encryptedBytes: ByteArray,
        encryptedText: String?,
        password: String,
    ): String {
        require(password.isNotBlank()) { "Password required" }
        val attempts = buildList {
            if (BaseFwxCompat.looksLikeRawFwxAes(encryptedBytes)) {
                add { String(BaseFwxCompat.fwxAesDecryptRaw(encryptedBytes, password), StandardCharsets.UTF_8) }
            }
            encryptedText?.trim()?.takeIf { it.isNotEmpty() }?.let { rawText ->
                add {
                    val decoded = Base64Codec.decode(rawText)
                    require(BaseFwxCompat.looksLikeRawFwxAes(decoded)) { "Not raw fwxAES" }
                    String(BaseFwxCompat.fwxAesDecryptRaw(decoded, password), StandardCharsets.UTF_8)
                }
                add {
                    val decoded = Base64Codec.decode(Codec.decode(rawText))
                    require(BaseFwxCompat.looksLikeRawFwxAes(decoded)) { "Not raw fwxAES" }
                    String(BaseFwxCompat.fwxAesDecryptRaw(decoded, password), StandardCharsets.UTF_8)
                }
                add { BaseFwxCompat.fwxAesDecryptText(rawText, password) }
                add { BaseFwxCompat.fwxAesHeavyDecryptText(rawText, password) }
                add { BaseFwxCompat.pb512Decode(rawText, password) }
                add { BaseFwxCompat.b512Decode(rawText, password) }
            }
            add { String(BaseFwxCompat.pb512FileDecodeBytes(encryptedBytes, password).data, StandardCharsets.UTF_8) }
            add { String(BaseFwxCompat.b512FileDecodeBytes(encryptedBytes, password).data, StandardCharsets.UTF_8) }
        }
        attempts.forEach { attempt ->
            val decrypted = runCatching(attempt).getOrNull() ?: return@forEach
            val pem = runCatching { validatePem(normalizePemCandidate(decrypted)) }.getOrNull()
            if (pem != null) {
                return pem
            }
        }
        throw IllegalArgumentException("Bad password or unsupported encrypted auth key")
    }

    private fun validatePem(candidate: String): String {
        YumeAuthKeyValidator.validatePrivateKeyPem(candidate)
        return candidate
    }

    private fun normalizePemCandidate(rawText: String): String {
        val trimmed = rawText.trim()
        require(trimmed.isNotEmpty()) { "PEM private key expected" }
        return "$trimmed\n"
    }

    private fun looksEncrypted(bytes: ByteArray, text: String?): Boolean {
        if (BaseFwxCompat.looksLikeRawFwxAes(bytes)) {
            return true
        }
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return false
        }
        if (normalized.contains("-----BEGIN")) {
            return false
        }
        val decoded = runCatching { Base64Codec.decode(normalized) }.getOrNull()
        return decoded?.let(BaseFwxCompat::looksLikeRawFwxAes) == true ||
            normalized.length > Constants.FWXAES_MAGIC.size
    }
}
