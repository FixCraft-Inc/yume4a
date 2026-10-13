package com.fixcraft.yume.ui.kits

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.SectionCard
import com.fixcraft.yume.ui.StatusPill
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The largest sealed kit: salt, nonce, length, 1 MiB of content, one padding
// block and the tag (docs/protocol/SEALED_KIT_1.md in YUME).
private const val MAX_SEALED_BYTES = 16 + 12 + 4 + 1024 * 1024 + 1024 + 16

private class ChosenFile(val name: String, val bytes: ByteArray)

/** The kits on this device: choose one, add one from its file and code, rename or remove one. */
@Composable
fun KitsScreen(
    uiState: MainUiState,
    onRecoverKitIndex: () -> Unit,
    onSelectKit: (String) -> Unit,
    onRenameKit: (String, String) -> Unit,
    onDeleteKit: (String) -> Unit,
    onImportKit: suspend (ByteArray, CharArray, String) -> KitImport,
    onReportNotice: (String) -> Unit,
) {
    val strings = appStrings()
    var recovering by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<KitSummary?>(null) }
    var deleting by remember { mutableStateOf<KitSummary?>(null) }
    val liveKitId = uiState.vpnState.kitId.takeIf { uiState.vpnState.isActive }

    if (recovering) {
        AlertDialog(
            onDismissRequest = { recovering = false },
            title = { Text(strings.recoverKitIndex) },
            text = { Text(strings.recoverKitIndexDetail) },
            confirmButton = { TextButton(onClick = { recovering = false; onRecoverKitIndex() }) { Text(strings.recoverKitIndex) } },
            dismissButton = { TextButton(onClick = { recovering = false }) { Text(strings.cancel) } },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (uiState.kitIndexCorrupt) {
            item {
                SectionCard(title = strings.kitIndexCorrupt, detail = strings.recoverKitIndexDetail) {
                    Button(onClick = { recovering = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.recoverKitIndex)
                    }
                }
            }
        }
        item {
            SectionCard(title = strings.kitsTitle, detail = strings.kitsSubtitle) {
                Button(onClick = { adding = true }, enabled = !uiState.kitIndexCorrupt, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.addKit)
                }
            }
        }
        if (uiState.kits.isEmpty()) {
            item {
                Text(
                    text = strings.noKitsYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(items = uiState.kits, key = { it.id }) { kit ->
            KitRow(
                kit = kit,
                selected = kit.id == uiState.selectedKitId,
                live = kit.id == liveKitId,
                onSelect = { onSelectKit(kit.id) },
                onRename = { renaming = kit },
                onDelete = { deleting = kit },
            )
        }
    }

    if (adding) {
        AddKitDialog(
            onDismiss = { adding = false },
            onImportKit = onImportKit,
            onReportNotice = onReportNotice,
        )
    }
    renaming?.let { kit ->
        RenameKitDialog(
            kit = kit,
            onDismiss = { renaming = null },
            onRename = { name ->
                onRenameKit(kit.id, name)
                renaming = null
            },
        )
    }
    deleting?.let { kit ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            modifier = Modifier.appDialogWidth(),
            title = { Text(strings.deleteKitTitle) },
            text = { Text("${kit.name}\n\n${strings.deleteKitDetail}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteKit(kit.id)
                        deleting = null
                    },
                ) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(strings.cancel) }
            },
            properties = appDialogProperties,
        )
    }
}

@Composable
private fun KitRow(
    kit: KitSummary,
    selected: Boolean,
    live: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = appStrings()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = kit.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = kit.endpointLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected || live) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (live) {
                            StatusPill(
                                text = strings.live,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (selected) {
                            StatusPill(
                                text = strings.selected,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onRename) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit_action),
                    contentDescription = strings.renameKit,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A kit in use stays until the VPN is stopped.
            IconButton(onClick = onDelete, enabled = !live) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_action),
                    contentDescription = strings.delete,
                    tint = if (live) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun AddKitDialog(
    onDismiss: () -> Unit,
    onImportKit: suspend (ByteArray, CharArray, String) -> KitImport,
    onReportNotice: (String) -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ChosenFile?>(null) }
    // Not saved across process death on purpose: the code is a secret.
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val chosen = withContext(Dispatchers.IO) { readKitFile(context, uri) }
            when {
                chosen == null -> error = strings.kitFileUnreadable
                chosen.bytes.size > MAX_SEALED_BYTES -> error = strings.kitRefusedTooLarge
                else -> {
                    file = chosen
                    error = null
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.addKitTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.kitFileLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !working) {
                        Text(strings.chooseFile)
                    }
                    Text(
                        text = file?.name ?: strings.noFileChosen,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.take(MAX_CODE_INPUT)
                        error = null
                    },
                    label = { Text(strings.kitCodeLabel) },
                    supportingText = { Text(strings.kitCodeHelper) },
                    singleLine = true,
                    enabled = !working,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_INPUT) },
                    label = { Text(strings.kitNameLabel) },
                    supportingText = { Text(strings.kitNameHelper) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && file != null && code.isNotBlank(),
                onClick = {
                    val chosen = file ?: return@TextButton
                    val typed = code.toCharArray()
                    working = true
                    scope.launch {
                        val result = onImportKit(chosen.bytes, typed, name)
                        working = false
                        when (result) {
                            is KitImport.Imported -> {
                                code = ""
                                onDismiss()
                            }

                            is KitImport.Refused -> {
                                error = strings.kitRefusal(result.reason)
                                if (result.detail.isNotBlank()) onReportNotice(result.detail)
                            }
                        }
                    }
                },
            ) {
                Text(strings.importKit)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(strings.cancel) }
        },
        properties = appDialogProperties,
    )
}

@Composable
private fun RenameKitDialog(
    kit: KitSummary,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val strings = appStrings()
    var name by remember(kit.id) { mutableStateOf(kit.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.renameKitTitle) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_INPUT) },
                label = { Text(strings.kitNameLabel) },
                supportingText = { Text(strings.kitNameHelper) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
        properties = appDialogProperties,
    )
}

private const val MAX_CODE_INPUT = 64
private const val MAX_NAME_INPUT = 48

/**
 * Reads [uri] up to one byte past a kit's largest size, so a larger file is
 * told apart without reading all of it. Null for an empty or unreadable file.
 */
private fun readKitFile(context: Context, uri: Uri): ChosenFile? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_SEALED_BYTES + 1)
            var filled = 0
            while (filled < buffer.size) {
                val read = input.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            if (filled == 0) null else buffer.copyOf(filled)
        }
    }.getOrNull() ?: return null
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
        }
    }.getOrNull()
    return ChosenFile(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty(), bytes)
}
