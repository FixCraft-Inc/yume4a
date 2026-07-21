/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.share

import com.fixcraft.basefwx.FwxAES
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Kotlin codec for the .yss ("yume secure store") file format.
 * Byte-compatible with the C++ implementation in
 * [src/client/share_file.cpp] — a file exported on desktop can be
 * imported on Android and vice versa.
 *
 * Wire format:
 *   [ 8 bytes ] "YUMESHRE"                    magic
 *   [ 1 byte  ] version (= 1)
 *   [ 1 byte  ] bundle type (0 = backup)
 *   [ 2 bytes ] reserved (= 0)
 *   [ N bytes ] FwxAES blob                   (BaseFWX Argon2id + AES-GCM
 *                                              of the inner JSON document)
 *
 * The header is the only unencrypted material. The FwxAES blob
 * carries its own salt + nonce + auth tag, so wrong-password attempts
 * fail at the GCM tag check (no plaintext leaks).
 */
object ShareFile {

    const val MAGIC = "YUMESHRE"
    const val VERSION: Byte = 1
    const val TYPE_BACKUP: Byte = 0
    private const val HEADER_LEN = 12

    /** Public bundle shape — schema matches the C++ ShareBundle 1:1. */
    data class Bundle(
        val type: Byte = TYPE_BACKUP,
        val createdAtIso8601: String = "",
        val createdBy: String = "",
        val label: String = "",
        // server endpoint (required)
        val serverHost: String,
        val serverPort: Int = 443,
        // auth (required for Backup)
        val authPrivateKeyPem: String = "",
        // stealth
        val obfuscation: Boolean = true,
        val obfsSecret: String = "",
        val obfsPadMultiple: Int = 0,
        val obfsJitterMs: Int = 0,
        val tlsPinSha256: String = "",
        val tlsStealthProfile: String = "",
        // Operator-identity trust material + PQ (legacy serialized field names).
        val anonymCaCertPem: String = "",
        val anonymPubkey: String = "",
        val pqPublicKeyPem: String = "",
        // client recommendations
        val innerCrypto: Boolean = true,
        val innerHeavy: Boolean = true,
        val innerHop: Boolean = true,
        val innerPsk: String = "",
        val hopIntervalMs: Int = 500,
        val allowUdp: Boolean = false,
        val allowLocalIp: Boolean = false,
    )

    /** Header peek (no decryption needed). */
    data class Header(val version: Int, val type: Int)

    /**
     * Peek the unencrypted header. Returns null if the blob isn't a
     * .yss file or the version is unsupported — caller should
     * surface a "not a .yss file" message before prompting for
     * a password.
     */
    fun peekHeader(blob: ByteArray): Header? {
        if (blob.size < HEADER_LEN) return null
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magicBytes.indices) {
            if (blob[i] != magicBytes[i]) return null
        }
        val version = blob[8].toInt() and 0xFF
        if (version != VERSION.toInt()) return null
        val type = blob[9].toInt() and 0xFF
        return Header(version = version, type = type)
    }

    /**
     * Encrypt the bundle into a complete file body (header + FwxAES
     * blob). Throws IllegalArgumentException on bad inputs (missing
     * required fields, empty password). The FwxAES path can throw
     * its own exceptions on internal failure; surface them as-is so
     * the caller can log.
     */
    fun encode(bundle: Bundle, password: String): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(bundle.serverHost.isNotBlank()) { "bundle missing server host" }
        require(bundle.serverPort in 1..65535) { "bundle has invalid server port" }
        // No auth-key requirement here: an info-only backup (server
        // endpoint + obfs secret + CA cert, no private key) is valid.
        // The downstream importer flags "no auth key — you'll need
        // to add one" in its summary UI.
        val plaintext = bundleToJson(bundle).toString().toByteArray(Charsets.UTF_8)
        val fwx = FwxAES.create()
        val encrypted = fwx.encryptRaw(plaintext, password)
        val out = ByteArray(HEADER_LEN + encrypted.size)
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, out, 0, magic.size)
        out[8] = VERSION
        out[9] = bundle.type
        out[10] = 0
        out[11] = 0
        System.arraycopy(encrypted, 0, out, HEADER_LEN, encrypted.size)
        return out
    }

    /**
     * Decrypt the file body into a Bundle. Throws DecryptException
     * on wrong password / tampered file / malformed payload. Caller
     * should peekHeader() first to short-circuit the "not a share
     * file at all" case without prompting.
     */
    @Throws(DecryptException::class)
    fun decode(blob: ByteArray, password: String): Bundle {
        if (blob.size < HEADER_LEN) {
            throw DecryptException("file too small to be a .yss blob")
        }
        if (peekHeader(blob) == null) {
            throw DecryptException("not a .yss file (bad magic or unsupported version)")
        }
        if (password.isEmpty()) {
            throw DecryptException("password must not be empty")
        }
        val encrypted = blob.copyOfRange(HEADER_LEN, blob.size)
        val fwx = FwxAES.create()
        val plaintext = try {
            fwx.decryptRaw(encrypted, password)
        } catch (t: Throwable) {
            throw DecryptException("decrypt failed (wrong password or corrupted file): ${t.message}", t)
        }
        val json = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (t: Throwable) {
            throw DecryptException("payload is not JSON: ${t.message}", t)
        }
        return jsonToBundle(json)
    }

    /** Caller's "now" for created_at. */
    fun isoNowUtc(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun bundleToJson(b: Bundle): JSONObject {
        val out = JSONObject()
        out.put("magic", "yume-share")
        out.put("version", VERSION.toInt())
        out.put("type", if (b.type == TYPE_BACKUP) "backup" else "unknown")
        if (b.createdAtIso8601.isNotEmpty()) out.put("created_at", b.createdAtIso8601)
        if (b.createdBy.isNotEmpty())          out.put("created_by", b.createdBy)
        if (b.label.isNotEmpty())              out.put("label", b.label)

        out.put("server", JSONObject()
            .put("host", b.serverHost)
            .put("port", b.serverPort))

        if (b.authPrivateKeyPem.isNotEmpty()) {
            out.put("auth", JSONObject().put("private_key_pem", b.authPrivateKeyPem))
        }

        val stealth = JSONObject().put("obfuscation", b.obfuscation)
        if (b.obfsSecret.isNotEmpty())       stealth.put("obfs_secret", b.obfsSecret)
        if (b.obfsPadMultiple > 0)            stealth.put("obfs_pad_multiple", b.obfsPadMultiple)
        if (b.obfsJitterMs > 0)               stealth.put("obfs_jitter_ms", b.obfsJitterMs)
        if (b.tlsPinSha256.isNotEmpty())     stealth.put("tls_pin_sha256", b.tlsPinSha256)
        if (b.tlsStealthProfile.isNotEmpty()) stealth.put("tls_stealth_profile", b.tlsStealthProfile)
        out.put("stealth", stealth)

        if (b.anonymCaCertPem.isNotEmpty() || b.anonymPubkey.isNotEmpty()) {
            val anonym = JSONObject()
            if (b.anonymCaCertPem.isNotEmpty()) anonym.put("ca_cert_pem", b.anonymCaCertPem)
            if (b.anonymPubkey.isNotEmpty())   anonym.put("pubkey", b.anonymPubkey)
            out.put("anonym", anonym)
        }
        if (b.pqPublicKeyPem.isNotEmpty()) {
            out.put("pq", JSONObject().put("public_key_pem", b.pqPublicKeyPem))
        }

        out.put("client_settings", JSONObject()
            .put("inner_crypto",   b.innerCrypto)
            .put("inner_heavy",    b.innerHeavy)
            .put("inner_hop",      b.innerHop)
            .put("inner_psk",      b.innerPsk)
            .put("hop_interval_ms", b.hopIntervalMs)
            .put("allow_udp",      b.allowUdp)
            .put("allow_local_ip", b.allowLocalIp))
        return out
    }

    private fun jsonToBundle(j: JSONObject): Bundle {
        if (j.optString("magic") != "yume-share") {
            throw DecryptException("share bundle missing or wrong 'magic' field")
        }
        val server = j.optJSONObject("server")
            ?: throw DecryptException("share bundle missing 'server' object")
        val host = server.optString("host").ifBlank {
            throw DecryptException("share bundle missing server.host")
        }
        val port = server.optInt("port", 0)
        if (port !in 1..65535) {
            throw DecryptException("share bundle has invalid server port")
        }
        val typeStr = j.optString("type", "backup")
        val typeByte: Byte = if (typeStr == "backup") TYPE_BACKUP else TYPE_BACKUP

        val auth = j.optJSONObject("auth")
        val stealth = j.optJSONObject("stealth")
        val anonym = j.optJSONObject("anonym")
        val pq = j.optJSONObject("pq")
        val cs = j.optJSONObject("client_settings")

        return Bundle(
            type = typeByte,
            createdAtIso8601 = j.optString("created_at"),
            createdBy = j.optString("created_by"),
            label = j.optString("label"),
            serverHost = host,
            serverPort = port,
            authPrivateKeyPem = auth?.optString("private_key_pem").orEmpty(),
            obfuscation        = stealth?.optBoolean("obfuscation", true) ?: true,
            obfsSecret         = stealth?.optString("obfs_secret").orEmpty(),
            obfsPadMultiple    = stealth?.optInt("obfs_pad_multiple", 0) ?: 0,
            obfsJitterMs       = stealth?.optInt("obfs_jitter_ms", 0) ?: 0,
            tlsPinSha256       = stealth?.optString("tls_pin_sha256").orEmpty(),
            tlsStealthProfile  = stealth?.optString("tls_stealth_profile").orEmpty(),
            anonymCaCertPem    = anonym?.optString("ca_cert_pem").orEmpty(),
            anonymPubkey       = anonym?.optString("pubkey").orEmpty(),
            pqPublicKeyPem     = pq?.optString("public_key_pem").orEmpty(),
            innerCrypto        = cs?.optBoolean("inner_crypto", true) ?: true,
            innerHeavy         = cs?.optBoolean("inner_heavy", true) ?: true,
            innerHop           = cs?.optBoolean("inner_hop", true) ?: true,
            innerPsk           = cs?.optString("inner_psk").orEmpty(),
            hopIntervalMs      = cs?.optInt("hop_interval_ms", 500) ?: 500,
            allowUdp           = cs?.optBoolean("allow_udp", false) ?: false,
            allowLocalIp       = cs?.optBoolean("allow_local_ip", false) ?: false,
        )
    }

    class DecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
