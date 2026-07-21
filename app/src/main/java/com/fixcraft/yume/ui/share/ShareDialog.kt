/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.ui.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.data.SecureMaterialSummary
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.share.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export-flow dialog. Walks the user through:
 *   1. (Only when authKeySummary != null) Show whether the key is
 *      encrypted at rest. If it is, ask for the key's password so
 *      we can decrypt and include the PEM in the bundle. If the
 *      user leaves the field blank for an encrypted key, the bundle
 *      ships without the auth key (info-only) — the recipient will
 *      need to attach their own.
 *   2. Set a new password (8+ chars, confirmed) for the output file.
 *   3. SAF CreateDocument picker → write the encrypted blob.
 *
 * The caller provides `resolveAuthKeyPem(password)` so this dialog
 * doesn't have to know how SecureMaterialStore works. Pass null for
 * authKeySummary if the profile has no auth-key material to include.
 */
@Composable
fun ExportShareDialog(
    profile: ServerProfile,
    caCertPem: String,
    authKeySummary: SecureMaterialSummary? = null,
    resolveAuthKeyPem: suspend (password: String?) -> Result<String> = { Result.failure(IllegalStateException("no resolver")) },
    obfsPadMultiple: Int = 0,
    obfsJitterMs: Int = 0,
    tlsPinSha256: String = "",
    tlsStealthProfile: String = "",
    onDismiss: () -> Unit,
    onResult: (success: Boolean, message: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authKeyPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    // "encrypting" covers Argon2 + AES-GCM (1–3 s on this Pixel),
    // "writing" covers the SAF outputStream.write after the user
    // picks a destination. Both are async — without an indicator the
    // dialog just looks frozen until the SAF picker opens.
    var busyLabel by remember { mutableStateOf<String?>(null) }
    val authKeyEncrypted = authKeySummary?.importedEncrypted == true
    val authKeyAvailable = authKeySummary != null

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null) {
            status = "Cancelled — no file written."
            return@rememberLauncherForActivityResult
        }
        if (bytes == null) {
            status = "Internal error: no bundle to write."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busyLabel = "Writing…"
            val ok = writeUri(context, uri, bytes)
            val displayName = resolveDisplayName(context, uri)
            busyLabel = null
            if (ok) {
                onResult(true, "Exported to $displayName.")
                onDismiss()
            } else {
                status = "Write failed."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export profile to a .yss file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Encrypts the server connection info, the operator CA cert, the obfs secret, " +
                        "and (optionally) your auth private key into a single password-protected file. " +
                        "Anyone with the file AND the password becomes you on this server — pick a strong " +
                        "password.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (authKeyAvailable) {
                    if (authKeyEncrypted) {
                        Text(
                            "Your auth key is encrypted at rest. Enter the password you set when you " +
                                "imported it to include the key in the export. Leave blank to export " +
                                "WITHOUT the key (info-only).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = authKeyPassword,
                            onValueChange = { authKeyPassword = it },
                            label = { Text("Auth-key password (optional)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Auth key is unencrypted — it'll be included in the export automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "Note: this profile has no auth key attached — exporting info only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New file password (8+ chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm new file password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                busyLabel?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = busyLabel == null && password.length >= 8 && password == confirm,
                onClick = {
                    scope.launch {
                        status = null
                        // Step 1: resolve the auth key PEM (if any).
                        // Unencrypted → null password; encrypted with
                        // a typed password → use it; encrypted with
                        // blank password → produce info-only bundle.
                        busyLabel = "Unlocking auth key…"
                        val authPem: String = if (!authKeyAvailable) {
                            ""
                        } else {
                            val pwd = if (authKeyEncrypted) authKeyPassword.trim() else ""
                            if (authKeyEncrypted && pwd.isEmpty()) {
                                // User opted out of including the key.
                                ""
                            } else {
                                val resolved = withContext(Dispatchers.IO) {
                                    resolveAuthKeyPem(if (pwd.isEmpty()) null else pwd)
                                }
                                if (resolved.isFailure) {
                                    busyLabel = null
                                    status = "Auth-key unlock failed: " +
                                        (resolved.exceptionOrNull()?.message ?: "wrong password")
                                    return@launch
                                }
                                resolved.getOrThrow()
                            }
                        }
                        // Step 2: encrypt the bundle.
                        busyLabel = "Encrypting…"
                        val (bytes, err) = withContext(Dispatchers.Default) {
                            try {
                                val bundle = ShareFile.Bundle(
                                    type = ShareFile.TYPE_BACKUP,
                                    createdAtIso8601 = ShareFile.isoNowUtc(),
                                    createdBy = "yume-android",
                                    label = if (profile.port == 443) profile.host
                                            else "${profile.host}:${profile.port}",
                                    serverHost = profile.host,
                                    serverPort = profile.port,
                                    authPrivateKeyPem = authPem,
                                    obfuscation = profile.obfuscation,
                                    obfsSecret = profile.obfsSecret,
                                    obfsPadMultiple = obfsPadMultiple,
                                    obfsJitterMs = obfsJitterMs,
                                    tlsPinSha256 = tlsPinSha256,
                                    tlsStealthProfile = tlsStealthProfile,
                                    innerPsk = profile.innerPsk,
                                    anonymCaCertPem = caCertPem,
                                )
                                ShareFile.encode(bundle, password) to null
                            } catch (t: Throwable) {
                                ByteArray(0) to (t.message ?: t.javaClass.simpleName)
                            }
                        }
                        if (err != null) {
                            busyLabel = null
                            status = "Encrypt failed: $err"
                            return@launch
                        }
                        pendingBytes = bytes
                        val defaultName = (profile.host
                            .replace('/', '_').replace(':', '_'))
                            .ifBlank { "yume-backup" } + ".yss"
                        // saveLauncher.launch hands off to the system
                        // SAF picker; clear busy so the dialog stops
                        // showing the spinner while the picker is up.
                        // The saveLauncher callback sets busy=Writing
                        // when the user actually picks a target.
                        busyLabel = null
                        saveLauncher.launch(defaultName)
                    }
                },
            ) { Text("Encrypt & save") }
        },
        dismissButton = {
            TextButton(
                enabled = busyLabel == null,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
    )
}

/**
 * Import-flow dialog. Pick a file (SAF) → enter password → preview
 * decrypted bundle → confirm "Create profile" which calls [onApply]
 * with the decoded bundle. Caller is expected to persist a new
 * ServerProfile in its own MainViewModel.
 */
@Composable
fun ImportShareDialog(
    onDismiss: () -> Unit,
    onApply: (bundle: ShareFile.Bundle) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ShareFile.Bundle?>(null) }
    var busyLabel by remember { mutableStateOf<String?>(null) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            status = "Cancelled — no file picked."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busyLabel = "Reading…"
            val bytes = withContext(Dispatchers.IO) { readUri(context, uri) }
            val displayName = resolveDisplayName(context, uri)
            busyLabel = null
            if (bytes == null) {
                status = "Could not read file."
                return@launch
            }
            val header = ShareFile.peekHeader(bytes)
            if (header == null) {
                status = "Not a .yss file (bad magic or unsupported version)."
                return@launch
            }
            fileBytes = bytes
            fileName = displayName
            preview = null
            status = "Loaded ${bytes.size} bytes (v${header.version}, type=${header.type})."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a .yss file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fileBytes == null) {
                    Text(
                        "Pick a .yss file you exported from another yume install, " +
                            "or that someone shared with you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            openLauncher.launch(arrayOf(
                                "application/octet-stream",
                                "application/yume-share",
                                "*/*",
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose file…") }
                } else {
                    Text("File: ${fileName ?: "(unnamed)"}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = busyLabel == null && password.isNotEmpty() && preview == null,
                        onClick = {
                            scope.launch {
                                busyLabel = "Decrypting…"
                                val (bundle, err) = withContext(Dispatchers.Default) {
                                    try {
                                        ShareFile.decode(fileBytes!!, password) to null
                                    } catch (t: Throwable) {
                                        null to (t.message ?: t.javaClass.simpleName)
                                    }
                                }
                                busyLabel = null
                                if (err != null) {
                                    status = "Decrypt: $err"
                                } else {
                                    preview = bundle
                                    status = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Decrypt & preview") }
                    preview?.let { b ->
                        Spacer(Modifier.height(4.dp))
                        Text("─── Bundle contents ───",
                            style = MaterialTheme.typography.titleSmall)
                        if (b.label.isNotEmpty()) Text("Label:   ${b.label}")
                        Text("Server:  ${b.serverHost}:${b.serverPort}")
                        Text("Auth key: ${if (b.authPrivateKeyPem.isEmpty()) "(none)" else "PRESENT"}")
                        Text("Operator CA: ${if (b.anonymCaCertPem.isEmpty()) "(none)" else "PRESENT"}")
                        Text("Obfs secret: ${if (b.obfsSecret.isEmpty()) "(none)" else "PRESENT"}")
                        Text("Inner PSK: ${if (b.innerPsk.isEmpty()) "(none)" else "PRESENT"}")
                    }
                }
                busyLabel?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = busyLabel == null && preview != null,
                onClick = {
                    preview?.let { onApply(it); onDismiss() }
                },
            ) { Text("Create profile") }
        },
        dismissButton = {
            TextButton(
                enabled = busyLabel == null,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
    )
}

private suspend fun readUri(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

private suspend fun writeUri(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

// SAF document URIs look like content://com.android.providers.downloads.documents/document/456.
// `uri.lastPathSegment` returns "456" — the document ID — which is what
// the old code was showing as the "exported to …" filename. The real
// display name lives in the Openable cursor under DISPLAY_NAME.
private suspend fun resolveDisplayName(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        return@withContext cursor.getString(idx)
                    }
                }
            }
            null
        }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()
    }
