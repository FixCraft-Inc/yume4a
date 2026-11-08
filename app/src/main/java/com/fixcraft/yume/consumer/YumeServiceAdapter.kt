package com.fixcraft.yume.consumer

import android.content.Context
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitSession
import com.fixcraft.yume.data.KitStore
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderRegistration
import com.fixcraft.yume.provider.YumeProviderNative
import com.fixcraft.yume.provider.yumeProviderStore

/** yume4a owns its selection vault and native session materialization. */
internal class YumeServiceAdapter(context: Context) : ServiceAdapter<KitSession> {
    private val app = context.applicationContext ?: context
    override fun ensureReady(): Boolean = AppVaultManager.ensureReady(app)
    override fun isUnlocked(): Boolean = AppVaultManager.isUnlocked()
    override fun readSelection(): ByteArray? = AppVaultManager.readBlob(app, "service-selection")
    override fun writeSelection(bytes: ByteArray) = AppVaultManager.writeBlob(app, "service-selection", bytes)
    override fun openSession(bundle: ByteArray): KitSession = KitStore.openBundleSession(app, bundle)
}

internal val ServiceSelection.summary: KitSummary get() = KitSummary(id, label, host, port, 0)

internal fun yumeServiceController(context: Context, registration: ProviderRegistration?): ServiceController<KitSession> {
    val app = context.applicationContext ?: context
    return ServiceController(app, registration, "vpn", YumeServiceAdapter(app)) { connections ->
        val store = yumeProviderStore(app)
        try { ProviderClient(app, registration, store, YumeProviderNative.validator(app), connections = connections) }
        catch (error: Throwable) { store.close(); throw error }
    }
}
