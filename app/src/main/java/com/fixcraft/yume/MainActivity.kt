package com.fixcraft.yume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.ui.home.HomeScreen
import com.fixcraft.yume.ui.theme.YumeVPNTheme
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android VPN permission granted.")
            viewModel.connectSelectedProfile()
        } else {
            YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "Android VPN permission was denied.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    headline = "Permission denied",
                    detail = "Android VPN permission was denied. Yume cannot create a VPN interface without it.",
                )
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestBatteryOptimizationThenConnect()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isIgnoringBatteryOptimizations()) {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Info,
                "Android battery optimization is unrestricted for Yume background activity.",
            )
        } else {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Battery optimization remains enabled. Android may pause Yume recovery and inner hopping when the app is backgrounded.",
            )
        }
        requestVpnPermissionAndConnect()
    }

    private val importCaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleMaterialImport(uri, SecureMaterialType.AnonymCa)
    }

    private val importAuthKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleMaterialImport(uri, SecureMaterialType.AuthKey)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            YumeVPNTheme {
                HomeScreen(
                    uiState = uiState,
                    onConnect = { requestNotificationPermissionThenConnect() },
                    onDisconnect = viewModel::disconnect,
                    onSelectProfile = viewModel::selectProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onSaveProfile = viewModel::saveProfile,
                    onImportCaFile = { importCaLauncher.launch(arrayOf("*/*")) },
                    onImportAuthKeyFile = { importAuthKeyLauncher.launch(arrayOf("*/*")) },
                    onSaveCaText = { name, pemText ->
                        viewModel.savePastedMaterial(SecureMaterialType.AnonymCa, name, pemText)
                    },
                    onSaveAuthKeyText = { name, pemText ->
                        viewModel.savePastedMaterial(SecureMaterialType.AuthKey, name, pemText)
                    },
                    onDeleteMaterial = viewModel::deleteMaterial,
                    onClearNotice = viewModel::clearNotice,
                )
            }
        }
    }

    private fun requestNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestBatteryOptimizationThenConnect()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            requestBatteryOptimizationThenConnect()
            return
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Requesting Android notification permission for VPN status updates.")
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryOptimizationThenConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) {
            requestVpnPermissionAndConnect()
            return
        }
        YumeVpnRuntime.appendLog(
            VpnLogLevel.Warn,
            "Requesting unrestricted battery usage so Yume can keep the protected control path and inner hopping alive in the background.",
        )
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching {
            batteryOptimizationLauncher.launch(requestIntent)
        }.recoverCatching {
            batteryOptimizationLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            YumeVpnRuntime.appendLog(
                VpnLogLevel.Warn,
                "Unable to open Android battery optimization settings automatically. Continue granting unrestricted battery use manually for reliable background protection.",
            )
            requestVpnPermissionAndConnect()
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val selectedProfileId = viewModel.uiState.value.selectedProfileId ?: return
        val permissionIntent = runCatching {
            VpnService.prepare(applicationContext)
        }.getOrElse { throwable ->
            val detail = throwable.message
                ?: "Android refused to prepare the VPN service. If this started after reinstalling the app, uninstall the old build and install the fresh one again."
            YumeVpnRuntime.appendLog(VpnLogLevel.Error, "VpnService.prepare() failed: $detail")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    activeProfileId = selectedProfileId,
                    headline = "VPN prepare failed",
                    detail = detail,
                )
            )
            return
        }
        if (permissionIntent == null) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android VPN permission is already granted.")
            viewModel.connectSelectedProfile()
        } else {
            viewModel.markPermissionRequired(selectedProfileId)
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Opening the Android VPN permission dialog.")
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun handleMaterialImport(uri: Uri?, type: SecureMaterialType) {
        if (uri == null) {
            return
        }
        val displayName = queryDisplayName(uri).orEmpty().ifBlank {
            if (type == SecureMaterialType.AnonymCa) "Imported CA" else "Imported auth key"
        }
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.reportUiNotice("Failed to read the selected ${type.label.lowercase()}")
            return
        }
        viewModel.importMaterialFromBytes(type, displayName, bytes)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) {
                null
            } else {
                cursor.getString(index)
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }
}
