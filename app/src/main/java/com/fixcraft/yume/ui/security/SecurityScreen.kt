package com.fixcraft.yume.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.home.MaterialTextDialog
import com.fixcraft.yume.ui.home.MaterialTextDraft
import com.fixcraft.yume.ui.home.MaterialTextKind
import com.fixcraft.yume.ui.home.SecureMaterialsSection

@Composable
fun SecurityScreen(
    uiState: MainUiState,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onSaveCaText: (String, String) -> String?,
    onSaveAuthKeyText: (String, String) -> String?,
    onDeleteMaterial: (String) -> Unit,
) {
    val strings = appStrings()
    var materialDraft by remember { mutableStateOf<MaterialTextDraft?>(null) }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.selectedServerLabel,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = strings.securitySubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    uiState.selectedProfile?.let { profile ->
                        Text(
                            text = strings.selectedServerSummary(
                                profile.name,
                                uiState.selectedCaMaterial?.displayName ?: strings.missing,
                                uiState.selectedAuthKeyMaterial?.displayName ?: strings.notAssigned,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                showHeader = false,
            )
        }
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
}
