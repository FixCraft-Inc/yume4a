package com.fixcraft.yume

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.ThemeColorMode
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

data class MainUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val caMaterials: List<SecureMaterialSummary> = emptyList(),
    val authKeyMaterials: List<SecureMaterialSummary> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
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

    private val baseUiState: Flow<MainUiState> = combine(
        store.profiles,
        store.selectedProfileId,
        secureStore.userMaterials,
        YumeVpnRuntime.state,
        _noticeMessage,
    ) { profiles, selectedProfileId, userMaterials, vpnState, noticeMessage ->
        val caMaterials = secureStore.caMaterials(userMaterials)
        val authKeyMaterials = secureStore.authKeyMaterials(userMaterials)
        val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
        MainUiState(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            caMaterials = caMaterials,
            authKeyMaterials = authKeyMaterials,
            vpnState = vpnState,
            bannerMessage = null,
            noticeMessage = noticeMessage,
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
        val normalizedName = name.trim()
        val normalizedHost = host.trim()
        val normalizedPort = port.trim().ifEmpty { "443" }
        val normalizedSni = sni.trim()
        val normalizedCaMaterialId = anonymCaMaterialId.ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID }
        val normalizedAuthKeyMaterialId = authKeyMaterialId.trim()
        if (normalizedName.isEmpty()) {
            return "Profile name is required"
        }
        if (normalizedHost.isEmpty()) {
            return "Server host is required"
        }
        val parsedPort = normalizedPort.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: return "Port must be between 1 and 65535"
        val parsedMtu = mtu.trim().ifEmpty { ServerProfile.DEFAULT_MTU.toString() }
            .toIntOrNull()
            ?.takeIf { it in 1280..1500 }
            ?: return "MTU must be between 1280 and 1500"
        val parsedDnsServers = dnsServers
            .lineSequence()
            .flatMap { line -> line.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { ServerProfile.DEFAULT_DNS_SERVERS }
        if (parsedDnsServers.any { !numericAddressPattern.matches(it) }) {
            return "DNS servers must be numeric IPv4 or IPv6 addresses"
        }
        if (!secureStore.hasMaterial(SecureMaterialType.AnonymCa, normalizedCaMaterialId)) {
            return "Selected anonym CA is unavailable"
        }
        if (normalizedAuthKeyMaterialId.isNotBlank() &&
            !secureStore.hasMaterial(SecureMaterialType.AuthKey, normalizedAuthKeyMaterialId)
        ) {
            return "Selected auth key is unavailable"
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
        _noticeMessage.value = "Server saved"
        return null
    }

    fun deleteProfile(profileId: String) {
        store.deleteProfile(profileId)
    }

    fun selectProfile(profileId: String) {
        store.selectProfile(profileId)
    }

    fun savePastedMaterial(type: SecureMaterialType, displayName: String, pemText: String): String? {
        return runCatching {
            val summary = secureStore.saveMaterialText(type, displayName, pemText, displayName)
            _noticeMessage.value = "${summary.type.label} saved: ${summary.displayName}"
            null
        }.getOrElse { throwable ->
            throwable.message ?: "Failed to save ${type.label.lowercase()}"
        }
    }

    fun importMaterialFromBytes(type: SecureMaterialType, displayName: String, bytes: ByteArray) {
        runCatching {
            val summary = secureStore.saveMaterialBytes(type, displayName, bytes, displayName)
            _noticeMessage.value = "${summary.type.label} imported: ${summary.displayName}"
        }.onFailure { throwable ->
            _noticeMessage.value = throwable.message ?: "Failed to import ${type.label.lowercase()}"
        }
    }

    fun deleteMaterial(materialId: String) {
        val removed = secureStore.deleteMaterial(materialId)
        if (removed == null) {
            _noticeMessage.value = "The embedded anonym CA cannot be removed"
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
        _noticeMessage.value = "${removed.displayName} removed"
    }

    fun reportUiNotice(message: String) {
        _noticeMessage.value = message
    }

    fun clearNotice() {
        _noticeMessage.value = null
    }

    fun updateConnectionTechnique(technique: ConnectionTechnique) {
        if (technique == ConnectionTechnique.NativeDirectAlpha && !BuildConfig.ENABLE_NATIVE_DIRECT_ALPHA) {
            _noticeMessage.value = "Native direct alpha is only available in debug builds right now."
            return
        }
        appSettingsStore.updateConnectionTechnique(technique)
        _noticeMessage.value = if (uiState.value.vpnState.phase == VpnPhase.Idle || uiState.value.vpnState.phase == VpnPhase.Error) {
            "Technique: ${technique.displayName}"
        } else {
            "Reconnect to apply ${technique.displayName}."
        }
    }

    fun updatePerformanceProfile(profile: PerformanceProfile) {
        appSettingsStore.updatePerformanceProfile(profile)
        _noticeMessage.value = if (uiState.value.vpnState.phase == VpnPhase.Idle || uiState.value.vpnState.phase == VpnPhase.Error) {
            "Profile: ${profile.displayName}"
        } else {
            "Reconnect to apply ${profile.displayName}."
        }
    }

    fun updateThemeColorMode(mode: ThemeColorMode) {
        appSettingsStore.updateThemeColorMode(mode)
        _noticeMessage.value = "Theme: ${mode.displayName}"
    }

    fun markPermissionRequired(profileId: String) {
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android needs VPN permission before Yume can create the interface.")
        YumeVpnRuntime.update(
            VpnConnectionState(
                phase = VpnPhase.PermissionRequired,
                activeProfileId = profileId,
                headline = "Permission required",
                detail = "Android needs permission to start the VPN.",
            )
        )
    }

    fun connectSelectedProfile() {
        val selectedProfile = uiState.value.selectedProfile ?: return
        YumeVpnRuntime.reset(
            VpnConnectionState(
                phase = VpnPhase.Connecting,
                activeProfileId = selectedProfile.id,
                headline = "Preparing",
                detail = "Checking keys and VPN permission for ${selectedProfile.endpointLabel}.",
                endpointLabel = selectedProfile.endpointLabel,
                sessionDnsServers = selectedProfile.dnsServers,
                sessionMtu = selectedProfile.mtu,
                veritySummary = if (selectedProfile.requireAnonymProof) {
                    "Anonym proof required"
                } else {
                    "Anonym proof optional"
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
                    headline = "Anonym CA missing",
                    detail = "Import an anonym CA or switch this server to the embedded CA.",
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
                    headline = "Auth key required",
                    detail = "Import an auth key and assign it to this server.",
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
                    headline = "Auth key unavailable",
                    detail = "The assigned auth key is missing. Edit the server and choose another one.",
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
                    headline = "Credential load failed",
                    detail = loadError.message ?: "Yume could not load the CA or auth key for this server.",
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
        val current = uiState.value
        val runtimeConfig = resolveRuntimeConfig(current.appSettings)
        return buildString {
            appendLine("Yume Android Diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Technique: ${current.appSettings.connectionTechnique.displayName}")
            appendLine("Performance profile: ${current.appSettings.performanceProfile.displayName}")
            appendLine("Theme colors: ${current.appSettings.themeColorMode.displayName}")
            appendLine("Phase: ${current.vpnState.phase.name}")
            appendLine("Headline: ${current.vpnState.headline}")
            appendLine("Detail: ${current.vpnState.detail}")
            appendLine("Selected server: ${current.selectedProfile?.name ?: "None"}")
            appendLine("Endpoint: ${current.selectedProfile?.endpointLabel ?: "None"}")
            appendLine("TLS name: ${current.selectedProfile?.serverName ?: "None"}")
            appendLine("Anonym policy: ${if (current.selectedProfile?.requireAnonymProof != false) "Required" else "Optional"}")
            appendLine("Anonym CA: ${current.selectedCaMaterial?.displayName ?: "Missing"}")
            appendLine("Auth key: ${current.selectedAuthKeyMaterial?.displayName ?: "Not assigned"}")
            appendLine("Upstream network: ${current.vpnState.upstreamNetworkLabel ?: "Unavailable"}")
            appendLine("Exit IP: ${current.vpnState.exitIp ?: "Unknown"}")
            appendLine("Control socket protected: ${current.vpnState.controlSocketProtected}")
            appendLine("VPN interface established: ${current.vpnState.vpnInterfaceEstablished}")
            appendLine("Traffic routed through Yume: ${current.vpnState.trafficRoutedThroughYume}")
            appendLine()
            appendLine("Runtime")
            appendLine(runtimeConfig.diagnosticsSummary)
            appendLine()
            appendLine("Event log")
            if (current.vpnState.eventLog.isEmpty()) {
                appendLine("No session events recorded.")
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
            ConnectionTechnique.NativeDirectAlpha -> {
                if (BuildConfig.ENABLE_NATIVE_DIRECT_ALPHA) {
                    NativeDirectTechniqueEngine.runtimeConfig(settings.performanceProfile)
                } else {
                    Tun2SocksTechniqueEngine.runtimeConfig(settings.performanceProfile)
                }
            }
        }
    }
}
