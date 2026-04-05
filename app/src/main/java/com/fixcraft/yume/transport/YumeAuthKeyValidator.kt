package com.fixcraft.yume.transport

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.Provider

internal object YumeAuthKeyValidator {
    private val provider: Provider by lazy { BouncyCastleProvider() }
    private val pemConverter: JcaPEMKeyConverter by lazy {
        JcaPEMKeyConverter().setProvider(provider)
    }

    fun validatePrivateKeyPem(pem: String) {
        val pemObject = readPemObject(pem)
        val privateInfo: PrivateKeyInfo
        val publicInfo: SubjectPublicKeyInfo
        when (pemObject) {
            is PEMKeyPair -> {
                privateInfo = pemObject.privateKeyInfo
                publicInfo = pemObject.publicKeyInfo
            }

            is PrivateKeyInfo -> {
                privateInfo = pemObject
                publicInfo = derivePublicKeyInfo(privateInfo)
            }

            else -> {
                throw IllegalArgumentException("Unsupported auth key format. Import an unencrypted PEM private key.")
            }
        }

        checkNotNull(pemConverter.getPrivateKey(privateInfo)) { "Failed to load the auth private key" }
        checkNotNull(pemConverter.getPublicKey(publicInfo)) { "Failed to derive the auth public key" }
    }

    private fun derivePublicKeyInfo(privateInfo: PrivateKeyInfo): SubjectPublicKeyInfo {
        if (privateInfo.privateKeyAlgorithm.algorithm != EdECObjectIdentifiers.id_Ed25519) {
            throw IllegalArgumentException(
                "Android currently needs an Ed25519 auth key in PKCS#8 PEM format when only the private key is provided."
            )
        }
        val seed = ASN1OctetString.getInstance(privateInfo.parsePrivateKey()).octets
        val publicKeyParameters = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey()
        return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKeyParameters)
    }

    private fun readPemObject(pem: String): Any {
        PEMParser(StringReader(pem)).use { parser ->
            return parser.readObject() ?: throw IllegalArgumentException("PEM private key expected")
        }
    }
}
