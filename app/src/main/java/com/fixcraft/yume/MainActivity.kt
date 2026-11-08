package com.fixcraft.yume

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.AutoTranslationRuntime
import com.fixcraft.yume.i18n.LanguagePreparationProgress
import com.fixcraft.yume.i18n.LanguagePreparationStage
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.i18n.communityLanguageTags
import com.fixcraft.yume.ui.LanguagePreparationUiState
import com.fixcraft.yume.ui.YumeApp
import com.fixcraft.yume.ui.theme.YumeVPNTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    // Connecting asks for three things in turn: notifications, so the VPN's
    // status can be shown, unrestricted battery use where the distribution
    // may ask for it, and Android's consent to run a VPN.
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectSelectedKit()
        } else {
            viewModel.markPermissionDenied("Android's VPN consent was declined.")
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
        requestVpnPermissionAndConnect()
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
                    serviceActions = com.fixcraft.yume.ui.kits.ServiceActions(
                        add = viewModel.services::add,
                        authorize = { provider -> lifecycleScope.launch {
                            try {
                                val browser = withContext(Dispatchers.IO) { viewModel.services.authorize(provider) }
                                startActivity(browser)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (refused: com.fixcraft.yume.provider.ProviderCodec.Refusal) {
                                viewModel.reportUiNotice(com.fixcraft.yume.ui.kits.providerFailure(
                                    appStrings(this@MainActivity, uiState.appSettings.language), refused.code))
                            } catch (error: Exception) {
                                viewModel.services.browserLaunchFailed(provider)
                                if (BuildConfig.DEBUG) android.util.Log.w("Yume.Provider", "Browser launch refused: " + error.javaClass.simpleName)
                                viewModel.reportUiNotice(appStrings(this@MainActivity, uiState.appSettings.language)
                                    .message(com.fixcraft.yume.i18n.MessageId.PROVIDER_ERROR_GENERIC))
                            }
                        } },
                        catalog = viewModel.services::catalog,
                        install = viewModel.services::install,
                        select = viewModel.services::select,
                        cancel = viewModel.services::cancel,
                        remove = viewModel.services::remove,
                        code = viewModel.services::submitCode,
                        reload = viewModel.services::reload,
                    ),
                    onConnect = { viewModel.captureConnectTarget(); requestNotificationPermissionThenConnect() },
                    onDisconnect = viewModel::disconnect,
                    onRecoverKitIndex = viewModel::recoverKitIndex,
                    onSelectKit = viewModel::selectKit,
                    onRenameKit = viewModel::renameKit,
                    onDeleteKit = viewModel::deleteKit,
                    onImportKit = viewModel::importKit,
                    onReportNotice = viewModel::reportUiNotice,
                    onBuildReport = viewModel::buildReport,
                    onClearLog = viewModel::clearLog,
                    onSelectThemeColorMode = viewModel::updateThemeColorMode,
                    onSetAmoledDarkTheme = viewModel::updateAmoledDarkTheme,
                    isDarkTheme = darkTheme,
                    onEnableAppDataPassword = viewModel::enableAppDataPassword,
                    onChangeAppDataPassword = viewModel::changeAppDataPassword,
                    onDisableAppDataPassword = viewModel::disableAppDataPassword,
                    onSelectLanguage = { language ->
                        if (uiState.appSettings.language != language && languagePreparationState == null) {
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
                                viewModel.updateLanguage(language)
                                recreate()
                            }
                        }
                    },
                    onSetAdvancedMode = viewModel::updateAdvancedMode,
                    onSetDnsServers = viewModel::updateDnsServers,
                    onSetCarryIpv6 = viewModel::updateCarryIpv6,
                    onSelectTuningPreset = viewModel::resetTuningPreset,
                    onMoveTuningSlider = viewModel::moveTuningSlider,
                    onApplyTuning = viewModel::applyTuning,
                    onUnlockTuningExpert = viewModel::unlockTuningExpert,
                    onApplyTuningExpert = viewModel::applyTuningExpert,
                    onDeleteDownloadedLanguage = viewModel::deleteDownloadedLanguage,
                    languagePreparationState = languagePreparationState,
                    onClearNotice = viewModel::clearNotice,
                )
            }
        }
    }

    private suspend fun prepareLanguageForUse(
        language: AppLanguage,
        strings: AppStrings,
        onStateChanged: (LanguagePreparationUiState?) -> Unit,
    ): Boolean {
        return try {
            if (!AutoTranslationRuntime.needsPreparation(this, language)) return true
            val languageName = languageDisplayName(language, strings)
            onStateChanged(LanguagePreparationUiState(
                title = strings.preparingLanguageTitle,
                detail = strings.preparingLanguage(languageName), progress = null,
            ))
            val prepared = withContext(Dispatchers.IO) {
                AutoTranslationRuntime.prepareLanguage(this@MainActivity, language) { progress ->
                    runOnUiThread {
                        onStateChanged(buildLanguagePreparationState(strings, languageName, progress))
                    }
                }
            }
            if (prepared) viewModel.refreshDownloadedLanguages()
            prepared
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            onStateChanged(null)
        }
    }

    private fun buildLanguagePreparationState(
        strings: AppStrings,
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

    private fun languageDisplayName(language: AppLanguage, strings: AppStrings): String {
        return when (language) {
            AppLanguage.SystemDefault -> {
                val effective = AppLanguage.fromLocaleTag(
                    AppLocaleManager.resolveSystemLocale(this).toLanguageTag(),
                    communityLanguageTags,
                )
                when (effective) {
                    is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(effective)
                    else -> strings.languageName(effective)
                }
            }

            is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(language)
            else -> strings.languageName(language)
        }
    }

    private fun requestNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestBatteryOptimizationThenConnect()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryOptimizationThenConnect() {
        if (!BuildConfig.ALLOW_DIRECT_BATTERY_OPTIMIZATION_REQUEST || isIgnoringBatteryOptimizations()) {
            requestVpnPermissionAndConnect()
            return
        }
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching {
            batteryOptimizationLauncher.launch(requestIntent)
        }.onFailure {
            requestVpnPermissionAndConnect()
        }
    }

    private fun requestVpnPermissionAndConnect() {
        if (viewModel.uiState.value.selectedKit == null) return
        val permissionIntent = runCatching {
            VpnService.prepare(applicationContext)
        }.getOrElse {
            viewModel.markPermissionDenied("Android refused to prepare the VPN service.")
            return
        }
        if (permissionIntent == null) {
            viewModel.connectSelectedKit()
        } else {
            viewModel.markPermissionRequired()
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
}
