package com.fixcraft.yume.data

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Removes what builds for transport v2 stored: server profiles, operator CAs
 * and auth keys. This YUME reads none of it, and key material nothing uses
 * should not stay on the device.
 */
internal object LegacyData {
    fun remove(context: Context) {
        val appContext = context.applicationContext ?: context
        val noBackup = appContext.noBackupFilesDir
        File(noBackup, "secure-materials").deleteRecursively()
        File(noBackup, "yume-native-sessions").deleteRecursively()
        File(noBackup, "tun2socks").deleteRecursively()
        File(File(noBackup, "app-vault"), "server_profiles.bin").delete()
        File(File(noBackup, "app-vault"), "secure_materials.bin").delete()
        for (name in listOf("yume_vpn_profiles", "yume_secure_materials", "yume_app_settings")) {
            appContext.deleteSharedPreferences(name)
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("yume_secure_materials_key")
        }
    }
}
