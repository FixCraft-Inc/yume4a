package com.fixcraft.yume.provider

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProviderProofTest {
    /** A software key with the same output contract as the Keystore key. */
    private class SoftwareKey : ProviderProofKey {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = pair.public as ECPublicKey
        override val x = ProviderProof.coordinate(public.w.affineX)
        override val y = ProviderProof.coordinate(public.w.affineY)
        override fun sign(input: ByteArray): ByteArray = ProviderProof.joseSignature(
            Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(input); sign() })
    }

    private val decoder = Base64.getUrlDecoder()
    private fun json(part: String) = JSONObject(String(decoder.decode(part), Charsets.UTF_8))

    @Test fun resourceProofBindsMethodUrlTimeTokenAndVerifiesWithThePublicKey() {
        val key = SoftwareKey()
        val proof = ProviderProof.create(key, "POST", "https://alpha.example/provider/v1/catalog", 1_800_000_000L, "ACCESS")
        val parts = proof.split('.')
        assertEquals(3, parts.size)
        val header = json(parts[0])
        assertEquals(setOf("typ", "alg", "jwk"), header.keys().asSequence().toSet())
        assertEquals("dpop+jwt", header.getString("typ"))
        assertEquals("ES256", header.getString("alg"))
        val jwk = header.getJSONObject("jwk")
        assertEquals(setOf("kty", "crv", "x", "y"), jwk.keys().asSequence().toSet())
        assertEquals(key.x, jwk.getString("x"))
        val claims = json(parts[1])
        assertEquals(setOf("jti", "htm", "htu", "iat", "ath"), claims.keys().asSequence().toSet())
        assertEquals("POST", claims.getString("htm"))
        assertEquals("https://alpha.example/provider/v1/catalog", claims.getString("htu"))
        assertEquals(1_800_000_000L, claims.getLong("iat"))
        assertEquals(43, claims.getString("jti").length)
        val ath = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("ACCESS".toByteArray()))
        assertEquals(ath, claims.getString("ath"))
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(key.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(decoder.decode(parts[2])))
        val tokenProof = json(ProviderProof.create(key, "POST", "https://login.alpha.example/token", 1L).split('.')[1])
        assertFalse(tokenProof.has("ath"))
        val again = json(ProviderProof.create(key, "POST", "https://alpha.example/provider/v1/catalog", 1L, "ACCESS").split('.')[1])
        assertNotEquals(claims.getString("jti"), again.getString("jti"))
    }

    @Test fun thumbprintIsTheRfc7638DigestOfTheRequiredMembers() {
        val key = SoftwareKey()
        val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${key.x}\",\"y\":\"${key.y}\"}"
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        assertEquals(expected, ProviderProof.thumbprint(key))
        assertEquals(43, ProviderProof.thumbprint(key).length)
        // Coordinates round-trip to the same public point, including leading zero bytes.
        val point = java.security.spec.ECPoint(BigInteger(1, decoder.decode(key.x)), BigInteger(1, decoder.decode(key.y)))
        val rebuilt = KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(point, key.public.params))
        assertEquals(key.public, rebuilt)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", ProviderProof.coordinate(BigInteger.ONE))
    }

    @Test fun derSignaturesConvertToFixedWidthAndMalformedInputIsRefused() {
        val r = ByteArray(33).also { it[1] = 0x80.toByte(); it[32] = 1 }
        val s = byteArrayOf(5)
        val der = byteArrayOf(0x30, (2 + r.size + 2 + s.size).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        val jose = ProviderProof.joseSignature(der)
        assertEquals(64, jose.size)
        assertArrayEquals(r.copyOfRange(1, 33), jose.copyOfRange(0, 32))
        assertEquals(5, jose[63].toInt())
        for (bad in listOf(byteArrayOf(), byteArrayOf(0x31, 0), der + 0, der.copyOf(der.size - 1),
            byteArrayOf(0x30, 38, 0x02, 34) + ByteArray(34).also { it[0] = 1 } + byteArrayOf(0x02, 0))) {
            assertThrows(ProviderCodec.Refusal::class.java) { ProviderProof.joseSignature(bad) }
        }
    }

    @Test fun onlyGetAndPostToCanonicalHttpsUrlsAreSigned() {
        val key = SoftwareKey()
        assertThrows(IllegalArgumentException::class.java) { ProviderProof.create(key, "PATCH", "https://alpha.example/x", 1L) }
        assertThrows(ProviderCodec.Refusal::class.java) { ProviderProof.create(key, "GET", "http://alpha.example/x", 1L) }
        assertThrows(ProviderCodec.Refusal::class.java) { ProviderProof.create(key, "GET", "https://alpha.example/x?y=1", 1L) }
    }
}
