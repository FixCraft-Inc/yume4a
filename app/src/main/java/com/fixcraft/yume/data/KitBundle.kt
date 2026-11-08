package com.fixcraft.yume.data

import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.provider.ProviderBundleCodec
import com.fixcraft.yume.provider.ProviderFile

/** App JNI file values over the one shared bounded stored-bundle codec. */
internal object KitBundle {
    const val CONFIG_PATH = ProviderBundleCodec.CONFIG_PATH
    const val MAX_FILES = ProviderBundleCodec.MAX_FILES
    const val MAX_PATH_BYTES = ProviderBundleCodec.MAX_PATH_BYTES
    const val MAX_FILE_BYTES = ProviderBundleCodec.MAX_FILE_BYTES
    const val MAX_CONTENT_BYTES = ProviderBundleCodec.MAX_CONTENT_BYTES

    fun isSafePath(path: String): Boolean = ProviderBundleCodec.isSafePath(path)
    fun problem(files: List<KitFile>): String? = ProviderBundleCodec.problem(values(files))
    fun encode(files: List<KitFile>): ByteArray = ProviderBundleCodec.encode(values(files))
    fun decode(bundle: ByteArray): List<KitFile> = ProviderBundleCodec.decode(bundle).map {
        KitFile(it.path, it.executable, it.bytes)
    }
    private fun values(files: List<KitFile>): List<ProviderFile> =
        files.map { ProviderFile(it.path, it.executable, it.bytes) }
}
