package com.fixcraft.yume.transport

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.util.Arrays
import java.util.Base64

/**
 * Validates a YUME AUTH v2 composite private identity.
 *
 * A dev6 identity is *exactly two* consecutive PEM private-key blocks: classical
 * Ed25519 first, then ML-DSA-87. This mirrors `load_composite_keypair()` in
 * `src/core/security/crypto.cpp`, which enforces the same count, order and
 * algorithms and refuses anything else.
 *
 * The previous implementation read only the first PEM object and explicitly
 * derived an Ed25519 public key, so it accepted a bare Ed25519 key that the
 * server rejects, silently ignored a second block, and never checked order or
 * count. Enrolling such a key produced a client that failed at authentication
 * with no local diagnosis.
 */
internal object YumeAuthKeyValidator {

    // Deliberately inspects the PKCS#8 algorithm OID and does not call
    // JcaPEMKeyConverter.getPrivateKey(). The pinned BouncyCastle (1.78.1)
    // predates FIPS 204 final, so materialising an ML-DSA-87 key would throw
    // even for a perfectly valid identity. Reading PrivateKeyInfo needs no
    // algorithm support, and the native client -- not the JVM -- is what
    // actually uses this key. Do not "fix" this by converting the key.

    /** ML-DSA-87, as published in the NIST FIPS 204 OID arc. */
    private val ML_DSA_87 = ASN1ObjectIdentifier("2.16.840.1.101.3.4.3.19")
    private const val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----"
    private const val END_PRIVATE_KEY = "-----END PRIVATE KEY-----"
    private const val MAX_IDENTITY_CHARS = 128 * 1024

    fun validatePrivateKeyPem(pem: String) {
        val blocks = readAllPrivateKeyBlocks(pem)

        require(blocks.size == 2) {
            "A YUME auth key is a composite identity: exactly two PEM private " +
                "key blocks, Ed25519 followed by ML-DSA-87. Found " +
                "${blocks.size}. Generate one with `yume --keygen` on the " +
                "desktop; a bare Ed25519 key is refused by the server."
        }

        val classical = blocks[0].privateKeyAlgorithm.algorithm
        require(classical == EdECObjectIdentifiers.id_Ed25519) {
            "Composite identity: the first PEM block must be Ed25519, got " +
                "$classical."
        }

        val pq = blocks[1].privateKeyAlgorithm.algorithm
        require(pq == ML_DSA_87) {
            "Composite identity: the second PEM block must be ML-DSA-87, got " +
                "$pq. Legacy Dilithium5 keys are not accepted by desktop dev6."
        }
    }

    /**
     * Reads every PEM object in the file.
     *
     * Anything that is not an unencrypted private key -- a certificate, a public
     * key, trailing junk, or an encrypted block -- is an error rather than
     * something to skip past, because "exactly two blocks" cannot be checked
     * while quietly discarding others.
     */
    private fun readAllPrivateKeyBlocks(pem: String): List<PrivateKeyInfo> {
        require(pem.length <= MAX_IDENTITY_CHARS) { "Auth key file is too large" }
        val blocks = mutableListOf<PrivateKeyInfo>()
        var cursor = 0
        while (true) {
            val begin = pem.indexOf(BEGIN_PRIVATE_KEY, cursor)
            if (begin < 0) break
            require(pem.substring(cursor, begin).all(Char::isWhitespace)) {
                "Auth key contains leading, trailing, or inter-block junk"
            }
            val bodyStart = begin + BEGIN_PRIVATE_KEY.length
            val end = pem.indexOf(END_PRIVATE_KEY, bodyStart)
            require(end >= 0) { "Auth key has an unterminated PRIVATE KEY block" }
            val body = pem.substring(bodyStart, end)
            require(body.isNotBlank() && body.all { it.isWhitespace() || it.isBase64Character() }) {
                "Auth key contains invalid PKCS#8 PEM data"
            }
            val encoded = body.filterNot(Char::isWhitespace)
            val der = try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Auth key contains invalid base64", error)
            }
            try {
                blocks += PrivateKeyInfo.getInstance(der)
            } catch (error: Exception) {
                throw IllegalArgumentException("Auth key contains malformed PKCS#8 DER", error)
            } finally {
                Arrays.fill(der, 0)
            }
            cursor = end + END_PRIVATE_KEY.length
            if (blocks.size > 2) break
        }
        require(pem.substring(cursor).all(Char::isWhitespace)) {
            "Auth key contains leading, trailing, or inter-block junk"
        }
        require(blocks.isNotEmpty()) { "PKCS#8 PEM private key expected" }
        return blocks
    }

    private fun Char.isBase64Character(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '+' || this == '/' || this == '='
}
