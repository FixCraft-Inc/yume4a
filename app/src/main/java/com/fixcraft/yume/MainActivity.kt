package com.fixcraft.yume

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.isVpnService
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.AutoTranslationRuntime
import com.fixcraft.yume.i18n.LanguagePreparationProgress
import com.fixcraft.yume.i18n.LanguagePreparationStage
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.LanguagePreparationUiState
import com.fixcraft.yume.ui.YumeApp
import com.fixcraft.yume.ui.home.PendingAuthKeyImport
import com.fixcraft.yume.ui.theme.YumeVPNTheme
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingAuthKeyImport by mutableStateOf<PendingAuthKeyImport?>(null)
    private var pendingAuthKeyUnlockName by mutableStateOf<String?>(null)
    private var pendingAuthKeyUnlockError by mutableStateOf<String?>(null)
    private var transientBusyState by mutableStateOf<BusyUiState?>(null)
    private var pendingConnectAuthKeyPassword: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android VPN permission granted.")
            connectSelectedProfileWithPendingPassword()
        } else {
            pendingConnectAuthKeyPassword = null
            YumeVpnRuntime.appendLog(VpnLogLevel.Warn, "Android VPN permission was denied.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    headline = appStrings(this, viewModel.uiState.value.appSettings.language).permissionDeniedHeadline,
                    detail = appStrings(this, viewModel.uiState.value.appSettings.language).permissionDeniedDetail,
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
        if (!AppVaultManager.ensureReady(this)) {
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = isSystemInDarkTheme()
            val scope = rememberCoroutineScope()
            var languagePreparationState by remember { mutableStateOf<LanguagePreparationUiState?>(null) }
            LaunchedEffect(darkTheme, uiState.appSettings.amoledDarkTheme) {
                viewModel.syncAmoledDarkTheme(darkTheme)
            }
            LaunchedEffect(uiState.appSettings.language.wireValue) {
                if (languagePreparationState == null &&
                    AutoTranslationRuntime.needsPreparation(this@MainActivity, uiState.appSettings.language)
                ) {
                    val currentStrings = appStrings(this@MainActivity, uiState.appSettings.language)
                    val prepared = prepareLanguageForUse(
                        language = uiState.appSettings.language,
                        strings = currentStrings,
                        onStateChanged = { languagePreparationState = it },
                    )
                    if (!prepared) {
                        viewModel.reportUiNotice(
                            currentStrings.languagePreparationFailed(
                                languageDisplayName(uiState.appSettings.language, currentStrings)
                            )
                        )
                    }
                }
            }
            YumeVPNTheme(
                darkTheme = darkTheme,
                themeColorMode = uiState.appSettings.themeColorMode,
                amoledDarkTheme = uiState.appSettings.amoledDarkTheme,
            ) {
                YumeApp(
                    uiState = uiState,
                    onConnect = {
                        val encryptedAuthKeyName = uiState.selectedAuthKeyMaterial
                            ?.takeIf { it.importedEncrypted }
                            ?.displayName
                        if (encryptedAuthKeyName != null) {
                            pendingAuthKeyUnlockError = null
                            pendingAuthKeyUnlockName = encryptedAuthKeyName
                        } else {
                            requestNotificationPermissionThenConnect()
                        }
                    },
                    onDisconnect = viewModel::disconnect,
                    onSelectProfile = viewModel::selectProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onSaveProfile = viewModel::saveProfile,
                    onImportCaFile = { importCaLauncher.launch(arrayOf("*/*")) },
                    onImportAuthKeyFile = { importAuthKeyLauncher.launch(arrayOf("*/*")) },
                    onSaveCaText = { name, pemText ->
                        viewModel.savePastedMaterial(SecureMaterialType.AnonymCa, name, pemText)
                    },
                    onSaveAuthKeyText = { name, pemText, encrypted, password ->
                        viewModel.savePastedAuthKey(name, pemText, encrypted, password)
                    },
                    pendingAuthKeyImport = pendingAuthKeyImport,
                    onImportAuthKeyPayload = { name, bytes, encrypted, password ->
                        viewModel.importAuthKeyFromBytes(name, bytes, encrypted, password).also { error ->
                            if (error == null) {
                                pendingAuthKeyImport = null
                            }
                        }
                    },
                    onDismissAuthKeyImport = { pendingAuthKeyImport = null },
                    onDeleteMaterial = viewModel::deleteMaterial,
                    onSelectTechnique = viewModel::updateConnectionTechnique,
                    onSelectPerformanceProfile = viewModel::updatePerformanceProfile,
                    onSelectSocks5BindMode = viewModel::updateSocks5BindMode,
                    onUpdateSocks5BindAddress = viewModel::updateSocks5BindAddress,
                    onUpdateSocks5Port = viewModel::updateSocks5ProxyPort,
                    onUpdateSocks5TunnelCount = viewModel::updateSocks5TunnelCount,
                    onSelectThemeColorMode = viewModel::updateThemeColorMode,
                    onSetAmoledDarkTheme = viewModel::updateAmoledDarkTheme,
                    isDarkTheme = darkTheme,
                    onEnableAppDataPassword = viewModel::enableAppDataPassword,
                    onChangeAppDataPassword = viewModel::changeAppDataPassword,
                    onDisableAppDataPassword = viewModel::disableAppDataPassword,
                    onSelectLanguage = { language ->
                        if (uiState.appSettings.language == language || languagePreparationState != null) {
                            Unit
                        } else {
                            scope.launch {
                                val currentStrings = appStrings(this@MainActivity, uiState.appSettings.language)
                                val prepared = prepareLanguageForUse(
                                    language = language,
                                    strings = currentStrings,
                                    onStateChanged = { languagePreparationState = it },
                                )
                                if (!prepared) {
                                    viewModel.reportUiNotice(
                                        currentStrings.languagePreparationFailed(
                                            languageDisplayName(language, currentStrings)
                                        )
                                    )
                                    return@launch
                                }
                                val shouldRecreate = uiState.appSettings.language != language
                                viewModel.updateLanguage(language)
                                if (shouldRecreate) {
                                    recreate()
                                }
                            }
                        }
                    },
                    onSelectLogLanguage = viewModel::updateLogLanguage,
                    onSetAdvancedMode = viewModel::updateAdvancedMode,
                    onDeleteDownloadedLanguage = viewModel::deleteDownloadedLanguage,
                    languagePreparationState = languagePreparationState,
                    busyState = uiState.busyState ?: transientBusyState,
                    diagnosticsReport = viewModel.buildDiagnosticsReport(),
                    onClearNotice = viewModel::clearNotice,
                    pendingAuthKeyUnlockName = pendingAuthKeyUnlockName,
                    pendingAuthKeyUnlockError = pendingAuthKeyUnlockError,
                    onDismissAuthKeyUnlock = {
                        pendingAuthKeyUnlockName = null
                        pendingAuthKeyUnlockError = null
                    },
                    onUnlockAuthKey = { password ->
                        val authKeyName = pendingAuthKeyUnlockName
                            ?: uiState.selectedAuthKeyMaterial?.displayName
                            ?: return@YumeApp
                        pendingAuthKeyUnlockName = null
                        pendingAuthKeyUnlockError = null
                        lifecycleScope.launch {
                            val error = viewModel.validateSelectedEncryptedAuthKey(password)
                            if (error == null) {
                                requestNotificationPermissionThenConnect(password)
                            } else {
                                pendingAuthKeyUnlockError = error
                                pendingAuthKeyUnlockName = authKeyName
                            }
                        }
                    },
                )
            }
        }
    }

    private suspend fun prepareLanguageForUse(
        language: AppLanguage,
        strings: com.fixcraft.yume.i18n.AppStrings,
        onStateChanged: (LanguagePreparationUiState?) -> Unit,
    ): Boolean {
        if (!AutoTranslationRuntime.needsPreparation(this, language)) {
            onStateChanged(null)
            return true
        }
        val languageName = languageDisplayName(language, strings)
        onStateChanged(
            LanguagePreparationUiState(
                title = strings.preparingLanguageTitle,
                detail = strings.preparingLanguage(languageName),
                progress = null,
            )
        )
        val prepared = withContext(Dispatchers.IO) {
            AutoTranslationRuntime.prepareLanguage(
                context = this@MainActivity,
                language = language,
                onProgress = { progress ->
                    runOnUiThread {
                        onStateChanged(buildLanguagePreparationState(strings, languageName, progress))
                    }
                },
            )
        }
        if (prepared) {
            viewModel.refreshDownloadedLanguages()
        }
        onStateChanged(null)
        return prepared
    }

    private fun buildLanguagePreparationState(
        strings: com.fixcraft.yume.i18n.AppStrings,
        languageName: String,
        progress: LanguagePreparationProgress,
    ): LanguagePreparationUiState {
        return when (progress.stage) {
            LanguagePreparationStage.Downloading -> LanguagePreparationUiState(
                title = strings.downloadingLanguageTitle,
                detail = strings.downloadingLanguage(languageName),
                progress = null,
            )

            LanguagePreparationStage.Translating -> LanguagePreparationUiState(
                title = strings.translatingLanguageTitle,
                detail = strings.translatingLanguage(languageName, progress.done, progress.total),
                progress = if (progress.total > 0) {
                    progress.done.toFloat() / progress.total.toFloat()
                } else {
                    null
                },
            )
        }
    }

    private fun languageDisplayName(
        language: AppLanguage,
        strings: com.fixcraft.yume.i18n.AppStrings,
    ): String {
        return when (language) {
            AppLanguage.SystemDefault -> {
                val effective = AppLanguage.fromLocaleTag(AppLocaleManager.resolveSystemLocale(this).toLanguageTag())
                when (effective) {
                    is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(effective)
                    else -> strings.languageName(effective)
                }
            }

            is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(language)
            else -> strings.languageName(language)
        }
    }

    private fun requestNotificationPermissionThenConnect(authKeyPassword: String? = null) {
        pendingConnectAuthKeyPassword = authKeyPassword
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
        val selectedProfileId = viewModel.uiState.value.selectedProfileId ?: run {
            pendingConnectAuthKeyPassword = null
            return
        }
        val technique = viewModel.uiState.value.appSettings.connectionTechnique
        if (!technique.isVpnService()) {
            // SOCKS5-only mode does not own a TUN interface, so the
            // platform VPN permission dialog is irrelevant. Skip it.
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "SOCKS5-only mode selected; skipping VPN permission step.")
            connectSelectedProfileWithPendingPassword()
            return
        }
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
                    headline = appStrings(this, viewModel.uiState.value.appSettings.language).vpnPrepareFailedHeadline,
                    detail = detail,
                )
            )
            return
        }
        if (permissionIntent == null) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android VPN permission is already granted.")
            connectSelectedProfileWithPendingPassword()
        } else {
            viewModel.markPermissionRequired(selectedProfileId)
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Opening the Android VPN permission dialog.")
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun connectSelectedProfileWithPendingPassword() {
        val authKeyPassword = pendingConnectAuthKeyPassword
        pendingConnectAuthKeyPassword = null
        viewModel.connectSelectedProfile(authKeyPassword)
    }

    private fun handleMaterialImport(uri: Uri?, type: SecureMaterialType) {
        if (uri == null) {
            return
        }
        val strings = appStrings(this, viewModel.uiState.value.appSettings.language)
        transientBusyState = BusyUiState(
            title = strings.importFile,
            detail = strings.readingSelectedFile(strings.materialTypeLabel(type)),
        )
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                val displayName = queryDisplayName(uri).orEmpty().ifBlank {
                    if (type == SecureMaterialType.AnonymCa) strings.importedCaFallbackName else strings.importedAuthKeyFallbackName
                }
                val bytes = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                displayName to bytes
            }
            transientBusyState = null
            val (displayName, bytes) = imported
            if (bytes == null) {
                viewModel.reportUiNotice(strings.failedToReadSelected(strings.materialTypeLabel(type)))
                return@launch
            }
            if (type == SecureMaterialType.AuthKey) {
                pendingAuthKeyImport = PendingAuthKeyImport(displayName = displayName, bytes = bytes)
            } else {
                viewModel.importMaterialFromBytes(type, displayName, bytes)
            }
        }
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
