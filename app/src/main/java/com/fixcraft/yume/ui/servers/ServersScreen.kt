package com.fixcraft.yume.ui.servers

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.home.ProfileDraft
import com.fixcraft.yume.ui.home.ProfileEditorDialog
import com.fixcraft.yume.ui.home.ServersSection
import com.fixcraft.yume.ui.home.toDraft
import com.fixcraft.yume.ui.share.ExportShareDialog
import com.fixcraft.yume.ui.share.ImportShareDialog
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    uiState: MainUiState,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: suspend (String?, String, String, String, String, Boolean, String, String, String, Boolean, String, String, String) -> String?,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editorDraft by remember { mutableStateOf<ProfileDraft?>(null) }
    // Share-file UI state. exportTarget != null shows the export dialog;
    // showImport=true shows the import dialog. The selected profile for
    // export is whatever's currently selected in the main UI (uiState
    // .selectedProfileId); if nothing is selected the button is disabled.
    var exportProfileId by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = strings.serversTitle,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = strings.serversSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            editorDraft = ProfileDraft(
                                anonymCaMaterialId = uiState.caMaterials.firstOrNull()?.id
                                    ?: SecureMaterialStore.DEFAULT_ANONYM_CA_ID,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.addServer)
                    }
                    // Export / Import row — paired so they read as
                    // related operations. Export is enabled only when
                    // a profile is selected (the one we'd export).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            enabled = uiState.selectedProfileId != null,
                            onClick = { exportProfileId = uiState.selectedProfileId },
                            modifier = Modifier.weight(1f),
                        ) { Text("Export selected") }
                        OutlinedButton(
                            onClick = { showImport = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Import…") }
                    }
                }
            }
        }

        if (uiState.profiles.isNotEmpty()) {
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
                    showHeader = false,
                )
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

    // Export dialog — find the selected profile + read its CA PEM
    // out of the material store (no password needed for CA reads),
    // then hand off to ExportShareDialog for password + SAF save.
    exportProfileId?.let { id ->
        val profile = uiState.profiles.firstOrNull { it.id == id }
        if (profile == null) {
            exportProfileId = null
        } else {
            val caPem = remember(profile.anonymCaMaterialId) {
                runCatching {
                    SecureMaterialStore.get(context).readMaterialText(profile.anonymCaMaterialId)
                }.getOrDefault("")
            }
            ExportShareDialog(
                profile = profile,
                caCertPem = caPem,
                onDismiss = { exportProfileId = null },
                onResult = { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    if (showImport) {
        ImportShareDialog(
            onDismiss = { showImport = false },
            onApply = { bundle ->
                // Persist the imported CA cert if present, then create
                // a server profile pointing at it. Auth key not yet
                // wired on the Android import path — recipient picks
                // an existing auth-key material via the editor.
                scope.launch {
                    val store = SecureMaterialStore.get(context)
                    val caId = if (bundle.anonymCaCertPem.isNotEmpty()) {
                        runCatching {
                            store.saveMaterialText(
                                com.fixcraft.yume.data.SecureMaterialType.AnonymCa,
                                "Imported CA (${bundle.serverHost})",
                                bundle.anonymCaCertPem,
                            ).id
                        }.getOrNull() ?: SecureMaterialStore.DEFAULT_ANONYM_CA_ID
                    } else {
                        SecureMaterialStore.DEFAULT_ANONYM_CA_ID
                    }
                    val err = onSaveProfile(
                        null,                                // new profile
                        bundle.label.ifBlank { bundle.serverHost },  // name
                        bundle.serverHost,
                        bundle.serverPort.toString(),
                        "",                                  // sni
                        bundle.obfuscation,
                        bundle.obfsSecret,
                        "",                                  // dns_servers (use defaults)
                        "",                                  // mtu (use default)
                        true,                                // require anonym proof
                        caId,
                        "",                                  // auth_key_material_id — recipient adds later
                        "Imported from yume-share file on ${ShareFileNow()}",
                    )
                    val msg = if (err == null) {
                        "Imported. Open the new profile in the editor to attach an auth key before connecting."
                    } else {
                        "Imported with errors: $err"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

// Tiny helper so we don't introduce another Date dependency at top
// of file just for one timestamp string.
private fun ShareFileNow(): String = com.fixcraft.yume.share.ShareFile.isoNowUtc()
