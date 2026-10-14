package com.fixcraft.yume

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.KitStore
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.TuningLimits
import com.fixcraft.yume.data.TuningPreset
import com.fixcraft.yume.data.TuningTable
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AutoTranslationRuntime
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BusyUiState(
    val title: String,
    val detail: String? = null,
)

data class MainUiState(
    val kits: List<KitSummary> = emptyList(),
    val selectedKitId: String? = null,
    val kitIndexCorrupt: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val appDataPasswordEnabled: Boolean = false,
    val downloadedLanguageTags: Set<String> = emptySet(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val noticeMessage: String? = null,
    val busyState: BusyUiState? = null,
    val tuning: TuningUiState = TuningUiState(),
) {
    val selectedKit: KitSummary? = kits.firstOrNull { it.id == selectedKitId }
}

data class TuningUiState(
    val kitId: String? = null,
    val limits: TuningLimits? = null,
    val preset: TuningPreset? = null,
    val saving: Boolean = false,
    val expertUnlocked: Boolean = false,
    val levelMaximum: Map<String, Int> = emptyMap(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val kitStore = KitStore.get(application)
    private val appSettingsStore = AppSettingsStore.get(application)
    private val _noticeMessage = MutableStateFlow<String?>(null)
    private val _busyState = MutableStateFlow<BusyUiState?>(null)
    private val _downloadedLanguageTags = MutableStateFlow<Set<String>>(emptySet())
    private val _appDataPasswordEnabled = MutableStateFlow(AppVaultManager.isPasswordEnabled(application))
    private val tuningTable = TuningTable.load(application)
    private val _tuning = MutableStateFlow(TuningUiState())
    private var tuningLoadGeneration = 0L
    private var tuningLoadJob: Job? = null

    init {
        refreshDownloadedLanguages()
        viewModelScope.launch {
            kitStore.selectedId.collect { selectedId -> refreshTuning(selectedId) }
        }
    }

    private val kitState = combine(kitStore.kits, kitStore.indexStatus) { kits, status ->
        kits to (status == com.fixcraft.yume.data.KitIndexStatus.Corrupt)
    }

    private val storedState = combine(
        kitState,
        kitStore.selectedId,
        appSettingsStore.settings,
        YumeVpnRuntime.state,
        _tuning,
    ) { kits, selectedId, settings, vpnState, tuning ->
        MainUiState(
            kits = kits.first,
            kitIndexCorrupt = kits.second,
            selectedKitId = selectedId,
            appSettings = settings,
            vpnState = vpnState,
            tuning = tuning.copy(levelMaximum = tuningTable.levelMaximum),
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        storedState,
        _appDataPasswordEnabled,
        _downloadedLanguageTags,
        _noticeMessage,
        _busyState,
    ) { stored, passwordEnabled, downloadedTags, notice, busy ->
        stored.copy(
            appDataPasswordEnabled = passwordEnabled,
            downloadedLanguageTags = downloadedTags,
            noticeMessage = notice,
            busyState = busy,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    private fun strings(language: AppLanguage = appSettingsStore.settings.value.language) =
        appStrings(getApplication(), language)

    private suspend fun <T> runBusy(title: String, detail: String? = null, block: () -> T): Result<T> {
        _busyState.value = BusyUiState(title = title, detail = detail)
        return try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            Result.failure(throwable)
        } finally {
            _busyState.value = null
        }
    }

    // Kits.

    fun selectKit(kitId: String) {
        runCatching { kitStore.select(kitId) }
            .onFailure { _noticeMessage.value = strings().kitRefusedStorage }
    }

    fun refreshTuning(kitId: String? = uiState.value.selectedKitId) {
        val generation = ++tuningLoadGeneration
        tuningLoadJob?.cancel()
        _tuning.value = TuningUiState(kitId = kitId)
        if (kitId == null) return
        tuningLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val config = kitStore.readConfig(kitId)
            val loaded = runCatching {
                val json = org.json.JSONObject(String(config ?: error("kit unavailable"), Charsets.UTF_8))
                val limits = tuningTable.effectiveLimits(json)
                TuningUiState(kitId, limits, tuningTable.match(limits))
            }.getOrNull()
            // The generation is owned by the main thread; a newer selection wins.
            withContext(Dispatchers.Main) {
                if (generation == tuningLoadGeneration && _tuning.value.kitId == kitId) {
                    _tuning.value = loaded ?: TuningUiState(kitId = kitId)
                }
            }
        }
    }

    fun resetTuningPreset() = selectTuningPreset(tuningTable.defaultId)

    fun selectTuningPreset(id: String) {
        val preset = tuningTable.presets.firstOrNull { it.id == id } ?: return
        if (_tuning.value.kitId == kitStore.selectedId.value && !_tuning.value.saving) {
            _tuning.value = _tuning.value.copy(limits = preset.limits, preset = preset)
        }
    }

    fun moveTuningSlider(axis: String, level: Int) {
        val current = _tuning.value.preset ?: return
        tuningTable.move(current, axis, level)?.let { preset ->
            _tuning.value = _tuning.value.copy(limits = preset.limits, preset = preset)
        }
    }

    fun unlockTuningExpert() {
        _tuning.value = _tuning.value.copy(expertUnlocked = true)
    }

    fun applyTuning() {
        val state = _tuning.value
        val kitId = state.kitId ?: return
        val limits = state.limits ?: return
        if (state.saving || kitId != kitStore.selectedId.value) return
        val generation = tuningLoadGeneration
        _tuning.value = state.copy(saving = true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = kitStore.updateLimits(kitId, limits)
            withContext(Dispatchers.Main) {
                if (generation != tuningLoadGeneration || _tuning.value.kitId != kitId) return@withContext
                _tuning.value = _tuning.value.copy(saving = false)
                if (result.isSuccess) {
                    _noticeMessage.value = strings().tuningSaved
                    refreshTuning(kitId)
                } else {
                    _noticeMessage.value = strings().tuningSaveFailed
                }
            }
        }
    }

    fun applyTuningExpert(key: String, value: String): Boolean {
        val state = _tuning.value
        if (!state.expertUnlocked || state.saving) return false
        val old = state.limits ?: return false
        val updated = when (key) {
            "max_queued_bytes" -> value.toLongOrNull()?.let { old.copy(maxQueuedBytes = it) }
            "max_epoch_bytes" -> value.toLongOrNull()?.let { old.copy(maxEpochBytes = it) }
            "credit_returns_per_window" -> value.toIntOrNull()?.let { old.copy(creditReturnsPerWindow = it) }
            "idle_epoch_rotation" -> value.toBooleanStrictOrNull()?.let { old.copy(idleEpochRotation = it) }
            else -> null
        } ?: return false
        _tuning.value = state.copy(limits = updated, preset = tuningTable.match(updated))
        applyTuning()
        return true
    }

    fun recoverKitIndex() {
        viewModelScope.launch {
            val result = runBusy(strings().recoverKitIndex) {
                kitStore.recoverKits(kitStore.recoverableKits())
            }
            if (result.isFailure || result.getOrNull()?.isFailure == true) {
                _noticeMessage.value = strings().kitRefusedStorage
            }
        }
    }

    fun renameKit(kitId: String, name: String) {
        runCatching { kitStore.rename(kitId, name) }
            .onFailure { _noticeMessage.value = strings().kitRefusedStorage }
    }

    fun deleteKit(kitId: String) {
        // A running kit's files are in use until the VPN is stopped.
        val vpnState = YumeVpnRuntime.state.value
        if (vpnState.isActive && vpnState.kitId == kitId) return
        runCatching { kitStore.delete(kitId) }
            .onFailure { _noticeMessage.value = strings().kitRefusedStorage }
    }

    /**
     * Opens a sealed kit and stores it. [code] is wiped before this returns.
     * Opening derives a key with Argon2id, which takes a few seconds.
     */
    suspend fun importKit(sealed: ByteArray, code: CharArray, name: String): KitImport {
        val strings = strings()
        val result = runBusy(strings.openingKit, strings.openingKitDetail) {
            kitStore.importSealed(sealed, code, name)
        }.getOrElse { throwable ->
            code.fill('\u0000')
            KitImport.Refused(com.fixcraft.yume.data.KitRefusal.Storage, throwable.message.orEmpty())
        }
        if (result is KitImport.Imported) {
            runCatching { kitStore.select(result.kit.id) }
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Added the kit ${result.kit.name} (${result.kit.endpointLabel}).")
            _noticeMessage.value = strings.kitAdded(result.kit.name)
        }
        return result
    }

    // Connection.

    fun markPermissionRequired() {
        val kit = uiState.value.selectedKit ?: return
        YumeVpnRuntime.update {
            VpnConnectionState(
                phase = VpnPhase.PermissionRequired,
                kitId = kit.id,
                kitName = kit.name,
                endpointLabel = kit.endpointLabel,
            )
        }
    }

    fun markPermissionDenied(detail: String) {
        YumeVpnRuntime.appendLog(VpnLogLevel.Warn, detail)
        YumeVpnRuntime.update {
            VpnConnectionState(
                phase = VpnPhase.Error,
                kitId = it.kitId,
                kitName = it.kitName,
                endpointLabel = it.endpointLabel,
                failure = VpnFailure.VpnPermission,
                failureDetail = detail,
            )
        }
    }

    fun connectSelectedKit() {
        val kit = uiState.value.selectedKit ?: return
        YumeVpnService.startConnect(getApplication(), kit.id)
    }

    fun disconnect() {
        YumeVpnService.startDisconnect(getApplication())
    }

    // Notices.

    fun reportUiNotice(message: String) {
        _noticeMessage.value = message
    }

    fun clearNotice() {
        _noticeMessage.value = null
    }

    // Settings.

    fun updateThemeColorMode(mode: ThemeColorMode) {
        appSettingsStore.updateThemeColorMode(mode)
    }

    fun updateAmoledDarkTheme(enabled: Boolean) {
        appSettingsStore.updateAmoledDarkTheme(enabled)
    }

    fun syncAmoledDarkTheme(darkTheme: Boolean) {
        if (!darkTheme && uiState.value.appSettings.amoledDarkTheme) {
            appSettingsStore.updateAmoledDarkTheme(false)
        }
    }

    fun updateLanguage(language: AppLanguage) {
        appSettingsStore.updateLanguage(language)
        _noticeMessage.value = strings(language).languageSummary(language)
    }

    fun updateAdvancedMode(enabled: Boolean) {
        appSettingsStore.updateAdvancedMode(enabled)
    }

    /** False when [text] is not one to four address literals. */
    fun updateDnsServers(text: String): Boolean {
        val servers = AppSettings.parseDnsServers(text) ?: return false
        appSettingsStore.updateDnsServers(servers)
        _noticeMessage.value = strings().appliesOnNextConnect
        return true
    }

    fun updateCarryIpv6(enabled: Boolean) {
        appSettingsStore.updateCarryIpv6(enabled)
        _noticeMessage.value = strings().appliesOnNextConnect
    }

    suspend fun enableAppDataPassword(password: String, confirmPassword: String): String? {
        val strings = strings()
        if (password.isBlank()) return strings.passwordRequired
        if (!AppVaultManager.passwordMeetsPolicy(password)) return strings.passwordTooShort
        if (password != confirmPassword) return strings.passwordsDoNotMatch
        return runBusy(strings.setPasswordButton) {
            AppVaultManager.enablePassword(getApplication(), password)
        }.getOrElse { throwable -> Result.failure<Unit>(throwable) }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = true
                    _noticeMessage.value = strings.appDataPasswordEnabled
                    null
                },
                onFailure = { strings.failedToEnableAppDataPassword },
            )
    }

    suspend fun changeAppDataPassword(currentPassword: String, newPassword: String, confirmPassword: String): String? {
        val strings = strings()
        if (currentPassword.isBlank()) return strings.currentPasswordRequired
        if (newPassword.isBlank()) return strings.newPasswordRequired
        if (!AppVaultManager.passwordMeetsPolicy(newPassword)) return strings.passwordTooShort
        if (newPassword != confirmPassword) return strings.passwordsDoNotMatch
        return runBusy(strings.changePasswordButton) {
            AppVaultManager.changePassword(getApplication(), currentPassword, newPassword)
        }.getOrElse { throwable -> Result.failure<Unit>(throwable) }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = true
                    _noticeMessage.value = strings.appDataPasswordChanged
                    null
                },
                onFailure = { strings.failedToChangeAppDataPassword },
            )
    }

    suspend fun disableAppDataPassword(currentPassword: String): String? {
        val strings = strings()
        if (currentPassword.isBlank()) return strings.currentPasswordRequired
        return runBusy(strings.removePasswordButton) {
            AppVaultManager.disablePassword(getApplication(), currentPassword)
        }.getOrElse { throwable -> Result.failure<Unit>(throwable) }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = false
                    _noticeMessage.value = strings.appDataPasswordDisabled
                    null
                },
                onFailure = { strings.failedToDisableAppDataPassword },
            )
    }

    fun refreshDownloadedLanguages() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _downloadedLanguageTags.value = AutoTranslationRuntime.refreshDownloadedLocaleTags()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _noticeMessage.value = strings().languageDownloadsUnavailable
            }
        }
    }

    fun deleteDownloadedLanguage(language: AppLanguage.AutoTranslated) {
        viewModelScope.launch(Dispatchers.IO) {
            val strings = strings()
            val languageName = AppLanguageCatalog.displayName(language)
            try {
                val deleted = AutoTranslationRuntime.deleteDownloadedLanguage(getApplication(), language.localeTag)
                _downloadedLanguageTags.value = AutoTranslationRuntime.refreshDownloadedLocaleTags()
                _noticeMessage.value = if (deleted) strings.deletedLanguageDownload(languageName)
                    else strings.failedToDeleteLanguageDownload(languageName)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _noticeMessage.value = strings.failedToDeleteLanguageDownload(languageName)
            }
        }
    }

    fun clearLog() {
        YumeVpnRuntime.clearLog()
    }

    /**
     * A plain-text report for whoever helps with a problem. It is written in
     * English, names no key and no code, and holds the server's address,
     * which the reader of a report needs and its sender already knows.
     */
    fun buildReport(): String {
        val state = uiState.value
        val vpn = state.vpnState
        val build = runCatching { YumeLibrary.build() }.getOrNull()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Yume Android report")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            if (build != null) {
                appendLine("YUME library: ${build.productVersion}, ${build.protocolName}, ABI ${build.abiVersion}")
                appendLine("Suite: ${build.suite}")
                appendLine("Cryptography: ${build.cryptoBackend}")
            } else {
                appendLine("YUME library: unavailable")
            }
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.joinToString()})")
            appendLine()
            appendLine("Phase: ${vpn.phase}")
            appendLine("Kit: ${vpn.kitName ?: state.selectedKit?.name ?: "none"}")
            appendLine("Server: ${vpn.endpointLabel ?: state.selectedKit?.endpointLabel ?: "none"}")
            vpn.serverAddress?.let { appendLine("Dialled address: $it") }
            appendLine("Network: ${vpn.upstream ?: "none"}")
            if (vpn.failure != VpnFailure.None) {
                appendLine("Failure: ${vpn.failure} ${vpn.failureDetail}".trim())
            }
            // As the posture screen: the running interface's values while a
            // run is active, otherwise what the next connection will use.
            val activeIpv6 = if (vpn.isActive) vpn.carriesIpv6 else state.appSettings.carryIpv6
            val activeDns = vpn.dnsServers.takeIf { vpn.isActive && it.isNotEmpty() }
                ?: com.fixcraft.yume.vpn.VpnRunPolicy.usableDnsServers(
                    state.appSettings.dnsServers, activeIpv6, AppSettings.DEFAULT_DNS_SERVERS,
                )
            val scope = if (vpn.isActive) "" else " (next connection)"
            appendLine("DNS$scope: ${activeDns.joinToString()}")
            appendLine("IPv6$scope: ${if (activeIpv6) "carried" else "blocked while connected"}")
            vpn.status?.let { status ->
                appendLine()
                appendLine("Session: ${sessionName(status.session)}, sessions ${status.sessions}, failed attempts ${status.failedAttempts}")
                appendLine("Connected: ${status.connectedMs / 1000} s, next attempt in ${status.retryMs} ms")
                appendLine("Payload: ${status.payloadBytesReceived} bytes down, ${status.payloadBytesSent} bytes up")
                appendLine("Records: ${status.recordBytesReceived} bytes down, ${status.recordBytesSent} bytes up")
                appendLine("Open: ${status.tcpConnections} TCP connections, ${status.udpDestinations} UDP destinations")
                appendLine("Key epoch: ${status.epochBytes} bytes, idle rotation ${if (status.idleEpochRotation) "on" else "off"}")
                if (status.lastFailureMessage.isNotBlank()) {
                    appendLine("Latest failure: ${status.lastFailureMessage}")
                }
            }
            appendLine()
            appendLine("Log:")
            if (vpn.eventLog.isEmpty()) appendLine("(empty)")
            vpn.eventLog.forEach { entry ->
                appendLine("${time.format(Date(entry.timestampEpochMs))} ${entry.level.name.uppercase(Locale.US)} ${entry.message}")
            }
        }
    }

    private fun sessionName(session: Int): String = when (session) {
        YumeStatus.SESSION_ACTIVE -> "active"
        YumeStatus.SESSION_ENDED -> "ended"
        YumeStatus.SESSION_CONNECTING -> "connecting"
        YumeStatus.SESSION_WAITING -> "waiting"
        else -> "none"
    }
}
