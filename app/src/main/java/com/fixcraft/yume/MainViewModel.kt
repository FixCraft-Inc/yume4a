package com.fixcraft.yume

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.ServerProfileStore
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val caMaterials: List<SecureMaterialSummary> = emptyList(),
    val authKeyMaterials: List<SecureMaterialSummary> = emptyList(),
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
    private val secureStore = SecureMaterialStore.get(application)
    private val numericAddressPattern = Regex("^[0-9A-Fa-f:.]+$")
    private val _noticeMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MainUiState> = combine(
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
            bannerMessage = when {
                profiles.isEmpty() -> "Add a Yume server profile to start."
                authKeyMaterials.isEmpty() -> "Import a PEM auth key, then assign it to a server before connecting."
                selectedProfile != null && selectedProfile.authKeyMaterialId.isBlank() ->
                    "The selected server still needs an auth key before it can authenticate."
                vpnState.phase == VpnPhase.Connected && vpnState.trafficRoutedThroughYume ->
                    "System traffic is currently routed through Yume. Exit IP: ${vpnState.exitIp ?: "unknown"}."
                vpnState.localProxyAddress != null && vpnState.vpnInterfaceEstablished ->
                    "The Android Yume tunnel is up and the local gateway is ready on ${vpnState.localProxyAddress} while routed traffic is being verified."
                vpnState.localProxyAddress != null ->
                    "The local Yume SOCKS5 gateway is ready on ${vpnState.localProxyAddress} while the Android tunnel is finishing startup."
                vpnState.phase == VpnPhase.BootstrapReady -> "Yume authenticated successfully and is finishing the Android tunnel startup."
                vpnState.phase == VpnPhase.Connected -> "Yume is active and keeping its upstream control socket out of the VPN path to avoid loops."
                else -> null
            },
            noticeMessage = noticeMessage,
        )
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

    fun markPermissionRequired(profileId: String) {
        YumeVpnRuntime.appendLog(VpnLogLevel.Info, "Android needs VPN permission before Yume can create the interface.")
        YumeVpnRuntime.update(
            VpnConnectionState(
                phase = VpnPhase.PermissionRequired,
                activeProfileId = profileId,
                headline = "Permission required",
                detail = "Android needs your approval before Yume can create a VPN interface.",
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
                detail = "Checking local auth material and Android VPN prerequisites for ${selectedProfile.endpointLabel}.",
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
                    detail = "Import a valid anonym CA or switch this server back to the embedded CA before connecting.",
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
                    detail = "Import or paste a PEM private key, then assign it to this server before connecting.",
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
                    detail = "The auth key assigned to this server is no longer available. Edit the server and choose another one.",
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
}
