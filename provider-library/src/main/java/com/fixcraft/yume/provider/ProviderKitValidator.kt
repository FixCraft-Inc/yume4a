package com.fixcraft.yume.provider

import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Validates a provider bundle without importing it into the manual kit store.
 * The opening code is a separate caller-owned handoff, never fetched here.
 */
class ProviderKitValidator(private val native: Native) : ProviderKitAdapter {
    /**
     * Must validate the referenced native credentials and derive the local
     * composite fingerprint from the actual private key through native code.
     * Config parsing and a hash of an accompanying public file do not do that.
     * There is deliberately no permissive default implementation.
     */
    interface Native {
        /** Strict native operations throw a typed refusal on unsupported/malformed input. */
        fun openKit(sealed: ByteArray, code: ByteArray): List<ProviderFile>
        fun checkClientConfig(config: ByteArray)
        /** Enforce this consumer's exact service and local-adapter policy after native schema validation. */
        fun checkConsumerConfig(config: ByteArray)
        fun fingerprint(files: List<ProviderFile>, originalConfig: ByteArray): String
    }

    /** The owner encrypts [bundle], then closes this value. Never execute kit files. */
    class ValidatedKit internal constructor(override val bundle: ByteArray) : ProviderBundle, Closeable {
        override fun close() { bundle.fill(0) }
        override fun toString(): String = "ValidatedKit([redacted])"
    }

    /** Wipes [openingCode] on every outcome; [sealed] remains owned by the caller. */
    override fun validate(
        sealed: ByteArray,
        openingCode: CharArray,
        grant: ProviderCodec.Grant,
        server: ProviderCodec.Server,
    ): ValidatedKit {
        var code: ByteArray? = null
        try {
            refuseUnless(grant.request.serverId == server.id, "binding")
            refuseUnless(sealed.size in MIN_SEALED_BYTES..MAX_SEALED_BYTES && (sealed.size - 44) % 1024 == 0, "malformed")
            refuseUnless(sealed.size == grant.kitSize && sha256(sealed) == grant.kitSha256, "binding")
            refuseUnless(openingCode.size in 25..64 && openingCode.all { it.code in 0x20..0x7e }, "malformed")
            code = ByteArray(openingCode.size) { openingCode[it].code.toByte() }
            openingCode.fill('\u0000')
            val files = native.openKit(sealed, code)
            try {
                val bundle = validateOpened(files, grant.clientIdentity, server)
                try {
                    return ValidatedKit(bundle)
                } catch (error: Throwable) {
                    bundle.fill(0)
                    throw error
                }
            } finally {
                files.forEach { it.bytes.fill(0) }
            }
        } finally {
            code?.fill(0)
            openingCode.fill('\u0000')
        }
    }

    /** Validates opened contained files through the same mandatory native boundary. */
    fun validateOpened(
        files: List<ProviderFile>,
        expectedIdentity: String,
        server: ProviderCodec.Server,
    ): ByteArray {
        refuseUnless(ProviderBundleCodec.problem(files) == null, "malformed")
        refuseUnless(DIGEST.matches(expectedIdentity) && DIGEST.matches(server.serverKeySha256), "malformed")
        val indexed = files.associateBy { it.path }
        val configFile = indexed.getValue(ProviderBundleCodec.CONFIG_PATH)
        refuseUnless(!configFile.executable, "unsupported")
        val config = configFile.bytes
        try {
            native.checkClientConfig(config)
            // The native parser owns the schema. Read only app constraints after it accepts.
            val root = JSONObject(String(config, Charsets.UTF_8))
            val endpoint = root.getJSONObject("endpoint")
            refuseUnless(!root.has("circuits") && !endpoint.has("socks5_proxy") && !endpoint.has("connect_address"), "unsupported")
            refuseUnless(endpoint.getString("host") == server.host && endpoint.getInt("port") == server.port, "binding")

            val credentials = root.getJSONObject("credentials")
            val references = CREDENTIALS.associateWith { key ->
                val reference = credentials.getJSONObject(key)
                refuseUnless(reference.length() == 1 && reference.has("file"), "unsupported")
                val path = reference.getString("file")
                refuseUnless(ProviderBundleCodec.isSafePath(path) && path != ProviderBundleCodec.CONFIG_PATH, "unsupported")
                val file = indexed[path] ?: throw ProviderCodec.Refusal("binding")
                // An opened kit has bytes, not links or special files. No reference is
                // resolved against the app filesystem, and no credential is executable.
                refuseUnless(!file.executable, "unsupported")
                file
            }
            refuseUnless(references.values.map { it.path }.distinct().size == CREDENTIALS.size, "unsupported")
            refuseUnless(sha256(references.getValue("server_identity").bytes) == server.serverKeySha256, "binding")
            val access = references.getValue("access_psk").bytes
            val admission = references.getValue("admission_key").bytes
            refuseUnless(access.size == 32 && admission.size == 32, "malformed")
            refuseUnless(!MessageDigest.isEqual(access, admission), "binding")

            native.checkConsumerConfig(config)
            val actualIdentity = try {
                native.fingerprint(files, config)
            } catch (refusal: ProviderCodec.Refusal) {
                throw refusal
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw ProviderCodec.Refusal("unsupported")
            }
            refuseUnless(actualIdentity == expectedIdentity, "binding")
            return ProviderBundleCodec.encode(files)
        } catch (_: JSONException) {
            throw ProviderCodec.Refusal("malformed")
        }
    }

    private fun refuseUnless(condition: Boolean, code: String) {
        if (!condition) throw ProviderCodec.Refusal(code)
    }

    companion object {
        const val MIN_SEALED_BYTES = 1068
        const val MAX_SEALED_BYTES = 1_049_648
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val CREDENTIALS = listOf("composite_key", "access_psk", "admission_key", "server_trust", "server_identity", "server_mlkem")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
