package com.fixcraft.yume.provider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * One device-bound P-256 signing key. Its private half never leaves the
 * implementation. [sign] returns a raw 64-byte ES256 signature (R || S).
 */
interface ProviderProofKey {
    /** Unpadded base64url P-256 public coordinates, 32 bytes each. */
    val x: String
    val y: String
    fun sign(input: ByteArray): ByteArray
}

/** Owns one proof key per enrollment epoch. */
interface ProviderProofKeys {
    /** Replaces any key for [epoch] with a fresh one. */
    fun create(epoch: String): ProviderProofKey
    fun load(epoch: String): ProviderProofKey?
    fun delete(epoch: String)
    /** Deletes every owned key whose epoch is not in [epochs]. */
    fun retain(epochs: Set<String>)
}

/**
 * RFC 9449 DPoP proofs. A provider that requires `device-bound-enrollment-1`
 * binds the account token family to this key at code exchange. Token exchange,
 * refresh, catalog, grant, kit, opening-code and provider revocation carry a fresh
 * proof for its exact method and URL, so a copied token is useless without
 * the key.
 */
object ProviderProof {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun thumbprint(key: ProviderProofKey): String {
        val canonical = """{"crv":"P-256","kty":"EC","x":"${key.x}","y":"${key.y}"}"""
        return encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.US_ASCII)))
    }

    /** [accessToken] is null for token-endpoint requests and present for API requests. */
    fun create(
        key: ProviderProofKey,
        method: String,
        url: String,
        issuedAt: Long,
        accessToken: String? = null,
        entropy: SecureRandom = SecureRandom(),
    ): String {
        require(method == "GET" || method == "POST")
        ProviderCodec.endpoint(url)
        val header = linkedMapOf<String, Any?>("typ" to "dpop+jwt", "alg" to "ES256",
            "jwk" to linkedMapOf("kty" to "EC", "crv" to "P-256", "x" to key.x, "y" to key.y))
        val claims = linkedMapOf<String, Any?>("jti" to ProviderAuthorization.random(32, entropy), "htm" to method,
            "htu" to url, "iat" to issuedAt)
        accessToken?.let {
            claims["ath"] = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.US_ASCII)))
        }
        val input = encoder.encodeToString(ProviderCodec.encode(header)) + "." + encoder.encodeToString(ProviderCodec.encode(claims))
        val signature = key.sign(input.toByteArray(Charsets.US_ASCII))
        check(signature.size == 64) { "ES256 signatures are 64 bytes" }
        return input + "." + encoder.encodeToString(signature)
    }

    /** Converts a DER ECDSA signature to the fixed JOSE R || S form. */
    fun joseSignature(der: ByteArray): ByteArray {
        fun fail(): Nothing = throw ProviderCodec.Refusal("state")
        var at = 0
        fun byte(): Int = if (at < der.size) der[at++].toInt() and 0xff else fail()
        if (byte() != 0x30) fail()
        val total = byte()
        if (total and 0x80 != 0 || total != der.size - 2) fail()
        val out = ByteArray(64)
        for (half in 0..1) {
            if (byte() != 0x02) fail()
            val length = byte()
            if (length !in 1..33 || at + length > der.size) fail()
            var start = at
            var size = length
            while (size > 32 && der[start].toInt() == 0) {
                start++
                size--
            }
            if (size > 32) fail()
            System.arraycopy(der, start, out, half * 32 + 32 - size, size)
            at += length
        }
        if (at != der.size) fail()
        return out
    }

    internal fun coordinate(value: BigInteger): String {
        val raw = value.toByteArray()
        val start = maxOf(0, raw.size - 32)
        check(raw.copyOfRange(0, start).all { it.toInt() == 0 })
        val fixed = ByteArray(32)
        System.arraycopy(raw, start, fixed, 32 - (raw.size - start), raw.size - start)
        return encoder.encodeToString(fixed)
    }
}

/**
 * Non-exportable Android Keystore keys. They need no user authentication.
 * Consumer access hooks (for example Ito's app lock) admit provider work before
 * any request is signed, and the key adds no hardware-authentication claim.
 */
class AndroidProviderProofKeys : ProviderProofKeys {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun create(epoch: String): ProviderProofKey = synchronized(lock) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(KeyGenParameterSpec.Builder(alias(epoch), KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build())
        generator.generateKeyPair()
        loadLocked(epoch) ?: throw ProviderCodec.Refusal("storage")
    }

    override fun load(epoch: String): ProviderProofKey? = synchronized(lock) { loadLocked(epoch) }

    override fun delete(epoch: String) = synchronized(lock) { keyStore().deleteEntry(alias(epoch)) }

    override fun retain(epochs: Set<String>) = synchronized(lock) {
        val keep = epochs.map(::alias).toSet()
        val keys = keyStore()
        keys.aliases().toList().filter { it.startsWith(PREFIX) && it !in keep }.forEach(keys::deleteEntry)
    }

    private fun loadLocked(epoch: String): ProviderProofKey? {
        val entry = keyStore().getEntry(alias(epoch), null) as? KeyStore.PrivateKeyEntry ?: return null
        val public = entry.certificate.publicKey as? ECPublicKey ?: return null
        return KeystoreKey(entry.privateKey, ProviderProof.coordinate(public.w.affineX), ProviderProof.coordinate(public.w.affineY))
    }

    private class KeystoreKey(private val key: PrivateKey, override val x: String, override val y: String) : ProviderProofKey {
        override fun sign(input: ByteArray): ByteArray {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(key)
            signer.update(input)
            return ProviderProof.joseSignature(signer.sign())
        }
        override fun toString() = "ProviderProofKey(redacted)"
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val PREFIX = "yume-provider-proof-"
        val lock = Any()
        val EPOCH = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun alias(epoch: String): String {
            require(EPOCH.matches(epoch))
            return PREFIX + epoch
        }
    }
}
