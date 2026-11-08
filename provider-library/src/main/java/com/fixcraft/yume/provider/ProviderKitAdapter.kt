package com.fixcraft.yume.provider

/** A consumer supplies its native kit-validation adapter, with no VPN or UI. */
fun interface ProviderKitAdapter {
    fun validate(sealed: ByteArray, openingCode: CharArray, grant: ProviderCodec.Grant,
                 server: ProviderCodec.Server): ProviderBundle
}

interface ProviderBundle : AutoCloseable {
    val bundle: ByteArray
}
