package com.fixcraft.yume

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AutoTranslationRuntime
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.NativeDirectTechniqueEngine
import com.fixcraft.yume.vpn.Tun2SocksTechniqueEngine
import com.fixcraft.yume.vpn.TunnelRuntimeConfig
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class MainUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val caMaterials: List<SecureMaterialSummary> = emptyList(),
    val authKeyMaterials: List<SecureMaterialSummary> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    val downloadedLanguageTags: Set<String> = emptySet(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val bannerMessage: String? = null,
    val noticeMessage: String? = null,
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ServerProfileStore.get(application)
    private val appSettingsStore = AppSettingsStore.get(application)
    private val secureStore = SecureMaterialStore.get(application)
    private val numericAddressPattern = Regex("^[0-9A-Fa-f:.]+$")
    private val _noticeMessage = MutableStateFlow<String?>(null)
    private val _downloadedLanguageTags = MutableStateFlow<Set<String>>(emptySet())

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
    }

    private val baseUiState: Flow<MainUiState> = combine(
        baseUiStateWithoutNotice,
        _noticeMessage,
    ) { baseState, noticeMessage ->
        baseState.copy(noticeMessage = noticeMessage)
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

    fun saveProfile(
        id: String?,
        name: String,
        host: String,
        port: String,
        sni: String,
        dnsServers: String,
        mtu: String,
        requireAnonymProof: Boolean,
        anonymCaMaterialId: String,
        authKeyMaterialId: String,
        notes: String,
    ): String? {
        val strings = strings()
        val normalizedName = name.trim()
        val normalizedHost = host.trim()
        val normalizedPort = port.trim().ifEmpty { "443" }
        val normalizedSni = sni.trim()
        val normalizedCaMaterialId = anonymCaMaterialId.ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID }
        val normalizedAuthKeyMaterialId = authKeyMaterialId.trim()
        if (normalizedName.isEmpty()) {
            return strings.profileNameRequired
        }
        if (normalizedHost.isEmpty()) {
            return strings.serverHostRequired
        }
        val parsedPort = normalizedPort.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: return strings.portRangeError
        val parsedMtu = mtu.trim().ifEmpty { ServerProfile.DEFAULT_MTU.toString() }
            .toIntOrNull()
            ?.takeIf { it in 1280..1500 }
            ?: return strings.mtuRangeError
        val parsedDnsServers = dnsServers
            .lineSequence()
            .flatMap { line -> line.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { ServerProfile.DEFAULT_DNS_SERVERS }
        if (parsedDnsServers.any { !numericAddressPattern.matches(it) }) {
            return strings.dnsServerFormatError
        }
        if (!secureStore.hasMaterial(SecureMaterialType.AnonymCa, normalizedCaMaterialId)) {
            return strings.anonymCaUnavailable
        }
        if (normalizedAuthKeyMaterialId.isNotBlank() &&
            !secureStore.hasMaterial(SecureMaterialType.AuthKey, normalizedAuthKeyMaterialId)
        ) {
            return strings.authKeyUnavailable
        }

        val targetId = id?.let { store.getProfile(it)?.id } ?: id ?: java.util.UUID.randomUUID().toString()
        store.saveProfile(
            ServerProfile(
                id = targetId,
                name = normalizedName,
                host = normalizedHost,
                port = parsedPort,
                sni = normalizedSni,
                dnsServers = parsedDnsServers,
                mtu = parsedMtu,
                requireAnonymProof = requireAnonymProof,
                anonymCaMaterialId = normalizedCaMaterialId,
                authKeyMaterialId = normalizedAuthKeyMaterialId,
                notes = notes.trim(),
            )
        )
        store.selectProfile(targetId)
        _noticeMessage.value = strings.serverSaved
        return null
    }

    fun deleteProfile(profileId: String) {
        store.deleteProfile(profileId)
    }

    fun selectProfile(profileId: String) {
        store.selectProfile(profileId)
    }

    fun savePastedMaterial(type: SecureMaterialType, displayName: String, pemText: String): String? {
        val strings = strings()
        return runCatching {
            val summary = secureStore.saveMaterialText(type, displayName, pemText, displayName)
            _noticeMessage.value = strings.materialSaved(strings.materialTypeLabel(summary.type), summary.displayName)
            null
        }.getOrElse { throwable ->
            throwable.message ?: strings.failedToSave(strings.materialTypeLabel(type))
        }
    }

    fun importMaterialFromBytes(type: SecureMaterialType, displayName: String, bytes: ByteArray) {
        val strings = strings()
        runCatching {
            val summary = secureStore.saveMaterialBytes(type, displayName, bytes, displayName)
            _noticeMessage.value = strings.materialImported(strings.materialTypeLabel(summary.type), summary.displayName)
        }.onFailure { throwable ->
            _noticeMessage.value = throwable.message ?: strings.failedToImport(strings.materialTypeLabel(type))
        }
    }

    fun deleteMaterial(materialId: String) {
        val strings = strings()
        val removed = secureStore.deleteMaterial(materialId)
        if (removed == null) {
            _noticeMessage.value = strings.embeddedAnonymCaCannotBeRemoved
            return
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

    fun connectSelectedProfile() {
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
        if (!secureStore.hasMaterial(SecureMaterialType.AnonymCa, selectedProfile.anonymCaMaterialId)) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Error, "The selected anonym CA is unavailable.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    activeProfileId = selectedProfile.id,
                    headline = strings.anonymCaMissingHeadline,
                    detail = strings.anonymCaMissingDetail,
                )
            )
            return
        }
        if (selectedProfile.authKeyMaterialId.isBlank()) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Error, "This server does not have an auth key assigned.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    activeProfileId = selectedProfile.id,
                    headline = strings.authKeyRequiredHeadline,
                    detail = strings.authKeyRequiredDetail,
                )
            )
            return
        }
        if (!secureStore.hasMaterial(SecureMaterialType.AuthKey, selectedProfile.authKeyMaterialId)) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Error, "The selected auth key is unavailable.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    activeProfileId = selectedProfile.id,
                    headline = strings.authKeyUnavailableHeadline,
                    detail = strings.authKeyUnavailableDetail,
                )
            )
            return
        }
        val loadError = runCatching {
            secureStore.readMaterialText(selectedProfile.anonymCaMaterialId)
            secureStore.readMaterialText(selectedProfile.authKeyMaterialId)
        }.exceptionOrNull()
        if (loadError != null) {
            YumeVpnRuntime.appendLog(VpnLogLevel.Error, loadError.message ?: "Yume could not load the CA or auth key for this server.")
            YumeVpnRuntime.update(
                VpnConnectionState(
                    phase = VpnPhase.Error,
                    activeProfileId = selectedProfile.id,
                    headline = strings.credentialLoadFailedHeadline,
                    detail = loadError.message ?: strings.credentialLoadFailedDetail,
                )
            )
            return
        }
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Local CA and auth key are ready. Starting the Android VPN service.")
        YumeVpnService.startConnect(getApplication(), selectedProfile.id)
    }

    fun disconnect() {
        YumeVpnService.startDisconnect(getApplication())
    }

    fun buildDiagnosticsReport(): String {
        val strings = strings()
        val current = uiState.value
        val runtimeConfig = resolveRuntimeConfig(current.appSettings)
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
            appendLine("${strings.performanceProfileLabel}: ${strings.performanceProfileName(current.appSettings.performanceProfile)}")
            appendLine("${strings.themeColorsLabel}: ${strings.themeColorModeName(current.appSettings.themeColorMode)}")
            appendLine("${strings.amoledDarkThemeLabel}: ${if (current.appSettings.amoledDarkTheme) strings.on else strings.off}")
            appendLine("${strings.languageLabel}: ${strings.languageSummary(current.appSettings.language)}")
            appendLine(
                "${strings.phaseLabel}: ${
                    strings.phaseStatus(
                        current.vpnState.phase,
                        current.vpnState.trafficRoutedThroughYume,
                        current.vpnState.localProxyAddress != null,
                    )
                }"
            )
            appendLine("${strings.headlineLabel}: ${current.vpnState.headline}")
            appendLine("${strings.detailLabel}: ${current.vpnState.detail}")
            appendLine("${strings.selectedServerReportLabel}: ${current.selectedProfile?.name ?: strings.none}")
            appendLine("${strings.endpointLabel}: ${current.selectedProfile?.endpointLabel ?: strings.none}")
            appendLine("${strings.tlsNameLabel}: ${current.selectedProfile?.serverName ?: strings.none}")
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
            appendLine(runtimeConfig.diagnosticsSummary)
            appendLine()
            appendLine(strings.eventLogTitle)
            if (current.vpnState.eventLog.isEmpty()) {
                appendLine(strings.noSessionEventsRecorded)
            } else {
                current.vpnState.eventLog.forEach { entry ->
                    appendLine("[${entry.timestampEpochMs}] ${entry.level.name} ${entry.message}")
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
        }
    }
}
