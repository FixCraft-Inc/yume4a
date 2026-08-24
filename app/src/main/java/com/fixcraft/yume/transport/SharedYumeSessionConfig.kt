package com.fixcraft.yume.transport

import android.system.Os
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.TransportProfileRegistry
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SharedYumeSessionConfig private constructor(
    val directory: File,
    val json: String,
) : Closeable {
    override fun close() {
        unregisterActive(directory)
        // Delete only the four files created inside this session-owned random
        // directory. Never recursively delete a shared application path.
        listOf(IDENTITY_FILE, TLS_CA_FILE, ADMISSION_FILE, INNER_PSK_FILE)
            .forEach { name -> runCatching { File(directory, name).delete() } }
        runCatching { directory.delete() }
    }

    companion object {
        private const val IDENTITY_FILE = "identity.key"
        private const val TLS_CA_FILE = "tls-ca.pem"
        private const val ADMISSION_FILE = "admission.secret"
        private const val INNER_PSK_FILE = "inner.psk"
        private val HEX_32 = Regex("^[0-9a-f]{64}$")
        private val activeSessionPaths = mutableSetOf<String>()

        fun create(
            noBackupFilesDir: File,
            profile: ServerProfile,
            dialAddress: String,
            tlsServerName: String,
            authKeyPem: String,
            operatorCaPem: String,
        ): SharedYumeSessionConfig {
            require(dialAddress.isNotBlank()) { "The resolved YUME server address is empty" }
            require(tlsServerName.isNotBlank()) {
                "TLS server name is required when dialing a numeric address"
            }
            require(profile.obfuscation) { "YUME 2.0 requires the authenticated HTTP/2 carrier" }
            require(HEX_32.matches(profile.obfsSecret)) {
                "YUME 2.0 admission secret must be exactly 64 lowercase hex characters"
            }
            require(HEX_32.matches(profile.innerPsk)) {
                "YUME 2.0 inner PSK must be exactly 64 lowercase hex characters"
            }
            require(profile.tlsPinSha256.isEmpty() || HEX_32.matches(profile.tlsPinSha256)) {
                "TLS pin must be exactly 64 lowercase hex characters"
            }
            require(authKeyPem.isNotBlank()) { "The client identity key is empty" }
            require(!profile.requireAnonymProof || operatorCaPem.isNotBlank()) {
                "Required operator authorization needs an operator CA"
            }

            val parent = File(noBackupFilesDir, "yume-native-sessions").apply {
                check(isDirectory || mkdirs()) { "Could not create the native YUME session directory" }
                Os.chmod(absolutePath, 0x1C0) // 0700
            }
            cleanupStaleSessions(parent)
            val directory = File(parent, UUID.randomUUID().toString()).apply {
                check(mkdir()) { "Could not create a private native YUME session" }
                Os.chmod(absolutePath, 0x1C0) // 0700
            }
            registerActive(directory)
            try {
                writePrivate(directory, IDENTITY_FILE, authKeyPem)
                if (operatorCaPem.isNotBlank()) {
                    writePrivate(directory, TLS_CA_FILE, operatorCaPem)
                }
                writePrivate(directory, ADMISSION_FILE, profile.obfsSecret)
                writePrivate(directory, INNER_PSK_FILE, profile.innerPsk)

                val stealthProfile =
                    TransportProfileRegistry.normalize(profile.tlsStealthProfile)
                val json = JSONObject()
                    // DNS is deliberately outside libyume: Android resolves on
                    // the selected underlying Network before the VPN route can
                    // recurse. Certificate verification still uses the
                    // original configured name below.
                    .put("server", dialAddress)
                    .put("port", profile.port)
                    .put("identity", IDENTITY_FILE)
                    .put("socks_port", 0)
                    .put("tunnels", 1)
                    .put("service_streams_only", true)
                    .put("tls_server_name", tlsServerName)
                    .put("require_anonym", profile.requireAnonymProof)
                    .put("accept_monitoring", !profile.requireAnonymProof)
                    .put("auto_attach_local", false)
                    .put("obfuscation", true)
                    .put("obfs_secret_file", ADMISSION_FILE)
                    .put("inner_psk_file", INNER_PSK_FILE)
                    .put("tls_stealth_enabled", true)
                    .put("tls_stealth_profile", stealthProfile)
                    .apply {
                        if (operatorCaPem.isNotBlank()) {
                            // The existing core/config identifiers are retained for
                            // ABI compatibility. This optional CA proves operator
                            // authorization; an empty selection keeps system TLS trust.
                            put("tls_ca_cert", TLS_CA_FILE)
                            put("anonym_ca_cert", TLS_CA_FILE)
                        }
                        if (profile.tlsPinSha256.isNotEmpty()) {
                            put("tls_pin_sha256", profile.tlsPinSha256)
                        }
                    }
                    .toString()
                return SharedYumeSessionConfig(directory, json)
            } catch (throwable: Throwable) {
                SharedYumeSessionConfig(directory, "").close()
                throw throwable
            }
        }

        private fun writePrivate(directory: File, name: String, value: String) {
            val file = File(directory, name)
            file.writeText(value, Charsets.UTF_8)
            Os.chmod(file.absolutePath, 0x180) // 0600
        }

        /**
         * Remove secrets left by a process crash. Only direct, UUID-named,
         * non-symlink directories and the exact files created above are
         * touched; this intentionally is not a recursive delete.
         */
        private fun cleanupStaleSessions(parent: File) {
            parent.listFiles().orEmpty().forEach { directory ->
                if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return@forEach
                if (runCatching { UUID.fromString(directory.name) }.isFailure) return@forEach
                if (isActive(directory)) return@forEach
                listOf(IDENTITY_FILE, TLS_CA_FILE, ADMISSION_FILE, INNER_PSK_FILE)
                    .forEach { name -> runCatching { File(directory, name).delete() } }
                runCatching { directory.delete() }
            }
        }

        private fun registerActive(directory: File) = synchronized(activeSessionPaths) {
            activeSessionPaths += directory.absolutePath
        }

        private fun unregisterActive(directory: File) = synchronized(activeSessionPaths) {
            activeSessionPaths -= directory.absolutePath
        }

        private fun isActive(directory: File): Boolean = synchronized(activeSessionPaths) {
            directory.absolutePath in activeSessionPaths
        }
    }
}
