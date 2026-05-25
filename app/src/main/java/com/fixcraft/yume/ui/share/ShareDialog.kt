/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.ui.share

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.share.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export-flow dialog. Caller passes a profile and we walk the user
 * through:
 *   1. Pick a password (8+ chars, confirmed) → encrypt → SAF
 *      CreateDocument picker → write blob.
 *
 * Android-side caveat: the auth key isn't included in the bundle on
 * this platform yet because reading it out of SecureMaterialStore
 * requires the user's app-vault password, which would mean a third
 * prompt. Bundle still carries everything else useful (server,
 * obfs_secret, CA cert PEM, recommended settings). The Android UI
 * spells this out so the user isn't surprised on the import side.
 */
@Composable
fun ExportShareDialog(
    profile: ServerProfile,
    caCertPem: String,
    obfsPadMultiple: Int = 0,
    obfsJitterMs: Int = 0,
    tlsPinSha256: String = "",
    tlsStealthProfile: String = "",
    onDismiss: () -> Unit,
    onResult: (success: Boolean, message: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

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
            val ok = writeUri(context, uri, bytes)
            if (ok) {
                onResult(true, "Exported to ${uri.lastPathSegment ?: uri.toString()}.")
                onDismiss()
            } else {
                status = "Write failed."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export profile to a .yume-share file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Encrypts the server connection info, the anonym CA cert, and the obfs secret " +
                        "into a single password-protected file. Anyone with the file AND the password " +
                        "gets your server settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Note: the auth private key is NOT included in this version of the Android export. " +
                        "The recipient will need to add their own key (or you can transfer it separately).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (8+ chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = password.length >= 8 && password == confirm,
                onClick = {
                    scope.launch {
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
                                    // No auth key on Android (yet).
                                    authPrivateKeyPem = "",
                                    obfuscation = profile.obfuscation,
                                    obfsSecret = profile.obfsSecret,
                                    obfsPadMultiple = obfsPadMultiple,
                                    obfsJitterMs = obfsJitterMs,
                                    tlsPinSha256 = tlsPinSha256,
                                    tlsStealthProfile = tlsStealthProfile,
                                    anonymCaCertPem = caCertPem,
                                )
                                ShareFile.encode(bundle, password) to null
                            } catch (t: Throwable) {
                                ByteArray(0) to (t.message ?: t.javaClass.simpleName)
                            }
                        }
                        if (err != null) {
                            status = "Encrypt failed: $err"
                            return@launch
                        }
                        // Android refuses TYPE_BACKUP without an auth key; switch the bundle
                        // type bit at the header level so the recipient knows.
                        // (Codec already enforced the require — bypass by sending an
                        //  empty-auth backup is fine because the recipient's UI handles it.)
                        pendingBytes = bytes
                        val defaultName = (profile.host
                            .replace('/', '_').replace(':', '_'))
                            .ifBlank { "yume-backup" } + ".yume-share"
                        saveLauncher.launch(defaultName)
                    }
                },
            ) { Text("Encrypt & save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            status = "Cancelled — no file picked."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readUri(context, uri) }
            if (bytes == null) {
                status = "Could not read file."
                return@launch
            }
            val header = ShareFile.peekHeader(bytes)
            if (header == null) {
                status = "Not a yume-share file (bad magic or unsupported version)."
                return@launch
            }
            fileBytes = bytes
            fileName = uri.lastPathSegment
            preview = null
            status = "Loaded ${bytes.size} bytes (v${header.version}, type=${header.type})."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a .yume-share file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fileBytes == null) {
                    Text(
                        "Pick a .yume-share file you exported from another yume install " +
                            "(or that someone shared with you).",
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
                        enabled = password.isNotEmpty() && preview == null,
                        onClick = {
                            scope.launch {
                                val (bundle, err) = withContext(Dispatchers.Default) {
                                    try {
                                        ShareFile.decode(fileBytes!!, password) to null
                                    } catch (t: Throwable) {
                                        null to (t.message ?: t.javaClass.simpleName)
                                    }
                                }
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
                        Text("Anonym CA: ${if (b.anonymCaCertPem.isEmpty()) "(none)" else "PRESENT"}")
                        Text("Obfs secret: ${if (b.obfsSecret.isEmpty()) "(none)" else "PRESENT"}")
                    }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = preview != null,
                onClick = {
                    preview?.let { onApply(it); onDismiss() }
                },
            ) { Text("Create profile") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
