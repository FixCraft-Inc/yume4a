package com.fixcraft.yume.provider

import android.content.Context
import com.fixcraft.yume.data.AppVaultManager
import java.io.File

/** Keeps yume4a's existing vault key, password gate and ciphertext layout. */
internal class YumeProviderBackend(context: Context) : ProviderSecretBackend {
    private val app = context.applicationContext ?: context
    override val directory = File(app.noBackupFilesDir, "app-vault")
    override fun ensureReady(): Boolean = AppVaultManager.ensureReady(app)
    override fun isUnlocked(): Boolean = AppVaultManager.isUnlocked()
    override fun readBlob(name: String): ByteArray? = AppVaultManager.readBlob(app, name)
    override fun writeBlob(name: String, plaintext: ByteArray) = AppVaultManager.writeBlob(app, name, plaintext)
    override fun deleteBlob(name: String) = AppVaultManager.deleteBlob(app, name)
}

internal fun yumeProviderStore(context: Context): ProviderStore =
    ProviderStore(File((context.applicationContext ?: context).noBackupFilesDir, "provider-state"), YumeProviderBackend(context))
