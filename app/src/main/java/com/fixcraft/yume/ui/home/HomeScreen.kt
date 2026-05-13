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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.AndroidSystemSettings
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.i18n.logStrings
import com.fixcraft.yume.i18n.VpnLogTranslator
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import com.fixcraft.yume.ui.theme.YumeErrorContainer
import com.fixcraft.yume.ui.theme.YumeErrorContent
import com.fixcraft.yume.ui.theme.YumeProtectedContainer
import com.fixcraft.yume.ui.theme.YumeProtectedContent
import com.fixcraft.yume.ui.theme.YumeVerifyContainer
import com.fixcraft.yume.ui.theme.YumeVerifyContent
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnLogEntry
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

internal data class ProfileDraft(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "443",
    val sni: String = "",
    val obfuscation: Boolean = false,
    val obfsSecret: String = "",
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
    val encrypted: Boolean = false,
    val password: String = "",
)

data class PendingAuthKeyImport(
    val displayName: String,
    val bytes: ByteArray,
)

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: suspend (String?, String, String, String, String, Boolean, String, String, String, Boolean, String, String, String) -> String?,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onSaveCaText: suspend (String, String) -> String?,
    onSaveAuthKeyText: suspend (String, String, Boolean, String) -> String?,
    onDeleteMaterial: (String) -> Unit,
    onClearNotice: () -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val logUiStrings = logStrings(context, uiState.appSettings)
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
                    Text(strings.addServer)
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
                        title = if (hasProfiles) strings.yourYumeTunnelTitle else strings.setUpYumeVpnTitle,
                        subtitle = if (hasProfiles) {
                            strings.yourYumeTunnelSubtitle
                        } else {
                            strings.setUpYumeVpnSubtitle
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
                                logStrings = logUiStrings,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                                onShowStatus = { showStatusDialog = true },
                                onEditServer = {
                                    editorDraft = selectedProfile.toDraft()
                                },
                            )
                        }
                    }

                    // Live throughput chart sits right under the selected
                    // server card so the user can glance at the protected
                    // traffic without scrolling. Only shown when there is
                    // at least one server profile to be talking about.
                    item {
                        TrafficChartCard(vpnPhase = uiState.vpnState.phase)
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
                                title = strings.pasteAnonymCaTitle,
                                name = strings.importedCaName,
                            )
                        },
                        onPasteAuthKey = {
                            materialDraft = MaterialTextDraft(
                                kind = MaterialTextKind.AuthKey,
                                title = strings.pasteAuthKeyTitle,
                                name = strings.importedAuthKeyName,
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
                onSaveProfile(
                    updated.id,
                    updated.name,
                    updated.host,
                    updated.port,
                    updated.sni,
                    updated.obfuscation,
                    updated.obfsSecret,
                    updated.dnsServers,
                    updated.mtu,
                    updated.requireAnonymProof,
                    updated.anonymCaMaterialId,
                    updated.authKeyMaterialId,
                    updated.notes,
                )
            },
        )
    }

    materialDraft?.let { draft ->
        MaterialTextDialog(
            draft = draft,
            onDismiss = { materialDraft = null },
            onSave = { updated ->
                when (updated.kind) {
                    MaterialTextKind.AnonymCa -> onSaveCaText(updated.name, updated.content)
                    MaterialTextKind.AuthKey -> onSaveAuthKeyText(
                        updated.name,
                        updated.content,
                        updated.encrypted,
                        updated.password,
                    )
                }
            },
        )
    }

    if (showStatusDialog) {
        StatusDialog(
            uiState = uiState,
            logStrings = logUiStrings,
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
    val strings = appStrings()
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
                text = strings.noServersConfigured,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = strings.addFirstGatewayTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.addFirstGatewayDetail,
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
                Text(strings.addServer)
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
    logStrings: com.fixcraft.yume.i18n.AppStrings,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onShowStatus: () -> Unit,
    onEditServer: () -> Unit,
) {
    val strings = appStrings()
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
        VpnPhase.Error -> strings.tryAgain
        VpnPhase.PermissionRequired -> strings.continueAction
        else -> strings.connect
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
                    containerColor = vpnStatusTone(uiState.vpnState).container,
                    contentColor = vpnStatusTone(uiState.vpnState).content,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = if (uiState.vpnState.controlSocketProtected) strings.loopSafeShort else strings.waiting,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                StatusPill(
                    text = if (uiState.vpnState.vpnInterfaceEstablished) strings.interfaceUp else strings.interfaceIdle,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            StatusMessage(
                headline = uiState.vpnState.headline,
                detail = uiState.vpnState.detail,
            )

            Text(
                text = "${strings.tlsNameLabel}: ${profile.serverName}  •  ${strings.dnsLabel}: ${profile.dnsSummary}  •  ${strings.mtuLabel}: ${profile.mtu}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strings.protectionShortLabel}: ${uiState.vpnState.requestedProtection}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strings.relayShortLabel}: ${uiState.vpnState.relaySummary}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.vpnState.exitIp == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = "${strings.routingShortLabel}: ${
                    when {
                        uiState.vpnState.trafficRoutedThroughYume -> strings.tunnelActive
                        uiState.vpnState.localProxyAddress != null -> strings.phaseStatus(VpnPhase.Verifying, protected = false, proxyReady = false)
                        else -> strings.phaseStatus(VpnPhase.Idle, protected = false, proxyReady = false)
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
                text = "${strings.anonymCaLabel}: ${selectedCa?.displayName ?: strings.missing}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strings.authKeyLabel}: ${selectedAuthKey?.displayName ?: strings.notAssigned}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedAuthKey == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strings.httpsMaskingLabel}: ${if (profile.obfuscation) strings.enabled else strings.disabled}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "${strings.upstreamNetworkReportLabel}: $upstream",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.vpnState.eventLog.lastOrNull()?.let { entry ->
                Text(
                    text = "${strings.latestLogLabel}: ${VpnLogTranslator.translate(entry.message, logStrings)}",
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
                        strings.importPemAuthKeyHint
                    } else {
                        strings.assignImportedAuthKeyHint
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
                        Icon(
                            painter = painterResource(R.drawable.ic_login_action),
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                        Icon(
                            painter = painterResource(R.drawable.ic_logout_action),
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.disconnect)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onEditServer,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(if (authReady) strings.editServerButton else strings.assignAuthKeyButton)
                    }
                }
                OutlinedButton(
                    onClick = onShowStatus,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text(strings.statusButton)
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
    showHeader: Boolean = true,
) {
    val strings = appStrings()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showHeader) {
                Text(
                    text = strings.trustAuthHeader,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.trustAuthDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MaterialGroupCard(
                title = strings.anonymCaCertificatesTitle,
                subtitle = strings.anonymCaCertificatesSubtitle,
                materials = caMaterials,
                selectedMaterialId = selectedCaMaterialId,
                emptyMessage = strings.noCustomCaImported,
                onImportFile = onImportCaFile,
                onPasteText = onPasteCa,
                onDeleteMaterial = onDeleteMaterial,
            )
            MaterialGroupCard(
                title = strings.authKeysTitle,
                subtitle = strings.authKeysSubtitle,
                materials = authKeyMaterials,
                selectedMaterialId = selectedAuthKeyMaterialId,
                emptyMessage = strings.noAuthKeysImported,
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
    val strings = appStrings()
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                Text(strings.importFile)
            }
            OutlinedButton(
                onClick = onPasteText,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(strings.pasteText)
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
    val strings = appStrings()
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = material.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!selected && material.isDefault) {
                    StatusPill(
                        text = strings.builtIn,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (material.importedEncrypted) {
                    EncryptedKeyBadge()
                }
                if (selected) {
                    SelectionBadge()
                }
                if (onDelete != null) {
                    IconButton(
                        modifier = Modifier.size(32.dp),
                        onClick = onDelete,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_action),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
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
    showHeader: Boolean = true,
) {
    val strings = appStrings()
    if (profiles.isEmpty()) {
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showHeader) {
                Text(
                    text = strings.savedServersTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.savedServersSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    val strings = appStrings()
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
                verticalAlignment = Alignment.Top,
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
                    Text(
                        text = "${strings.dnsLabel} ${profile.dnsSummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile.mtu != 1500) {
                        Text(
                            text = "${strings.mtuLabel} ${profile.mtu}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (connected) {
                    StatusPill(
                        text = strings.live,
                        containerColor = YumeProtectedContainer,
                        contentColor = YumeProtectedContent,
                        borderColor = YumeProtectedContent.copy(alpha = 0.32f),
                    )
                } else if (selected) {
                    StatusPill(
                        text = strings.selected,
                        iconRes = R.drawable.ic_check_small,
                        iconTrailing = true,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            profile.notes.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_action),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.edit)
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_action),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.delete)
                }
            }
        }
    }
}

@Composable
private fun SelectionBadge() {
    Surface(
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_check_small),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun EncryptedKeyBadge() {
    Surface(
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_lock_action),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
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
    text: String? = null,
    iconRes: Int? = null,
    iconTrailing: Boolean = false,
    compact: Boolean = false,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact || text.isNullOrBlank()) 8.dp else 12.dp,
                vertical = if (compact || text.isNullOrBlank()) 6.dp else 7.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null && !iconTrailing) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                )
            }
            if (!text.isNullOrBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
            if (iconRes != null && iconTrailing) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        }
    }
}

internal data class StatusTone(
    val container: Color,
    val content: Color,
)

@Composable
internal fun vpnStatusTone(state: VpnConnectionState): StatusTone {
    return when {
        state.phase == VpnPhase.Error -> StatusTone(YumeErrorContainer, YumeErrorContent)
        state.phase == VpnPhase.Verifying -> StatusTone(YumeVerifyContainer, YumeVerifyContent)
        state.phase == VpnPhase.Connected && state.trafficRoutedThroughYume -> {
            StatusTone(YumeProtectedContainer, YumeProtectedContent)
        }
        state.phase == VpnPhase.Connected -> {
            StatusTone(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
        else -> StatusTone(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun BuildStampFooter() {
    val strings = appStrings()
    val message = strings.buildVersionLabel(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        debug = BuildConfig.DEBUG,
    )
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
                    text = strings.debugBuildHint,
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
    logStrings: com.fixcraft.yume.i18n.AppStrings,
    onDismiss: () -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val selectedProfile = uiState.selectedProfile
    val batteryExempt = remember(context) {
        AndroidSystemSettings.isIgnoringBatteryOptimizations(context)
    }
    val routingValue = if (uiState.vpnState.trafficRoutedThroughYume) {
        strings.tunnelActive
    } else if (uiState.vpnState.localProxyAddress != null) {
        strings.phaseStatus(VpnPhase.Verifying, protected = false, proxyReady = false)
    } else {
        strings.phaseStatus(VpnPhase.Idle, protected = false, proxyReady = false)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.yumeStatusTitle) },
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
                StatusFactRow(strings.briefLabel, uiState.vpnState.briefStatus)
                StatusFactRow(strings.clientLabel, "${uiState.vpnState.clientPlatform}/${uiState.vpnState.clientVariant} ${uiState.vpnState.clientVersion}")
                StatusFactRow(strings.lifecycleLabel, uiState.vpnState.latestLifecycleState ?: strings.unknown)
                StatusFactRow(strings.lifecycleMessageLabel, uiState.vpnState.latestLifecycleMessage ?: strings.none)
                StatusFactRow(strings.connectionLabel, uiState.vpnState.transportSummary)
                StatusFactRow(strings.protectionShortLabel, uiState.vpnState.requestedProtection)
                StatusFactRow(strings.serverModeLabel, uiState.vpnState.serverMode ?: strings.unknown)
                StatusFactRow(strings.innerLabel, uiState.vpnState.innerSummary)
                StatusFactRow(strings.hopLabel, uiState.vpnState.hopSummary)
                StatusFactRow(strings.verityLabel, uiState.vpnState.veritySummary)
                StatusFactRow(strings.relayShortLabel, uiState.vpnState.relaySummary)
                StatusFactRow(strings.exitIpLabel, uiState.vpnState.exitIp ?: strings.unknown)
                StatusFactRow(strings.localSocks5Label, uiState.vpnState.localProxyAddress ?: strings.unavailable)
                StatusFactRow(strings.peerCertLabel, uiState.vpnState.peerCertificateFingerprint ?: strings.unknown)
                StatusFactRow(strings.routingShortLabel, routingValue)
                StatusFactRow(strings.loopSafeLabel, strings.boolValue(uiState.vpnState.controlSocketProtected))
                StatusFactRow(strings.interfaceLabel, if (uiState.vpnState.vpnInterfaceEstablished) strings.established else strings.phaseStatus(VpnPhase.Idle, protected = false, proxyReady = false))
                StatusFactRow(strings.backgroundLabel, if (batteryExempt) strings.unrestricted else strings.batteryOptimized)
                StatusFactRow(strings.upstreamLabel, uiState.vpnState.upstreamNetworkLabel ?: strings.unavailable)
                StatusFactRow(strings.endpointLabel, selectedProfile?.endpointLabel ?: strings.noServerSelected)
                StatusFactRow(strings.tlsNameLabel, selectedProfile?.serverName ?: strings.notSet)
                StatusFactRow(
                    strings.anonymPolicyLabel,
                    when {
                        selectedProfile == null -> strings.notSet
                        selectedProfile.requireAnonymProof -> strings.anonymRequired
                        else -> strings.anonymOptional
                    },
                )
                StatusFactRow(strings.anonymCaLabel, uiState.selectedCaMaterial?.displayName ?: strings.missing)
                StatusFactRow(strings.authKeyLabel, uiState.selectedAuthKeyMaterial?.displayName ?: strings.notAssigned)
                StatusFactRow(strings.dnsLabel, selectedProfile?.dnsSummary ?: strings.notSet)
                StatusFactRow(strings.mtuLabel, selectedProfile?.mtu?.toString() ?: strings.notSet)
                StatusFactRow(strings.yumeLabel, uiState.vpnState.yumeProtocolVersion)
                StatusFactRow(strings.basefwxLabel, uiState.vpnState.basefwxVersion)
                StatusFactRow(strings.appLabel, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                StatusFactRow(
                    strings.serverVersionLabel,
                    uiState.vpnState.serverVersion ?: strings.unknown
                )
                if (!batteryExempt) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                AndroidSystemSettings.openBatteryOptimizationControls(context)
                            },
                        ) {
                            Text(strings.allowBackground)
                        }
                        TextButton(
                            onClick = {
                                AndroidSystemSettings.openAppDetails(context)
                            },
                        ) {
                            Text(strings.batterySettingsButton)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    text = strings.sessionLogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (uiState.vpnState.eventLog.isEmpty()) {
                    Text(
                        text = strings.noSessionEventsYet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.vpnState.eventLog.asReversed().forEach { entry ->
                        StatusLogLine(entry, logStrings)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
        properties = appDialogProperties,
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
internal fun StatusLogLine(
    entry: VpnLogEntry,
    logStrings: com.fixcraft.yume.i18n.AppStrings,
) {
    Text(
        text = "[${formatLogTimestamp(entry.timestampEpochMs)}] ${entry.level.name} ${VpnLogTranslator.translate(entry.message, logStrings)}",
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
    onSave: suspend (ProfileDraft) -> String?,
) {
    val strings = appStrings()
    var localDraft by remember(draft) { mutableStateOf(draft) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    var submitting by remember(draft) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!submitting) {
                onDismiss()
            }
        },
        modifier = Modifier.appDialogWidth(),
        title = {
            Text(if (draft.id == null) strings.addServerTitle else strings.editServerTitle)
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
                    label = { Text(strings.profileNameLabel) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.host,
                    onValueChange = { localDraft = localDraft.copy(host = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.serverHostLabel) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.port,
                    onValueChange = { localDraft = localDraft.copy(port = it.filter(Char::isDigit)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.portLabel) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.sni,
                    onValueChange = { localDraft = localDraft.copy(sni = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.tlsServerNameLabel) },
                    singleLine = true,
                    supportingText = {
                        Text(strings.tlsServerNameHelper)
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
                                localDraft = localDraft.copy(obfuscation = !localDraft.obfuscation)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = strings.httpsMaskingTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (localDraft.obfuscation) {
                                    strings.httpsMaskingEnabled
                                } else {
                                    strings.httpsMaskingDisabled
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            modifier = Modifier.align(Alignment.Top),
                            checked = localDraft.obfuscation,
                            onCheckedChange = { checked ->
                                localDraft = localDraft.copy(obfuscation = checked)
                            },
                        )
                    }
                }
                if (localDraft.obfuscation) {
                    OutlinedTextField(
                        value = localDraft.obfsSecret,
                        onValueChange = { localDraft = localDraft.copy(obfsSecret = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.httpsMaskingSecretLabel) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = {
                            Text(strings.httpsMaskingSecretHelper)
                        },
                    )
                }
                OutlinedTextField(
                    value = localDraft.dnsServers,
                    onValueChange = { localDraft = localDraft.copy(dnsServers = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.dnsServersLabel) },
                    minLines = 2,
                    supportingText = {
                        Text(strings.dnsServersHelper)
                    },
                )
                OutlinedTextField(
                    value = localDraft.mtu,
                    onValueChange = { localDraft = localDraft.copy(mtu = it.filter(Char::isDigit)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.mtuLabel) },
                    singleLine = true,
                    supportingText = {
                        Text(strings.mtuHelper)
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
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = strings.requireAnonymProofTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (localDraft.requireAnonymProof) {
                                    strings.requireAnonymProofEnabled
                                } else {
                                    strings.requireAnonymProofDisabled
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            modifier = Modifier.align(Alignment.Top),
                            checked = localDraft.requireAnonymProof,
                            onCheckedChange = { checked ->
                                localDraft = localDraft.copy(requireAnonymProof = checked)
                            },
                        )
                    }
                }

                MaterialChoiceSection(
                    title = strings.anonymCaChoiceTitle,
                    helper = if (localDraft.requireAnonymProof) {
                        strings.anonymCaChoiceHelperRequired
                    } else {
                        strings.anonymCaChoiceHelperOptional
                    },
                    materials = caMaterials,
                    selectedMaterialId = localDraft.anonymCaMaterialId.ifBlank { SecureMaterialStore.DEFAULT_ANONYM_CA_ID },
                    allowUnassigned = false,
                    onSelect = { selectedId ->
                        localDraft = localDraft.copy(anonymCaMaterialId = selectedId)
                    },
                )

                MaterialChoiceSection(
                    title = strings.authKeyChoiceTitle,
                    helper = strings.authKeyChoiceHelper,
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
                    label = { Text(strings.notesLabel) },
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
            Button(
                enabled = !submitting,
                onClick = {
                    if (submitting) return@Button
                    scope.launch {
                        submitting = true
                        error = onSave(localDraft)
                        if (error == null) {
                            onDismiss()
                        }
                        submitting = false
                    }
                },
            ) {
                Text(if (draft.id == null) strings.save else strings.update)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
        properties = appDialogProperties,
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
    val strings = appStrings()
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
                label = strings.notAssignedChoice,
                subtitle = strings.notAssignedChoiceSubtitle,
                selected = selectedMaterialId.isBlank(),
                onClick = { onSelect("") },
            )
        }
        if (materials.isEmpty()) {
            Text(
                text = strings.nothingImportedYet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            materials.forEach { material ->
                SelectableMaterialChoice(
                    label = material.displayName,
                    subtitle = material.subtitle,
                    encrypted = material.importedEncrypted,
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
    encrypted: Boolean = false,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (encrypted || selected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (encrypted) {
                        EncryptedKeyBadge()
                    }
                    if (selected) {
                        SelectionBadge()
                    }
                }
            }
        }
    }
}

@Composable
internal fun MaterialTextDialog(
    draft: MaterialTextDraft,
    onDismiss: () -> Unit,
    onSave: suspend (MaterialTextDraft) -> String?,
) {
    val strings = appStrings()
    var localDraft by remember(draft) { mutableStateOf(draft) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    var submitting by remember(draft) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!submitting) {
                onDismiss()
            }
        },
        modifier = Modifier.appDialogWidth(),
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
                    label = { Text(strings.savedNameLabel) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = localDraft.content,
                    onValueChange = { localDraft = localDraft.copy(content = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.pemTextLabel) },
                    minLines = 8,
                    supportingText = {
                        Text(
                            if (draft.kind == MaterialTextKind.AuthKey) {
                                strings.authKeyTextHelper
                            } else {
                                strings.pemTextHelper
                            },
                        )
                    },
                )
                if (draft.kind == MaterialTextKind.AuthKey) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!localDraft.encrypted) {
                            FilledTonalButton(
                                onClick = {
                                    localDraft = localDraft.copy(encrypted = false, password = "")
                                    error = null
                                },
                            ) {
                                Text(strings.regularKeyOption)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    localDraft = localDraft.copy(encrypted = false, password = "")
                                    error = null
                                },
                            ) {
                                Text(strings.regularKeyOption)
                            }
                        }
                        if (localDraft.encrypted) {
                            FilledTonalButton(
                                onClick = {
                                    localDraft = localDraft.copy(encrypted = true)
                                    error = null
                                },
                            ) {
                                Text(strings.encryptedKeyOption)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    localDraft = localDraft.copy(encrypted = true)
                                    error = null
                                },
                            ) {
                                Text(strings.encryptedKeyOption)
                            }
                        }
                    }
                    if (localDraft.encrypted) {
                        OutlinedTextField(
                            value = localDraft.password,
                            onValueChange = {
                                localDraft = localDraft.copy(password = it)
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.passwordLabel) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                    }
                }
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
            Button(
                enabled = !submitting,
                onClick = {
                    if (submitting) return@Button
                    scope.launch {
                        submitting = true
                        error = onSave(localDraft)
                        if (error == null) {
                            onDismiss()
                        }
                        submitting = false
                    }
                },
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
        properties = appDialogProperties,
    )
}

@Composable
internal fun AuthKeyImportDialog(
    pending: PendingAuthKeyImport,
    onDismiss: () -> Unit,
    onImport: suspend (String, ByteArray, Boolean, String) -> String?,
) {
    val strings = appStrings()
    var displayName by remember(pending) { mutableStateOf(pending.displayName) }
    var encrypted by remember(pending) { mutableStateOf(false) }
    var password by remember(pending) { mutableStateOf("") }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    var submitting by remember(pending) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!submitting) {
                onDismiss()
            }
        },
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.importAuthKeyTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.savedNameLabel) },
                    singleLine = true,
                )
                Text(
                    text = strings.authKeyImportModeHelper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!encrypted) {
                        FilledTonalButton(
                            onClick = {
                                encrypted = false
                                password = ""
                                error = null
                            },
                        ) {
                            Text(strings.regularKeyOption)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                encrypted = false
                                password = ""
                                error = null
                            },
                        ) {
                            Text(strings.regularKeyOption)
                        }
                    }
                    if (encrypted) {
                        FilledTonalButton(
                            onClick = {
                                encrypted = true
                                error = null
                            },
                        ) {
                            Text(strings.encryptedKeyOption)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                encrypted = true
                                error = null
                            },
                        ) {
                            Text(strings.encryptedKeyOption)
                        }
                    }
                }
                if (encrypted) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.passwordLabel) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
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
            Button(
                enabled = !submitting,
                onClick = {
                    if (submitting) return@Button
                    scope.launch {
                        submitting = true
                        error = onImport(displayName, pending.bytes, encrypted, password)
                        if (error == null) {
                            onDismiss()
                        }
                        submitting = false
                    }
                },
            ) {
                Text(strings.importFile)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
        properties = appDialogProperties,
    )
}

@Composable
internal fun AuthKeyUnlockDialog(
    authKeyName: String,
    initialError: String?,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    val strings = appStrings()
    var password by remember(authKeyName) { mutableStateOf("") }
    var error by remember(authKeyName, initialError) { mutableStateOf(initialError) }

    AlertDialog(
        onDismissRequest = {
            onDismiss()
        },
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.unlockAuthKeyTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = strings.unlockAuthKeyDetail(authKeyName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.passwordLabel) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
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
            Button(
                onClick = {
                    val trimmedPassword = password.trim()
                    if (trimmedPassword.isEmpty()) {
                        error = strings.passwordRequired
                    } else {
                        onUnlock(trimmedPassword)
                    }
                },
            ) {
                Text(strings.unlockButton)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
        properties = appDialogProperties,
    )
}

internal fun ServerProfile.toDraft(): ProfileDraft {
    return ProfileDraft(
        id = id,
        name = name,
        host = host,
        port = port.toString(),
        sni = sni,
        obfuscation = obfuscation,
        obfsSecret = obfsSecret,
        dnsServers = dnsServers.joinToString(", "),
        mtu = mtu.toString(),
        requireAnonymProof = requireAnonymProof,
        anonymCaMaterialId = anonymCaMaterialId,
        authKeyMaterialId = authKeyMaterialId,
        notes = notes,
    )
}

internal fun formatLogTimestamp(epochMs: Long): String {
    return DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}
