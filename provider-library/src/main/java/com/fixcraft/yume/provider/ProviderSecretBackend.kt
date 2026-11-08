package com.fixcraft.yume.provider

import java.io.File

/**
 * Consumer-owned encryption and key lifetime. [directory] contains only ordinary
 * `$name.bin` encrypted blobs, with an authenticated binding to the record name. Writes
 * atomically replace a synced file and add at most 64 bytes to the plaintext.
 * Missing is null. Lock, unavailable keys, corruption and I/O failure throw.
 * Deletion requires neither a key nor a network connection.
 *
 * The consumer excludes this directory and the store's control directory from
 * backup. Input bytes remain caller-owned. Returned plaintext is caller-owned.
 */
interface ProviderSecretBackend {
    val directory: File
    fun ensureReady(): Boolean
    fun isUnlocked(): Boolean
    fun readBlob(name: String): ByteArray?
    fun writeBlob(name: String, plaintext: ByteArray)
    fun deleteBlob(name: String)
}
