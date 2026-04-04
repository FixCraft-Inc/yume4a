package com.fixcraft.yume.ui.home

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.vpn.VpnLogEntry
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ProfileDraft(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "443",
    val sni: String = "",
    val dnsServers: String = "1.1.1.1, 9.9.9.9",
    val mtu: String = "1500",
    val requireAnonymProof: Boolean = true,
    val anonymCaMaterialId: String = SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
    val authKeyMaterialId: String = "",
    val notes: String = "",
)

internal enum class MaterialTextKind {
    AnonymCa,
    AuthKey,
}

internal data class MaterialTextDraft(
    val kind: MaterialTextKind,
    val title: String,
    val name: String,
    val content: String = "",
)

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: (String?, String, String, String, String, String, String, Boolean, String, String, String) -> String?,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onSaveCaText: (String, String) -> String?,
    onSaveAuthKeyText: (String, String) -> String?,
    onDeleteMaterial: (String) -> Unit,
    onClearNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var editorDraft by remember { mutableStateOf<ProfileDraft?>(null) }
    var materialDraft by remember { mutableStateOf<MaterialTextDraft?>(null) }
    var showStatusDialog by remember { mutableStateOf(false) }
    val hasProfiles = uiState.profiles.isNotEmpty()

    LaunchedEffect(uiState.bannerMessage) {
        uiState.bannerMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(uiState.noticeMessage) {
        uiState.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (hasProfiles) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editorDraft = ProfileDraft(
                            anonymCaMaterialId = uiState.caMaterials.firstOrNull()?.id
                                ?: SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("Add server")
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.80f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.44f),
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    IntroBlock(
                        title = if (hasProfiles) "Your Yume tunnel" else "Set up Yume VPN",
                        subtitle = if (hasProfiles) {
                            "Save trust and auth material locally, pick a gateway, and bring up a loop-safe systemwide Yume VPN on Android."
                        } else {
                            "Import trust or auth material at any time, then add your first server profile."
                        },
                    )
                }

                if (!hasProfiles) {
                    item {
                        EmptyStateCard(
                            vpnPhase = uiState.vpnState.phase,
                            vpnHeadline = uiState.vpnState.headline,
                            vpnDetail = uiState.vpnState.detail,
                            onAddServer = {
                                editorDraft = ProfileDraft(
                                    anonymCaMaterialId = uiState.caMaterials.firstOrNull()?.id
                                        ?: SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
                                )
                            },
                        )
                    }
                } else {
                    uiState.selectedProfile?.let { selectedProfile ->
                        item {
                            SelectedServerCard(
                                profile = selectedProfile,
                                selectedCa = uiState.selectedCaMaterial,
                                selectedAuthKey = uiState.selectedAuthKeyMaterial,
                                availableAuthKeyCount = uiState.authKeyMaterials.size,
                                uiState = uiState,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                                onShowStatus = { showStatusDialog = true },
                                onEditServer = {
                                    editorDraft = selectedProfile.toDraft()
                                },
                            )
                        }
                    }
                }

                item {
                    SecureMaterialsSection(
                        caMaterials = uiState.caMaterials,
                        authKeyMaterials = uiState.authKeyMaterials,
                        selectedCaMaterialId = uiState.selectedProfile?.anonymCaMaterialId,
                        selectedAuthKeyMaterialId = uiState.selectedProfile?.authKeyMaterialId,
                        onImportCaFile = onImportCaFile,
                        onImportAuthKeyFile = onImportAuthKeyFile,
                        onPasteCa = {
                            materialDraft = MaterialTextDraft(
                                kind = MaterialTextKind.AnonymCa,
                                title = "Paste anonym CA",
                                name = "Imported CA",
                            )
                        },
                        onPasteAuthKey = {
                            materialDraft = MaterialTextDraft(
                                kind = MaterialTextKind.AuthKey,
                                title = "Paste auth key",
                                name = "Imported auth key",
                            )
                        },
                        onDeleteMaterial = onDeleteMaterial,
                    )
                }

                if (hasProfiles) {
                    item {
                        ServersSection(
                            profiles = uiState.profiles,
                            selectedProfileId = uiState.selectedProfileId,
                            activeProfileId = uiState.vpnState.activeProfileId,
                            vpnPhase = uiState.vpnState.phase,
                            onSelectProfile = onSelectProfile,
                            onDeleteProfile = onDeleteProfile,
                            onEditProfile = { profile ->
                                editorDraft = profile.toDraft()
                            },
                        )
                    }
                }

                item {
                    BuildStampFooter()
                }
            }
        }
    }

    editorDraft?.let { draft ->
        ProfileEditorDialog(
            draft = draft,
            caMaterials = uiState.caMaterials,
            authKeyMaterials = uiState.authKeyMaterials,
            onDismiss = { editorDraft = null },
            onSave = { updated ->
                val error = onSaveProfile(
                    updated.id,
                    updated.name,
                    updated.host,
                    updated.port,
                    updated.sni,
                    updated.dnsServers,
                    updated.mtu,
                    updated.requireAnonymProof,
                    updated.anonymCaMaterialId,
                    updated.authKeyMaterialId,
                    updated.notes,
                )
                if (error == null) {
                    editorDraft = null
                }
                error
            },
        )
    }

    materialDraft?.let { draft ->
        MaterialTextDialog(
            draft = draft,
            onDismiss = { materialDraft = null },
            onSave = { updated ->
                val error = when (updated.kind) {
                    MaterialTextKind.AnonymCa -> onSaveCaText(updated.name, updated.content)
                    MaterialTextKind.AuthKey -> onSaveAuthKeyText(updated.name, updated.content)
                }
                if (error == null) {
                    materialDraft = null
                }
                error
            },
        )
    }

    if (showStatusDialog) {
        StatusDialog(
            uiState = uiState,
            onDismiss = { showStatusDialog = false },
        )
    }
}

@Composable
private fun IntroBlock(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyStateCard(
    vpnPhase: VpnPhase,
    vpnHeadline: String,
    vpnDetail: String,
    onAddServer: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPill(
                text = "No servers configured",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Add your first gateway",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Server profiles hold the host, TLS name, DNS, MTU, selected anonym CA, and selected auth key. Saving the first profile auto-selects it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vpnPhase != VpnPhase.Idle) {
                StatusMessage(
                    headline = vpnHeadline,
                    detail = vpnDetail,
                )
            }
            Button(
                onClick = onAddServer,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text("Add server")
            }
        }
    }
}

@Composable
private fun SelectedServerCard(
    profile: ServerProfile,
    selectedCa: SecureMaterialSummary?,
    selectedAuthKey: SecureMaterialSummary?,
    availableAuthKeyCount: Int,
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onShowStatus: () -> Unit,
    onEditServer: () -> Unit,
) {
    val phase = uiState.vpnState.phase
    val authReady = selectedAuthKey != null
    val showConnect = authReady &&
        phase != VpnPhase.Connecting &&
        phase != VpnPhase.Verifying &&
        phase != VpnPhase.Connected &&
        phase != VpnPhase.Disconnecting
    val showDisconnect = phase == VpnPhase.Connecting ||
        phase == VpnPhase.Verifying ||
        phase == VpnPhase.Connected ||
        phase == VpnPhase.Disconnecting
    val connectLabel = when (phase) {
        VpnPhase.Error -> "Try again"
        VpnPhase.PermissionRequired -> "Continue"
        else -> "Connect"
    }

    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = profile.endpointLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = uiState.vpnState.briefStatus,
                    containerColor = if (phase == VpnPhase.Verifying || phase == VpnPhase.Connected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (phase == VpnPhase.Verifying || phase == VpnPhase.Connected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = if (uiState.vpnState.controlSocketProtected) "Loop-safe" else "Waiting",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                StatusPill(
                    text = if (uiState.vpnState.vpnInterfaceEstablished) "Interface up" else "Interface idle",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            StatusMessage(
                headline = uiState.vpnState.headline,
                detail = uiState.vpnState.detail,
            )

            Text(
                text = "TLS ${profile.serverName}  •  DNS ${profile.dnsSummary}  •  MTU ${profile.mtu}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Protection: ${uiState.vpnState.requestedProtection}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Relay: ${uiState.vpnState.relaySummary}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.vpnState.exitIp == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = "Routing: ${
                    when {
                        uiState.vpnState.trafficRoutedThroughYume -> "Tunnel active"
                        uiState.vpnState.localProxyAddress != null -> "Verifying"
                        else -> "Idle"
                    }
                }",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.vpnState.trafficRoutedThroughYume || uiState.vpnState.localProxyAddress != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = "Anonym CA: ${selectedCa?.displayName ?: "Missing"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Auth key: ${selectedAuthKey?.displayName ?: "Not assigned"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedAuthKey == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            profile.notes.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.vpnState.upstreamNetworkLabel?.let { upstream ->
                Text(
                    text = "Upstream network: $upstream",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.vpnState.eventLog.lastOrNull()?.let { entry ->
                Text(
                    text = "Latest log: ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.level) {
                        VpnLogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                        VpnLogLevel.Warn -> MaterialTheme.colorScheme.primary
                        VpnLogLevel.Error -> MaterialTheme.colorScheme.error
                    },
                )
            }

            if (!authReady) {
                Text(
                    text = if (availableAuthKeyCount == 0) {
                        "Import a PEM auth key below, then edit this server to assign it before connecting."
                    } else {
                        "Edit this server and assign one of the imported auth keys before connecting."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showConnect) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(connectLabel)
                    }
                }
                if (showDisconnect) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = phase != VpnPhase.Disconnecting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onEditServer,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(if (authReady) "Edit server" else "Assign auth key")
                    }
                }
                OutlinedButton(
                    onClick = onShowStatus,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Status")
                }
            }
        }
    }
}

@Composable
internal fun SecureMaterialsSection(
    caMaterials: List<SecureMaterialSummary>,
    authKeyMaterials: List<SecureMaterialSummary>,
    selectedCaMaterialId: String?,
    selectedAuthKeyMaterialId: String?,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onPasteCa: () -> Unit,
    onPasteAuthKey: () -> Unit,
    onDeleteMaterial: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Trust & auth",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Imported PEM certificates and keys are encrypted at rest inside the app and stored outside cloud backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MaterialGroupCard(
                title = "Anonym CA certificates",
                subtitle = "The embedded anonym CA is always available. Import your own PEM CA if you need a different trust anchor.",
                materials = caMaterials,
                selectedMaterialId = selectedCaMaterialId,
                emptyMessage = "No custom CAs imported.",
                onImportFile = onImportCaFile,
                onPasteText = onPasteCa,
                onDeleteMaterial = onDeleteMaterial,
            )
            MaterialGroupCard(
                title = "Auth keys",
                subtitle = "Yume auth uses a PEM private key. Import it from a file or paste the PEM text and then assign it to a server.",
                materials = authKeyMaterials,
                selectedMaterialId = selectedAuthKeyMaterialId,
                emptyMessage = "No auth keys imported yet.",
                onImportFile = onImportAuthKeyFile,
                onPasteText = onPasteAuthKey,
                onDeleteMaterial = onDeleteMaterial,
            )
        }
    }
}

@Composable
private fun MaterialGroupCard(
    title: String,
    subtitle: String,
    materials: List<SecureMaterialSummary>,
    selectedMaterialId: String?,
    emptyMessage: String,
    onImportFile: () -> Unit,
    onPasteText: () -> Unit,
    onDeleteMaterial: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onImportFile,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Import file")
            }
            OutlinedButton(
                onClick = onPasteText,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Paste text")
            }
            if (materials.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    materials.forEachIndexed { index, material ->
                        SecureMaterialRow(
                            material = material,
                            selected = material.id == selectedMaterialId,
                            onDelete = if (material.isDefault) null else { { onDeleteMaterial(material.id) } },
                        )
                        if (index + 1 < materials.size) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecureMaterialRow(
    material: SecureMaterialSummary,
    selected: Boolean,
    onDelete: (() -> Unit)?,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.60f)
    } else {
        Color.Transparent
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = material.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = material.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    StatusPill(
                        text = "Selected",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else if (material.isDefault) {
                    StatusPill(
                        text = "Built in",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServersSection(
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    activeProfileId: String?,
    vpnPhase: VpnPhase,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onEditProfile: (ServerProfile) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Saved servers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Tap a server to select it. Edit opens the CA/auth assignment and connection settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profiles.forEachIndexed { index, profile ->
                ServerProfileRow(
                    profile = profile,
                    selected = profile.id == selectedProfileId,
                    connected = profile.id == activeProfileId && vpnPhase == VpnPhase.Connected,
                    onClick = { onSelectProfile(profile.id) },
                    onEdit = { onEditProfile(profile) },
                    onDelete = { onDeleteProfile(profile.id) },
                )
                if (index + 1 < profiles.size) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ServerProfileRow(
    profile: ServerProfile,
    selected: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = rowColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = profile.endpointLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    StatusPill(
                        text = "Live",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else if (selected) {
                    StatusPill(
                        text = "Selected",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = "TLS ${profile.serverName}  •  DNS ${profile.dnsSummary}  •  MTU ${profile.mtu}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.notes.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) {
                    Text("Edit")
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
internal fun StatusMessage(
    headline: String,
    detail: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun BuildStampFooter() {
    val message = if (BuildConfig.DEBUG) {
        "DEBUG build v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (BuildConfig.DEBUG) {
                Text(
                    text = "If the installed app does not match this UI, uninstall it and reinstall the fresh debug APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDialog(
    uiState: MainUiState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val selectedProfile = uiState.selectedProfile
    val batteryExempt = remember(context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val packageUri = remember(context) { Uri.parse("package:${context.packageName}") }
    val routingValue = if (uiState.vpnState.trafficRoutedThroughYume) {
        "Tunnel active"
    } else if (uiState.vpnState.localProxyAddress != null) {
        "Verifying"
    } else {
        "Idle"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yume status") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusMessage(
                    headline = uiState.vpnState.headline,
                    detail = uiState.vpnState.detail,
                )
                StatusFactRow("Brief", uiState.vpnState.briefStatus)
                StatusFactRow("Client", "${uiState.vpnState.clientPlatform}/${uiState.vpnState.clientVariant} ${uiState.vpnState.clientVersion}")
                StatusFactRow("Lifecycle", uiState.vpnState.latestLifecycleState ?: "Unknown")
                StatusFactRow("Lifecycle msg", uiState.vpnState.latestLifecycleMessage ?: "None")
                StatusFactRow("Connection", uiState.vpnState.transportSummary)
                StatusFactRow("Protection", uiState.vpnState.requestedProtection)
                StatusFactRow("Server mode", uiState.vpnState.serverMode ?: "Unknown")
                StatusFactRow("Inner", uiState.vpnState.innerSummary)
                StatusFactRow("Hop", uiState.vpnState.hopSummary)
                StatusFactRow("Verity", uiState.vpnState.veritySummary)
                StatusFactRow("Relay", uiState.vpnState.relaySummary)
                StatusFactRow("Exit IP", uiState.vpnState.exitIp ?: "Unknown")
                StatusFactRow("Local SOCKS5", uiState.vpnState.localProxyAddress ?: "Unavailable")
                StatusFactRow("Peer cert", uiState.vpnState.peerCertificateFingerprint ?: "Unknown")
                StatusFactRow("Routing", routingValue)
                StatusFactRow("Loop-safe", if (uiState.vpnState.controlSocketProtected) "Yes" else "No")
                StatusFactRow("Interface", if (uiState.vpnState.vpnInterfaceEstablished) "Established" else "Idle")
                StatusFactRow("Background", if (batteryExempt) "Unrestricted" else "Battery optimized")
                StatusFactRow("Upstream", uiState.vpnState.upstreamNetworkLabel ?: "Unavailable")
                StatusFactRow("Endpoint", selectedProfile?.endpointLabel ?: "No server selected")
                StatusFactRow("TLS name", selectedProfile?.serverName ?: "Not set")
                StatusFactRow(
                    "Anonym policy",
                    when {
                        selectedProfile == null -> "Not set"
                        selectedProfile.requireAnonymProof -> "Required"
                        else -> "Optional"
                    },
                )
                StatusFactRow("Anonym CA", uiState.selectedCaMaterial?.displayName ?: "Missing")
                StatusFactRow("Auth key", uiState.selectedAuthKeyMaterial?.displayName ?: "Not assigned")
                StatusFactRow("DNS", selectedProfile?.dnsSummary ?: "Not set")
                StatusFactRow("MTU", selectedProfile?.mtu?.toString() ?: "Not set")
                StatusFactRow("Yume", uiState.vpnState.yumeProtocolVersion)
                StatusFactRow("BaseFWX", uiState.vpnState.basefwxVersion)
                StatusFactRow("App", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                StatusFactRow(
                    "Server version",
                    uiState.vpnState.serverVersion ?: "Unknown"
                )
                if (!batteryExempt) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            packageUri,
                                        )
                                    )
                                }.onFailure {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            },
                        ) {
                            Text("Allow background")
                        }
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            },
                        ) {
                            Text("Battery settings")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    text = "Session log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (uiState.vpnState.eventLog.isEmpty()) {
                    Text(
                        text = "No session events yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.vpnState.eventLog.asReversed().forEach { entry ->
                        StatusLogLine(entry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun StatusFactRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun StatusLogLine(entry: VpnLogEntry) {
    Text(
        text = "[${formatLogTimestamp(entry.timestampEpochMs)}] ${entry.level.name} ${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = when (entry.level) {
            VpnLogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
            VpnLogLevel.Warn -> MaterialTheme.colorScheme.primary
            VpnLogLevel.Error -> MaterialTheme.colorScheme.error
        },
    )
}

@Composable
internal fun ProfileEditorDialog(
    draft: ProfileDraft,
    caMaterials: List<SecureMaterialSummary>,
    authKeyMaterials: List<SecureMaterialSummary>,
    onDismiss: () -> Unit,
    onSave: (ProfileDraft) -> String?,
) {
    var localDraft by remember(draft) { mutableStateOf(draft) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (draft.id == null) "Add server" else "Edit server")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = localDraft.name,
                    onValueChange = { localDraft = localDraft.copy(name = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.host,
                    onValueChange = { localDraft = localDraft.copy(host = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server host") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.port,
                    onValueChange = { localDraft = localDraft.copy(port = it.filter(Char::isDigit)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.sni,
                    onValueChange = { localDraft = localDraft.copy(sni = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TLS server name") },
                    singleLine = true,
                    supportingText = {
                        Text("Optional. Leave blank to use the server host.")
                    },
                )
                OutlinedTextField(
                    value = localDraft.dnsServers,
                    onValueChange = { localDraft = localDraft.copy(dnsServers = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DNS servers") },
                    minLines = 2,
                    supportingText = {
                        Text("Comma or newline separated numeric IPv4 or IPv6 addresses.")
                    },
                )
                OutlinedTextField(
                    value = localDraft.mtu,
                    onValueChange = { localDraft = localDraft.copy(mtu = it.filter(Char::isDigit)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MTU") },
                    singleLine = true,
                    supportingText = {
                        Text("Android VPNs are most stable between 1280 and 1500.")
                    },
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                localDraft = localDraft.copy(requireAnonymProof = !localDraft.requireAnonymProof)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = localDraft.requireAnonymProof,
                            onCheckedChange = { checked ->
                                localDraft = localDraft.copy(requireAnonymProof = checked)
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Require anonym proof",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (localDraft.requireAnonymProof) {
                                    "Only allow servers running trusted anonym proof mode."
                                } else {
                                    "Allow normal-mode servers too. Verity will show FAIL and the server operator may monitor proxied traffic."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                MaterialChoiceSection(
                    title = "Anonym CA",
                    helper = if (localDraft.requireAnonymProof) {
                        "Choose the trust anchor for anonym verification."
                    } else {
                        "Choose the trust anchor for anonym verification when this server runs anonym mode. Normal-mode servers can connect without requiring it."
                    },
                    materials = caMaterials,
                    selectedMaterialId = localDraft.anonymCaMaterialId.ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID },
                    allowUnassigned = false,
                    onSelect = { selectedId ->
                        localDraft = localDraft.copy(anonymCaMaterialId = selectedId)
                    },
                )

                MaterialChoiceSection(
                    title = "Auth key",
                    helper = "Choose the PEM private key used for client auth. Leave empty only if you are not ready to connect yet.",
                    materials = authKeyMaterials,
                    selectedMaterialId = localDraft.authKeyMaterialId,
                    allowUnassigned = true,
                    onSelect = { selectedId ->
                        localDraft = localDraft.copy(authKeyMaterialId = selectedId)
                    },
                )

                OutlinedTextField(
                    value = localDraft.notes,
                    onValueChange = { localDraft = localDraft.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes") },
                    minLines = 3,
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { error = onSave(localDraft) }) {
                Text(if (draft.id == null) "Save" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MaterialChoiceSection(
    title: String,
    helper: String,
    materials: List<SecureMaterialSummary>,
    selectedMaterialId: String,
    allowUnassigned: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (allowUnassigned) {
            SelectableMaterialChoice(
                label = "Not assigned",
                subtitle = "Import a PEM private key later if you are only saving the server for now.",
                selected = selectedMaterialId.isBlank(),
                onClick = { onSelect("") },
            )
        }
        if (materials.isEmpty()) {
            Text(
                text = "Nothing imported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            materials.forEach { material ->
                SelectableMaterialChoice(
                    label = material.displayName,
                    subtitle = material.subtitle,
                    selected = material.id == selectedMaterialId,
                    onClick = { onSelect(material.id) },
                )
            }
        }
    }
}

@Composable
private fun SelectableMaterialChoice(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MaterialTextDialog(
    draft: MaterialTextDraft,
    onDismiss: () -> Unit,
    onSave: (MaterialTextDraft) -> String?,
) {
    var localDraft by remember(draft) { mutableStateOf(draft) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(draft.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = localDraft.name,
                    onValueChange = { localDraft = localDraft.copy(name = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Saved name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.content,
                    onValueChange = { localDraft = localDraft.copy(content = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PEM text") },
                    minLines = 8,
                    supportingText = {
                        Text("Paste the full PEM including BEGIN/END lines.")
                    },
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { error = onSave(localDraft) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

internal fun ServerProfile.toDraft(): ProfileDraft {
    return ProfileDraft(
        id = id,
        name = name,
        host = host,
        port = port.toString(),
        sni = sni,
        dnsServers = dnsServers.joinToString(", "),
        mtu = mtu.toString(),
        requireAnonymProof = requireAnonymProof,
        anonymCaMaterialId = anonymCaMaterialId,
        authKeyMaterialId = authKeyMaterialId,
        notes = notes,
    )
}

private fun phaseLabel(phase: VpnPhase): String = when (phase) {
    VpnPhase.Idle -> "Idle"
    VpnPhase.PermissionRequired -> "Permission"
    VpnPhase.Connecting -> "Connecting"
    VpnPhase.Verifying -> "Verify"
    VpnPhase.Connected -> "Connected"
    VpnPhase.Disconnecting -> "Disconnecting"
    VpnPhase.Error -> "Error"
}

internal fun formatLogTimestamp(epochMs: Long): String {
    return DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}
