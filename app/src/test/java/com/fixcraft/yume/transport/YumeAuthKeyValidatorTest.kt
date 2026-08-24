package com.fixcraft.yume.transport

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class YumeAuthKeyValidatorTest {
    @Test
    fun acceptsDesktopGeneratedDev6CompositeIdentity() {
        YumeAuthKeyValidator.validatePrivateKeyPem(fixture())
    }

    @Test
    fun rejectsWrongCountAndExtraBlocks() {
        val blocks = privateKeyBlocks(fixture())
        assertRejected(blocks.first())
        assertRejected(blocks.joinToString("\n") + "\n" + blocks.first())
    }

    @Test
    fun rejectsWrongOrderAndAlgorithm() {
        val blocks = privateKeyBlocks(fixture())
        assertRejected(blocks.reversed().joinToString("\n"))
        assertRejected(pkcs8Pem(RSA) + "\n" + blocks[1])
        assertRejected(blocks[0] + "\n" + pkcs8Pem(RSA))
    }

    @Test
    fun rejectsLegacyDilithium5OidsThatDesktopRejects() {
        for (oid in LEGACY_DILITHIUM5) {
            assertRejected(pkcs8Pem(EdECObjectIdentifiers.id_Ed25519) + "\n" + pkcs8Pem(oid))
        }
    }

    @Test
    fun rejectsEncryptedMalformedAndNonPkcs8Pem() {
        assertRejected(
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----",
        )
        assertRejected(
            "-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----\n" +
                pkcs8Pem(ML_DSA_87),
        )
        assertRejected(
            "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n" +
                pkcs8Pem(ML_DSA_87),
        )
        assertRejected(
            "-----BEGIN RSA PRIVATE KEY-----\nAA==\n-----END RSA PRIVATE KEY-----\n" +
                pkcs8Pem(ML_DSA_87),
        )
    }

    @Test
    fun rejectsLeadingTrailingAndInterBlockJunk() {
        val blocks = privateKeyBlocks(fixture())
        assertRejected("junk\n" + blocks.joinToString("\n"))
        assertRejected(blocks.joinToString("\n") + "\njunk")
        assertRejected(blocks[0] + "\njunk\n" + blocks[1])
    }

    private fun fixture(): String = requireNotNull(
        javaClass.getResource("/fixtures/dev6-composite-identity.key"),
    ).readText()

    private fun assertRejected(pem: String) {
        assertThrows(IllegalArgumentException::class.java) {
            YumeAuthKeyValidator.validatePrivateKeyPem(pem)
        }
    }

    private fun privateKeyBlocks(pem: String): List<String> =
        PRIVATE_KEY_BLOCK.findAll(pem).map(MatchResult::value).toList()

    private fun pkcs8Pem(oid: ASN1ObjectIdentifier): String {
        val der = PrivateKeyInfo(
            AlgorithmIdentifier(oid),
            DEROctetString(byteArrayOf(1, 2, 3, 4)),
        ).encoded
        val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        der.fill(0)
        return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----"
    }

    companion object {
        private val PRIVATE_KEY_BLOCK = Regex(
            "-----BEGIN PRIVATE KEY-----[\\s\\S]*?-----END PRIVATE KEY-----",
        )
        private val ML_DSA_87 = ASN1ObjectIdentifier("2.16.840.1.101.3.4.3.19")
        private val RSA = ASN1ObjectIdentifier("1.2.840.113549.1.1.1")
        private val LEGACY_DILITHIUM5 = listOf(
            ASN1ObjectIdentifier("1.3.6.1.4.1.2.267.7.8.7"),
            ASN1ObjectIdentifier("1.3.6.1.4.1.2.267.12.8.7"),
        )
    }
}
