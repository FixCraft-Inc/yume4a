package com.fixcraft.yume.provider

/** One contained kit file. The owner clears [bytes] after its last borrower. */
class ProviderFile(val path: String, val executable: Boolean, val bytes: ByteArray) {
    override fun toString(): String = "ProviderFile([redacted])"
}
