package com.fixcraft.yume.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.SecureMaterialStore
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.home.ProfileDraft
import com.fixcraft.yume.ui.home.ProfileEditorDialog
import com.fixcraft.yume.ui.home.ServersSection
import com.fixcraft.yume.ui.home.toDraft

@Composable
fun ServersScreen(
    uiState: MainUiState,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: suspend (String?, String, String, String, String, String, String, Boolean, String, String, String) -> String?,
) {
    val strings = appStrings()
    var editorDraft by remember { mutableStateOf<ProfileDraft?>(null) }

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
}
