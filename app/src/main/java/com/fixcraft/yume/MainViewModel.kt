package com.fixcraft.yume

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.Socks5BindMode
import com.fixcraft.yume.data.Socks5ProxySettings
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.canonicalLabel
import com.fixcraft.yume.data.isVpnService
import com.fixcraft.yume.data.supportsUserSelectablePerformanceProfiles
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AutoTranslationRuntime
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.i18n.logStrings
import com.fixcraft.yume.i18n.VpnLogTranslator
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.NativeDirectTechniqueEngine
import com.fixcraft.yume.vpn.Socks5ProxyTechniqueEngine
import com.fixcraft.yume.vpn.Tun2SocksTechniqueEngine
import com.fixcraft.yume.vpn.TunnelRuntimeConfig
import com.fixcraft.yume.vpn.YumeSocks5ProxyService
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BusyUiState(
    val title: String,
    val detail: String? = null,
)

data class MainUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val caMaterials: List<SecureMaterialSummary> = emptyList(),
    val authKeyMaterials: List<SecureMaterialSummary> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    val appDataPasswordEnabled: Boolean = false,
    val downloadedLanguageTags: Set<String> = emptySet(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val bannerMessage: String? = null,
    val noticeMessage: String? = null,
    val busyState: BusyUiState? = null,
) {
    val selectedProfile: ServerProfile? = profiles.firstOrNull { it.id == selectedProfileId }
    val selectedCaMaterial: SecureMaterialSummary? = selectedProfile?.let { profile ->
        caMaterials.firstOrNull { it.id == profile.anonymCaMaterialId }
            ?: caMaterials.firstOrNull { it.id == SecureMaterialStore.DEFAULT_ANONYM_CA_ID }
    }
    val selectedAuthKeyMaterial: SecureMaterialSummary? = selectedProfile?.authKeyMaterialId
        ?.takeIf { it.isNotBlank() }
        ?.let { authId -> authKeyMaterials.firstOrNull { it.id == authId } }
}

private sealed interface ConnectPreflightResult {
    data object Ready : ConnectPreflightResult

    data class Error(
        val headline: String,
        val detail: String,
        val logMessage: String,
    ) : ConnectPreflightResult
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ServerProfileStore.get(application)
    private val appSettingsStore = AppSettingsStore.get(application)
    private val secureStore = SecureMaterialStore.get(application)
    private val numericAddressPattern = Regex("^[0-9A-Fa-f:.]+$")
    private val _noticeMessage = MutableStateFlow<String?>(null)
    private val _busyState = MutableStateFlow<BusyUiState?>(null)
    private val _downloadedLanguageTags = MutableStateFlow<Set<String>>(emptySet())
    private val _appDataPasswordEnabled = MutableStateFlow(AppVaultManager.isPasswordEnabled(application))

    init {
        refreshDownloadedLanguages()
    }

    private val baseUiStateWithoutNotice: Flow<MainUiState> = combine(
        store.profiles,
        store.selectedProfileId,
        secureStore.userMaterials,
        _downloadedLanguageTags,
        YumeVpnRuntime.state,
    ) { profiles, selectedProfileId, userMaterials, downloadedLanguageTags, vpnState ->
        val caMaterials = secureStore.caMaterials(userMaterials)
        val authKeyMaterials = secureStore.authKeyMaterials(userMaterials)
        MainUiState(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            caMaterials = caMaterials,
            authKeyMaterials = authKeyMaterials,
            downloadedLanguageTags = downloadedLanguageTags,
            vpnState = vpnState,
            bannerMessage = null,
        )
    }.combine(_appDataPasswordEnabled) { baseState, appDataPasswordEnabled ->
        baseState.copy(appDataPasswordEnabled = appDataPasswordEnabled)
    }

    private val baseUiState: Flow<MainUiState> = combine(
        baseUiStateWithoutNotice,
        _noticeMessage,
        _busyState,
    ) { baseState, noticeMessage, busyState ->
        baseState.copy(
            noticeMessage = noticeMessage,
            busyState = busyState,
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        baseUiState,
        appSettingsStore.settings,
    ) { baseState, appSettings ->
        baseState.copy(appSettings = appSettings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    private fun strings(language: AppLanguage = appSettingsStore.settings.value.language) =
        appStrings(getApplication(), language)

    private suspend fun <T> runBusy(
        title: String,
        detail: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: () -> T,
    ): Result<T> {
        _busyState.value = BusyUiState(title = title, detail = detail)
        return try {
            Result.success(withContext(dispatcher) { block() })
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            _busyState.value = null
        }
    }

    suspend fun saveProfile(
        id: String?,
        name: String,
        host: String,
        port: String,
        sni: String,
        obfuscation: Boolean,
        obfsSecret: String,
        dnsServers: String,
        mtu: String,
        requireAnonymProof: Boolean,
        anonymCaMaterialId: String,
        authKeyMaterialId: String,
        notes: String,
        tlsStealthProfile: String = ServerProfile.DEFAULT_TLS_STEALTH_PROFILE,
    ): String? {
        val strings = strings()
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return strings.profileNameRequired
        }
        return runBusy(
            title = strings.save,
            detail = strings.savingServer(normalizedName),
        ) {
            val normalizedHost = host.trim()
            val normalizedPort = port.trim().ifEmpty { "443" }
            val normalizedSni = sni.trim()
            val normalizedObfsSecret = obfsSecret.trim()
            val normalizedCaMaterialId = anonymCaMaterialId.ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID }
            val normalizedAuthKeyMaterialId = authKeyMaterialId.trim()
            if (normalizedHost.isEmpty()) {
                return@runBusy strings.serverHostRequired
            }
            val parsedPort = normalizedPort.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return@runBusy strings.portRangeError
            val parsedMtu = mtu.trim().ifEmpty { ServerProfile.DEFAULT_MTU.toString() }
                .toIntOrNull()
                ?.takeIf { it in 1280..1500 }
                ?: return@runBusy strings.mtuRangeError
            val parsedDnsServers = dnsServers
                .lineSequence()
                .flatMap { line -> line.split(',').asSequence() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .ifEmpty { ServerProfile.DEFAULT_DNS_SERVERS }
            if (parsedDnsServers.any { !numericAddressPattern.matches(it) }) {
                return@runBusy strings.dnsServerFormatError
            }
            if (!secureStore.hasMaterial(SecureMaterialType.AnonymCa, normalizedCaMaterialId)) {
                return@runBusy strings.anonymCaUnavailable
            }
            if (normalizedAuthKeyMaterialId.isNotBlank() &&
                !secureStore.hasMaterial(SecureMaterialType.AuthKey, normalizedAuthKeyMaterialId)
            ) {
                return@runBusy strings.authKeyUnavailable
            }

            val targetId = id?.let { store.getProfile(it)?.id } ?: id ?: java.util.UUID.randomUUID().toString()
            store.saveProfile(
                ServerProfile(
                    id = targetId,
                    name = normalizedName,
                    host = normalizedHost,
                    port = parsedPort,
                    sni = normalizedSni,
                    obfuscation = obfuscation,
                    obfsSecret = normalizedObfsSecret,
                    dnsServers = parsedDnsServers,
                    mtu = parsedMtu,
                    requireAnonymProof = requireAnonymProof,
                    anonymCaMaterialId = normalizedCaMaterialId,
                    authKeyMaterialId = normalizedAuthKeyMaterialId,
                    notes = notes.trim(),
                    tlsStealthProfile = tlsStealthProfile.lowercase()
                        .ifBlank { ServerProfile.DEFAULT_TLS_STEALTH_PROFILE }
                        .let { if (it in ServerProfile.TLS_STEALTH_PROFILE_OPTIONS) it
                               else ServerProfile.DEFAULT_TLS_STEALTH_PROFILE },
                )
            )
            store.selectProfile(targetId)
            _noticeMessage.value = strings.serverSaved
            null
        }.getOrElse { throwable ->
            throwable.message ?: strings.failedToSave(strings.serversTitle)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteProfile(profileId)
        }
    }

    fun selectProfile(profileId: String) {
        store.selectProfile(profileId)
    }

    suspend fun savePastedMaterial(type: SecureMaterialType, displayName: String, pemText: String): String? {
        val strings = strings()
        return runBusy(
            title = strings.save,
            detail = strings.saving(strings.materialTypeLabel(type)),
        ) {
            val summary = secureStore.saveMaterialText(type, displayName, pemText, displayName)
            _noticeMessage.value = strings.materialSaved(strings.materialTypeLabel(summary.type), summary.displayName)
            null
        }.getOrElse { throwable ->
            throwable.message ?: strings.failedToSave(strings.materialTypeLabel(type))
        }
    }

    suspend fun savePastedAuthKey(displayName: String, rawText: String, encrypted: Boolean, password: String): String? {
        val strings = strings()
        return runBusy(
            title = strings.save,
            detail = strings.saving(strings.authKeyLabel),
        ) {
            val summary = secureStore.saveImportedAuthKeyText(
                displayName = displayName,
                rawText = rawText,
                encrypted = encrypted,
                password = password,
                sourceLabel = displayName,
            )
            _noticeMessage.value = strings.materialSaved(strings.materialTypeLabel(summary.type), summary.displayName)
            null
        }.getOrElse { throwable ->
            throwable.message ?: strings.failedToSave(strings.authKeyLabel)
        }
    }

    suspend fun importMaterialFromBytes(type: SecureMaterialType, displayName: String, bytes: ByteArray) {
        val strings = strings()
        runBusy(
            title = strings.importFile,
            detail = strings.importing(strings.materialTypeLabel(type)),
        ) {
            val summary = secureStore.saveMaterialBytes(type, displayName, bytes, displayName)
            _noticeMessage.value = strings.materialImported(strings.materialTypeLabel(summary.type), summary.displayName)
        }.onFailure { throwable ->
            _noticeMessage.value = throwable.message ?: strings.failedToImport(strings.materialTypeLabel(type))
        }
    }

    suspend fun importAuthKeyFromBytes(displayName: String, bytes: ByteArray, encrypted: Boolean, password: String): String? {
        val strings = strings()
        return runBusy(
            title = strings.importFile,
            detail = strings.importing(strings.authKeyLabel),
        ) {
            val summary = secureStore.saveImportedAuthKeyBytes(
                displayName = displayName,
                bytes = bytes,
                encrypted = encrypted,
                password = password,
                sourceLabel = displayName,
            )
            _noticeMessage.value = strings.materialImported(strings.materialTypeLabel(summary.type), summary.displayName)
            null
        }.getOrElse { throwable ->
            throwable.message ?: strings.failedToImport(strings.authKeyLabel)
        }
    }

    suspend fun validateSelectedEncryptedAuthKey(password: String): String? {
        val strings = strings()
        val selectedProfile = uiState.value.selectedProfile ?: return null
        if (password.isBlank()) {
            return strings.passwordRequired
        }
        val authKeyId = selectedProfile.authKeyMaterialId.takeIf { it.isNotBlank() } ?: return strings.authKeyRequiredDetail
        val authKeySummary = secureStore.getSummary(authKeyId) ?: return strings.authKeyUnavailableDetail
        return runBusy(
            title = strings.unlockAuthKeyTitle,
            detail = strings.unlockingAuthKey(authKeySummary.displayName),
        ) {
            secureStore.readAuthKeyPem(authKeyId, password)
            null
        }.getOrElse { throwable ->
            authKeyUnlockDetail(authKeySummary.displayName, throwable, strings)
        }
    }

    fun deleteMaterial(materialId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val strings = strings()
            val removed = secureStore.deleteMaterial(materialId)
            if (removed == null) {
                _noticeMessage.value = strings.embeddedAnonymCaCannotBeRemoved
                return@launch
            }
            store.profiles.value.forEach { profile ->
                val updatedProfile = when {
                    profile.anonymCaMaterialId == removed.id -> {
                        profile.copy(anonymCaMaterialId = SecureMaterialStore.DEFAULT_ANONYM_CA_ID)
                    }

                    profile.authKeyMaterialId == removed.id -> {
                        profile.copy(authKeyMaterialId = "")
                    }

                    else -> null
                }
                if (updatedProfile != null) {
                    store.saveProfile(updatedProfile)
                }
            }
            _noticeMessage.value = strings.removed(removed.displayName)
        }
    }

    fun reportUiNotice(message: String) {
        _noticeMessage.value = message
    }

    fun clearNotice() {
        _noticeMessage.value = null
    }

    fun updateConnectionTechnique(technique: ConnectionTechnique) {
        val strings = strings()
        if (technique == ConnectionTechnique.NativeDirectBeta && !BuildConfig.ENABLE_NATIVE_DIRECT_BETA) {
            _noticeMessage.value = strings.nativeDirectUnavailable
            return
        }
        appSettingsStore.updateConnectionTechnique(technique)
        _noticeMessage.value = if (uiState.value.vpnState.phase == VpnPhase.Idle || uiState.value.vpnState.phase == VpnPhase.Error) {
            strings.connectionTechniqueName(technique)
        } else {
            strings.reconnectFor(strings.connectionTechniqueName(technique))
        }
    }

    fun updatePerformanceProfile(profile: PerformanceProfile) {
        val strings = strings()
        appSettingsStore.updatePerformanceProfile(profile)
        _noticeMessage.value = if (uiState.value.vpnState.phase == VpnPhase.Idle || uiState.value.vpnState.phase == VpnPhase.Error) {
            strings.performanceProfileName(profile)
        } else {
            strings.reconnectFor(strings.performanceProfileName(profile))
        }
    }

    fun updateThemeColorMode(mode: ThemeColorMode) {
        val strings = strings()
        appSettingsStore.updateThemeColorMode(mode)
        _noticeMessage.value = strings.themeColorModeName(mode)
    }

    fun updateAmoledDarkTheme(enabled: Boolean) {
        val strings = strings()
        appSettingsStore.updateAmoledDarkTheme(enabled)
        _noticeMessage.value = if (enabled) strings.amoledOn else strings.amoledOff
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

    fun updateLogLanguage(language: LogLanguage) {
        val strings = strings()
        appSettingsStore.updateLogLanguage(language)
        _noticeMessage.value = strings.logLanguageSummary(language)
    }

    fun updateAdvancedMode(enabled: Boolean) {
        val strings = strings()
        appSettingsStore.updateAdvancedMode(enabled)
        _noticeMessage.value = if (enabled) strings.advancedModeOn else strings.advancedModeOff
    }

    suspend fun enableAppDataPassword(password: String, confirmPassword: String): String? {
        val strings = strings()
        if (password.isBlank()) {
            return strings.passwordRequired
        }
        if (password.length < 6) {
            return strings.passwordTooShort
        }
        if (password != confirmPassword) {
            return strings.passwordsDoNotMatch
        }
        return runBusy(
            title = strings.setPasswordButton,
            detail = strings.updatingAppDataProtection,
        ) {
            AppVaultManager.enablePassword(getApplication(), password)
        }.getOrElse { throwable ->
            Result.failure<Unit>(throwable)
        }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = true
                    _noticeMessage.value = strings.appDataPasswordEnabled
                    null
                },
                onFailure = { it.message ?: strings.failedToEnableAppDataPassword },
            )
    }

    suspend fun changeAppDataPassword(currentPassword: String, newPassword: String, confirmPassword: String): String? {
        val strings = strings()
        if (currentPassword.isBlank()) {
            return strings.currentPasswordRequired
        }
        if (newPassword.isBlank()) {
            return strings.newPasswordRequired
        }
        if (newPassword.length < 6) {
            return strings.passwordTooShort
        }
        if (newPassword != confirmPassword) {
            return strings.passwordsDoNotMatch
        }
        return runBusy(
            title = strings.changePasswordButton,
            detail = strings.updatingAppDataProtection,
        ) {
            AppVaultManager.changePassword(getApplication(), currentPassword, newPassword)
        }.getOrElse { throwable ->
            Result.failure<Unit>(throwable)
        }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = true
                    _noticeMessage.value = strings.appDataPasswordChanged
                    null
                },
                onFailure = { it.message ?: strings.failedToChangeAppDataPassword },
            )
    }

    suspend fun disableAppDataPassword(currentPassword: String): String? {
        val strings = strings()
        if (currentPassword.isBlank()) {
            return strings.currentPasswordRequired
        }
        return runBusy(
            title = strings.removePasswordButton,
            detail = strings.updatingAppDataProtection,
        ) {
            AppVaultManager.disablePassword(getApplication(), currentPassword)
        }.getOrElse { throwable ->
            Result.failure<Unit>(throwable)
        }
            .fold(
                onSuccess = {
                    _appDataPasswordEnabled.value = false
                    _noticeMessage.value = strings.appDataPasswordDisabled
                    null
                },
                onFailure = { it.message ?: strings.failedToDisableAppDataPassword },
            )
    }

    fun refreshDownloadedLanguages() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadedLanguageTags.value = AutoTranslationRuntime.refreshDownloadedLocaleTags()
        }
    }

    fun deleteDownloadedLanguage(language: AppLanguage.AutoTranslated) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = AutoTranslationRuntime.deleteDownloadedLanguage(getApplication(), language.localeTag)
            _downloadedLanguageTags.value = AutoTranslationRuntime.refreshDownloadedLocaleTags()
            val strings = strings()
            val languageName = AppLanguageCatalog.displayName(language)
            _noticeMessage.value = if (deleted) {
                strings.deletedLanguageDownload(languageName)
            } else {
                strings.failedToDeleteLanguageDownload(languageName)
            }
        }
    }

    fun markPermissionRequired(profileId: String) {
        val strings = strings()
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android needs VPN permission before Yume can create the interface.")
        YumeVpnRuntime.update(
            VpnConnectionState(
                phase = VpnPhase.PermissionRequired,
                activeProfileId = profileId,
                headline = strings.permissionRequiredHeadline,
                detail = strings.permissionRequiredDetail,
            )
        )
    }

    fun connectSelectedProfile(authKeyPassword: String? = null) {
        val strings = strings()
        val selectedProfile = uiState.value.selectedProfile ?: return
        YumeVpnRuntime.reset(
            VpnConnectionState(
                phase = VpnPhase.Connecting,
                activeProfileId = selectedProfile.id,
                headline = strings.preparingHeadline,
                detail = strings.checkingKeysFor(selectedProfile.endpointLabel),
                endpointLabel = selectedProfile.endpointLabel,
                sessionDnsServers = selectedProfile.dnsServers,
                sessionMtu = selectedProfile.mtu,
                veritySummary = if (selectedProfile.requireAnonymProof) {
                    strings.anonymSummary(required = true)
                } else {
                    strings.anonymSummary(required = false)
                },
            )
        )
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Selected server: ${selectedProfile.name} (${selectedProfile.endpointLabel}).")
        viewModelScope.launch {
            val preflight = runBusy(
                title = strings.preparingHeadline,
                detail = strings.loadingKeysFor(selectedProfile.endpointLabel),
            ) {
                connectPreflight(selectedProfile, strings, authKeyPassword)
            }.getOrElse { throwable ->
                ConnectPreflightResult.Error(
                    headline = strings.credentialLoadFailedHeadline,
                    detail = throwable.message ?: strings.credentialLoadFailedDetail,
                    logMessage = throwable.message ?: "Yume could not load the CA or auth key for this server.",
                )
            }

            when (preflight) {
                ConnectPreflightResult.Ready -> {
                    val settings = uiState.value.appSettings
                    if (settings.connectionTechnique.isVpnService()) {
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Local CA and auth key are ready. Starting the Android VPN service.")
                        YumeVpnService.startConnect(getApplication(), selectedProfile.id, authKeyPassword)
                    } else {
                        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Local CA and auth key are ready. Starting the Yume SOCKS5 proxy service.")
                        YumeSocks5ProxyService.startConnect(getApplication(), selectedProfile.id, authKeyPassword)
                    }
                }

                is ConnectPreflightResult.Error -> {
                    YumeVpnRuntime.appendLog(VpnLogLevel.Error, preflight.logMessage)
                    YumeVpnRuntime.update(
                        VpnConnectionState(
                            phase = VpnPhase.Error,
                            activeProfileId = selectedProfile.id,
                            headline = preflight.headline,
                            detail = preflight.detail,
                        )
                    )
                }
            }
        }
    }

    fun disconnect() {
        // The active service is whichever one was started for the
        // current technique; dispatch to both so we don't need to track
        // it explicitly. Each service short-circuits when it has no
        // active session.
        YumeVpnService.startDisconnect(getApplication())
        YumeSocks5ProxyService.startDisconnect(getApplication())
    }

    fun updateSocks5ProxySettings(settings: Socks5ProxySettings) {
        appSettingsStore.updateSocks5ProxySettings(settings)
    }

    fun updateSocks5ProxyPort(port: Int) {
        val current = uiState.value.appSettings.socks5ProxySettings
        appSettingsStore.updateSocks5ProxySettings(current.copy(port = port))
    }

    fun updateSocks5BindMode(mode: Socks5BindMode) {
        val current = uiState.value.appSettings.socks5ProxySettings
        appSettingsStore.updateSocks5ProxySettings(current.copy(bindMode = mode, customBindAddress = ""))
    }

    fun updateSocks5BindAddress(address: String) {
        val current = uiState.value.appSettings.socks5ProxySettings
        appSettingsStore.updateSocks5ProxySettings(current.copy(customBindAddress = address))
    }

    fun updateSocks5TunnelCount(count: Int) {
        val current = uiState.value.appSettings.socks5ProxySettings
        appSettingsStore.updateSocks5ProxySettings(current.copy(tunnelCount = count))
    }

    private fun connectPreflight(
        selectedProfile: ServerProfile,
        strings: com.fixcraft.yume.i18n.AppStrings,
        authKeyPassword: String?,
    ): ConnectPreflightResult {
        if (!secureStore.hasMaterial(SecureMaterialType.AnonymCa, selectedProfile.anonymCaMaterialId)) {
            return ConnectPreflightResult.Error(
                headline = strings.anonymCaMissingHeadline,
                detail = strings.anonymCaMissingDetail,
                logMessage = "The selected anonym CA is unavailable.",
            )
        }
        if (selectedProfile.authKeyMaterialId.isBlank()) {
            return ConnectPreflightResult.Error(
                headline = strings.authKeyRequiredHeadline,
                detail = strings.authKeyRequiredDetail,
                logMessage = "This server does not have an auth key assigned.",
            )
        }
        val authKeySummary = secureStore.getSummary(selectedProfile.authKeyMaterialId)
            ?: return ConnectPreflightResult.Error(
                headline = strings.authKeyUnavailableHeadline,
                detail = strings.authKeyUnavailableDetail,
                logMessage = "The selected auth key is unavailable.",
            )
        if (authKeySummary.importedEncrypted && authKeyPassword.isNullOrBlank()) {
            return ConnectPreflightResult.Error(
                headline = strings.unlockAuthKeyTitle,
                detail = strings.unlockAuthKeyDetail(authKeySummary.displayName),
                logMessage = "Password required for encrypted auth key ${authKeySummary.displayName}.",
            )
        }
        val loadError = runCatching {
            secureStore.readMaterialText(selectedProfile.anonymCaMaterialId)
            secureStore.readAuthKeyPem(selectedProfile.authKeyMaterialId, authKeyPassword)
        }.exceptionOrNull()
        if (loadError != null) {
            return ConnectPreflightResult.Error(
                headline = strings.credentialLoadFailedHeadline,
                detail = authKeyUnlockDetail(authKeySummary.displayName, loadError, strings),
                logMessage = loadError.message ?: "Yume could not load the CA or auth key for this server.",
            )
        }
        return ConnectPreflightResult.Ready
    }

    private fun authKeyUnlockDetail(
        authKeyDisplayName: String,
        throwable: Throwable,
        strings: com.fixcraft.yume.i18n.AppStrings,
    ): String {
        return when (throwable.message) {
            "Password required for encrypted auth key" -> strings.unlockAuthKeyDetail(authKeyDisplayName)
            "Bad password or unsupported encrypted auth key" -> strings.authKeyUnlockFailed
            else -> throwable.message ?: strings.credentialLoadFailedDetail
        }
    }

    fun buildDiagnosticsReport(): String {
        val strings = strings()
        val current = uiState.value
        val logStrings = logStrings(getApplication(), current.appSettings)
        val runtimeConfig = resolveRuntimeConfig(current.appSettings)
        val profileSummary = effectiveProfileSummary(current.appSettings, strings)
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: strings.unknown
        val securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() } ?: strings.unknown
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: strings.unknown
        val brand = Build.BRAND?.takeIf { it.isNotBlank() } ?: strings.unknown
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: strings.unknown
        val deviceCode = Build.DEVICE?.takeIf { it.isNotBlank() } ?: strings.unknown
        val product = Build.PRODUCT?.takeIf { it.isNotBlank() } ?: strings.unknown
        val hardware = Build.HARDWARE?.takeIf { it.isNotBlank() } ?: strings.unknown
        val supportedAbis = Build.SUPPORTED_ABIS
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?.ifBlank { strings.unknown }
            ?: strings.unknown
        val buildFingerprint = Build.FINGERPRINT?.takeIf { it.isNotBlank() } ?: strings.unknown
        return buildString {
            appendLine(strings.diagnosticsReportHeader)
            appendLine(strings.appVersion("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            appendLine()
            appendLine(strings.deviceTitle)
            appendLine("${strings.osLabel}: Android")
            appendLine("${strings.androidVersionLabel}: $androidVersion")
            appendLine("${strings.apiLevelLabel}: ${Build.VERSION.SDK_INT}")
            appendLine("${strings.securityPatchLabel}: $securityPatch")
            appendLine("${strings.manufacturerLabel}: $manufacturer")
            appendLine("${strings.brandLabel}: $brand")
            appendLine("${strings.modelLabel}: $model")
            appendLine("${strings.deviceCodeLabel}: $deviceCode")
            appendLine("${strings.productLabel}: $product")
            appendLine("${strings.hardwareLabel}: $hardware")
            appendLine("${strings.supportedAbisLabel}: $supportedAbis")
            appendLine("${strings.buildFingerprintLabel}: $buildFingerprint")
            appendLine()
            appendLine("${strings.techniqueLabel}: ${strings.connectionTechniqueName(current.appSettings.connectionTechnique)}")
            appendLine("${strings.performanceProfileLabel}: $profileSummary")
            appendLine("${strings.themeColorsLabel}: ${strings.themeColorModeName(current.appSettings.themeColorMode)}")
            appendLine("${strings.amoledDarkThemeLabel}: ${if (current.appSettings.amoledDarkTheme) strings.on else strings.off}")
            appendLine("${strings.languageLabel}: ${strings.languageSummary(current.appSettings.language)}")
            if (current.appSettings.advancedMode) {
                appendLine(
                    "${strings.logLanguageLabel}: ${
                        strings.logLanguageSummary(current.appSettings.effectiveLogLanguage())
                    }"
                )
            }
            appendLine(
                "${strings.phaseLabel}: ${
                    strings.phaseStatus(
                        current.vpnState.phase,
                        current.vpnState.trafficRoutedThroughYume,
                        current.vpnState.localProxyAddress != null,
                    )
                }"
            )
            appendLine("${strings.headlineLabel}: ${strings.localizedHeadline(current.vpnState.headline)}")
            appendLine("${strings.detailLabel}: ${strings.localizedDetail(current.vpnState.detail)}")
            appendLine("${strings.selectedServerReportLabel}: ${current.selectedProfile?.name ?: strings.none}")
            appendLine("${strings.endpointLabel}: ${current.selectedProfile?.endpointLabel ?: strings.none}")
            appendLine("${strings.tlsNameLabel}: ${current.selectedProfile?.serverName ?: strings.none}")
            appendLine("${strings.httpsMaskingLabel}: ${strings.boolValue(current.selectedProfile?.obfuscation == true)}")
            appendLine("${strings.anonymPolicyLabel}: ${if (current.selectedProfile?.requireAnonymProof != false) strings.anonymRequired else strings.anonymOptional}")
            appendLine("${strings.anonymCaLabel}: ${current.selectedCaMaterial?.displayName ?: strings.missing}")
            appendLine("${strings.authKeyLabel}: ${current.selectedAuthKeyMaterial?.displayName ?: strings.notAssigned}")
            appendLine("${strings.upstreamNetworkReportLabel}: ${current.vpnState.upstreamNetworkLabel ?: strings.unavailable}")
            appendLine("${strings.exitIpLabel}: ${current.vpnState.exitIp ?: strings.unknown}")
            appendLine("${strings.controlSocketProtectedLabel}: ${strings.boolValue(current.vpnState.controlSocketProtected)}")
            appendLine("${strings.vpnInterfaceEstablishedLabel}: ${strings.boolValue(current.vpnState.vpnInterfaceEstablished)}")
            appendLine("${strings.trafficRoutedLabel}: ${strings.boolValue(current.vpnState.trafficRoutedThroughYume)}")
            appendLine()
            appendLine(strings.runtimeTitle)
            appendLine(buildRuntimeDiagnostics(runtimeConfig, strings))
            appendLine()
            appendLine(strings.eventLogTitle)
            if (current.vpnState.eventLog.isEmpty()) {
                appendLine(strings.noSessionEventsRecorded)
            } else {
                current.vpnState.eventLog.forEach { entry ->
                    appendLine("[${entry.timestampEpochMs}] ${entry.level.name} ${VpnLogTranslator.translate(entry.message, logStrings)}")
                }
            }
        }.trim()
    }

    private fun resolveRuntimeConfig(settings: AppSettings): TunnelRuntimeConfig {
        return when (settings.connectionTechnique) {
            ConnectionTechnique.Tun2SocksStable -> Tun2SocksTechniqueEngine.runtimeConfig(settings.performanceProfile)
            ConnectionTechnique.NativeDirectBeta -> {
                if (BuildConfig.ENABLE_NATIVE_DIRECT_BETA) {
                    NativeDirectTechniqueEngine.runtimeConfig(settings.performanceProfile)
                } else {
                    Tun2SocksTechniqueEngine.runtimeConfig(settings.performanceProfile)
                }
            }
            ConnectionTechnique.Socks5ProxyOnly ->
                Socks5ProxyTechniqueEngine.runtimeConfig(settings.performanceProfile, settings.socks5ProxySettings)
        }
    }

    private fun buildRuntimeDiagnostics(runtimeConfig: TunnelRuntimeConfig, strings: com.fixcraft.yume.i18n.AppStrings): String {
        fun formatMs(value: Long): String = "${value}ms"
        fun formatKiB(value: Int): String = "${value / 1024} KiB"

        fun buildBridgeLines(config: com.fixcraft.yume.vpn.Tun2SocksRuntimeConfig): List<String> = listOf(
            "${strings.techniqueLabel}: ${strings.connectionTechniqueName(config.technique)}",
            "${strings.profileLabel}: ${strings.performanceProfileName(config.performanceProfile)}",
            "${strings.runtimeTcpRelaysLabel}: ${config.maxActiveTcpRelays}",
            "${strings.runtimeRelayAcquireTimeoutLabel}: ${formatMs(config.relayAcquireTimeoutMs)}",
            "${strings.runtimeRelayAcquireSliceLabel}: ${formatMs(config.relayAcquireSliceMs)}",
            "${strings.runtimeIdleTcpEvictionLabel}: ${formatMs(config.idleTcpEvictionMs)}",
            "${strings.runtimeTcpEvictionGraceLabel}: ${formatMs(config.tcpEvictionGraceMs)}",
            "${strings.runtimeTcpEvictionBatchLabel}: ${config.tcpEvictionBatchSize}",
            "${strings.runtimeBridgeChunkLabel}: ${formatKiB(config.bridgeChunkBytes)}",
            "${strings.runtimeTcpStreamBufferLabel}: ${formatKiB(config.tcpStreamBufferBytes)}",
            "${strings.runtimeUdpStreamBufferLabel}: ${formatKiB(config.udpStreamBufferBytes)}",
            "${strings.runtimeOutboundFrameQueueLabel}: ${config.outboundFrameQueueCapacity}",
            "${strings.runtimeTun2SocksConnectTimeoutLabel}: ${config.tun2SocksConnectTimeoutMs}ms",
            "${strings.runtimeTun2SocksTcpReadWriteTimeoutLabel}: ${config.tun2SocksTcpReadWriteTimeoutMs}ms",
            "${strings.runtimeTun2SocksUdpReadWriteTimeoutLabel}: ${config.tun2SocksUdpReadWriteTimeoutMs}ms",
            "${strings.runtimeMaxDnsUdpAssociationsLabel}: ${config.maxDnsUdpAssociations}",
            "${strings.runtimeMaxUdpAssociationsLabel}: ${config.maxUdpAssociations}",
            "${strings.runtimeDnsRelayConcurrencyLabel}: ${config.dnsRelayMaxConcurrency}",
            "${strings.runtimePreRoutingStabilityDelayLabel}: ${formatMs(config.preRoutingStabilityDelayMs)}",
            "${strings.runtimePostRoutingTrafficRampLabel}: ${formatMs(config.postRoutingTrafficRampDelayMs)}",
            "${strings.runtimeUdp443PolicyLabel}: ${strings.runtimeForcedTcpFallback}",
        )

        val lines = when (runtimeConfig) {
            is com.fixcraft.yume.vpn.Tun2SocksRuntimeConfig -> buildBridgeLines(runtimeConfig)
            is com.fixcraft.yume.vpn.Socks5ProxyRuntimeConfig -> buildList {
                val bindLabel = runtimeConfig.proxySettings.normalizedCustomBindAddress
                    .ifBlank { runtimeConfig.proxySettings.bindMode.canonicalLabel() }
                add("${strings.techniqueLabel}: ${strings.connectionTechniqueName(runtimeConfig.technique)}")
                add("${strings.profileLabel}: ${strings.performanceProfileName(runtimeConfig.performanceProfile)}")
                add("${strings.socks5ListenPortLabel}: ${runtimeConfig.proxySettings.normalizedPort}")
                add("${strings.socks5BindAddressLabel}: $bindLabel")
                add(strings.socks5OnlyRuntimeDetail)
                add("")
                add(strings.runtimeAppliedBridgeTuningTitle)
                addAll(buildBridgeLines(runtimeConfig.bridgeTuning).drop(2))
            }
            is com.fixcraft.yume.vpn.NativeDirectRuntimeConfig -> buildList {
                add("${strings.techniqueLabel}: ${strings.connectionTechniqueName(runtimeConfig.technique)}")
                add("${strings.profileLabel}: ${strings.nativeDirectFixedTuningName}")
                add(
                    "${strings.runtimeStatusLabel}: ${
                        when (runtimeConfig.engineStatus) {
                            com.fixcraft.yume.vpn.NativeDirectEngineStatus.CompatibilityBridgeFallback ->
                                strings.runtimeCompatibilityBridgeFallbackStatus

                            com.fixcraft.yume.vpn.NativeDirectEngineStatus.PacketNative ->
                                strings.runtimePacketNativeStatus
                        }
                    }"
                )
                add("${strings.runtimeNativeQueueLabel}: ${runtimeConfig.nativeQueueCapacity}")
                add("${strings.runtimeNativeTcpFlowWindowLabel}: ${formatKiB(runtimeConfig.tcpFlowWindowBytes)}")
                add("${strings.runtimeNativeUdpFlowBufferLabel}: ${formatKiB(runtimeConfig.udpFlowBufferBytes)}")
                add(
                    "${strings.runtimeIpv6PolicyLabel}: ${
                        if (runtimeConfig.ipv6FailClosed) strings.runtimeIpv6FailClosedStatus
                        else strings.enabled
                    }"
                )
                add("")
                add(strings.runtimeAppliedBridgeTuningTitle)
                addAll(buildBridgeLines(runtimeConfig.bridgeTuning).drop(2))
            }
        }
        return lines.joinToString("\n")
    }

    private fun effectiveProfileSummary(
        settings: AppSettings,
        strings: com.fixcraft.yume.i18n.AppStrings,
    ): String {
        return if (settings.connectionTechnique.supportsUserSelectablePerformanceProfiles()) {
            strings.performanceProfileName(settings.performanceProfile)
        } else {
            strings.nativeDirectFixedTuningName
        }
    }
}
