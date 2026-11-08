package com.fixcraft.yume.ui.kits

import com.fixcraft.yume.consumer.summary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.consumer.*
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.MessageId
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.ui.SectionCard
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth

class ServiceActions(
    val add: (String, String?) -> Unit,
    val authorize: (String) -> Unit,
    val catalog: (String) -> Unit,
    val install: (String, ProviderCodec.Catalog, String, String) -> Unit,
    val select: (ServiceSelection) -> Unit,
    val cancel: (String) -> Unit,
    val remove: (String) -> Unit,
    val code: (String, CharArray) -> Unit,
    val reload: () -> Unit,
)

@Composable
fun ProviderPanel(state: ServiceUiState, actions: ServiceActions, advanced: Boolean) {
    val strings = appStrings()
    fun text(id: MessageId) = strings.message(id)
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    if (adding) AddServiceDialog(onDismiss = { adding = false }) { origin, issuer ->
        adding = false
        actions.add(origin, issuer)
    }
    removing?.let { provider ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(text(MessageId.PROVIDER_REMOVE)) }, text = { Text(provider) },
            confirmButton = { TextButton(onClick = { removing = null; actions.remove(provider) }) { Text(strings.message(MessageId.PROVIDER_REMOVE)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strings.cancel) } })
    }
    state.codePrompt?.let { prompt ->
        CodeDialog(prompt, onCancel = { actions.cancel(prompt.provider) }, onSubmit = { actions.code(prompt.id, it) })
    }
    SectionCard(title = text(MessageId.PROVIDER_SERVICES)) {
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(text(MessageId.PROVIDER_ADD_SERVICE)) }
        TextButton(onClick = actions.reload) { Text(text(MessageId.PROVIDER_REFRESH)) }
        if (state.entries.isEmpty()) Text(text(MessageId.PROVIDER_NO_SELECTION))
        state.error?.let { Text(providerFailure(strings, it), color = MaterialTheme.colorScheme.error) }
        if (state.removed) Text(text(MessageId.PROVIDER_REMOVED))
        state.entries.forEach { entry ->
            HorizontalDivider()
            Text(entry.label, style = MaterialTheme.typography.titleMedium)
            Text(entry.provider)
            entry.issuer?.let { issuer -> Text(issuer) }
            if (entry.suspended) Text(text(MessageId.PROVIDER_SUSPENDED))
            if (state.selection?.provider == entry.provider && !state.manual) Text(text(MessageId.PROVIDER_SELECTED))
            if (state.authorizing == entry.provider) {
                Text(text(MessageId.PROVIDER_AUTHORIZATION))
                TextButton(onClick = { actions.cancel(entry.provider) }) { Text(text(MessageId.PROVIDER_CANCEL_SIGN_IN)) }
            }
            if (entry.provider in state.busy) {
                Text(text(MessageId.PROVIDER_BUSY))
                TextButton(onClick = { actions.cancel(entry.provider) }) { Text(strings.cancel) }
            }
            if (entry.issuer != null) {
                TextButton(onClick = { actions.authorize(entry.provider) }, enabled = entry.provider !in state.busy) {
                    Text(text(MessageId.PROVIDER_SIGN_IN))
                }
                if (!state.browserRegistered) Text(text(MessageId.PROVIDER_REGISTRATION))
            }
            OutlinedButton(onClick = { actions.catalog(entry.provider) }, enabled = entry.provider !in state.busy) {
                Text(text(MessageId.PROVIDER_SERVERS))
            }
            val catalog = state.catalogs[entry.provider]
            if (catalog != null) {
                if (catalog.services.isEmpty()) Text(text(MessageId.PROVIDER_EMPTY))
                catalog.services.forEach { service ->
                    Text(service.label, style = MaterialTheme.typography.titleSmall)
                    service.servers.forEach { server ->
                        server.country?.let { Text(strings.providerCountry(it)) }
                        Text(server.label)
                        if (advanced) Text("${server.host}:${server.port}")
                        val different = entry.selection?.let { it.server != server.id || it.service != service.id } == true
                        if (different) Text(text(MessageId.PROVIDER_CHANGE_SERVER))
                        else OutlinedButton(onClick = { actions.install(entry.provider, catalog, service.id, server.id) },
                            enabled = entry.provider !in state.busy) { Text(text(MessageId.PROVIDER_PROVISION)) }
                    }
                }
            }
            entry.selection?.let { selected ->
                Text(selected.summary.endpointLabel)
                if (advanced) {
                    Text(text(MessageId.PROVIDER_GRANT) + ": " + selected.grant)
                    Text(text(MessageId.PROVIDER_IDENTITY) + ": " + selected.identity)
                    Text(text(MessageId.PROVIDER_SERVER_TRUST) + ": " + selected.serverKey)
                    entry.expiresAt?.let { Text(text(MessageId.PROVIDER_EXPIRY) + ": " + java.time.Instant.ofEpochSecond(it)) }
                }
                OutlinedButton(onClick = { actions.select(selected) }, enabled = !entry.suspended && entry.provider !in state.busy) {
                    Text(text(MessageId.PROVIDER_SELECT))
                }
            }
            TextButton(onClick = { removing = entry.provider }) { Text(text(MessageId.PROVIDER_REMOVE)) }
        }
    }
}

/** Provider origins are URLs: no capitals, suggestions or autocorrection. */
private val urlKeyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

@Composable
private fun AddServiceDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    val strings = appStrings()
    var origin by remember { mutableStateOf(BuildConfig.SUGGESTED_PROVIDER) }
    var issuer by remember { mutableStateOf(BuildConfig.SUGGESTED_ISSUER) }
    var consent by remember { mutableStateOf(false) }
    AlertDialog(modifier = Modifier.appDialogWidth(), properties = appDialogProperties, onDismissRequest = onDismiss,
        title = { Text(strings.message(MessageId.PROVIDER_ADD_SERVICE)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // External labels can wrap at large text sizes without entering the field border.
                val originLabel = strings.message(MessageId.PROVIDER_ORIGIN)
                Text(originLabel)
                OutlinedTextField(origin, { if (it.length <= 512) origin = it }, singleLine = true,
                    keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = originLabel })
                val issuerLabel = strings.message(MessageId.PROVIDER_ISSUER)
                Text(issuerLabel)
                OutlinedTextField(issuer, { if (it.length <= 512) issuer = it }, singleLine = true,
                    keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = issuerLabel })
                Row(Modifier.fillMaxWidth().toggleable(consent, role = Role.Checkbox, onValueChange = { consent = it })) {
                    Checkbox(consent, onCheckedChange = null)
                    Text(strings.message(MessageId.PROVIDER_CONSENT), modifier = Modifier.padding(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(origin.trim(), issuer.trim().ifEmpty { null }) },
            enabled = consent && origin.isNotBlank()) { Text(strings.message(MessageId.ADD)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } })
}

@Composable
private fun CodeDialog(prompt: CodePrompt, onCancel: () -> Unit, onSubmit: (CharArray) -> Unit) {
    val strings = appStrings()
    // Process-local UI state only. Never use rememberSaveable for a secret.
    var code by remember(prompt.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { code = ""; onCancel() },
        title = { Text(strings.message(MessageId.PROVIDER_AWAIT_CODE)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(prompt.provider)
            Text(prompt.grant)
            Text(strings.message(MessageId.PROVIDER_OPENING_CODE))
            OutlinedTextField(code, { if (it.length <= 64) code = it }, label = { Text(strings.message(MessageId.CODE)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
        } },
        confirmButton = { TextButton(enabled = code.length in 25..64, onClick = {
            val value = code.toCharArray(); code = ""; onSubmit(value)
        }) { Text(strings.save) } },
        dismissButton = { TextButton(onClick = { code = ""; onCancel() }) { Text(strings.cancel) } })
}

fun providerFailure(strings: AppStrings, code: String): String = strings.message(when (code) {
    "stale" -> MessageId.PROVIDER_ERROR_STALE
    "unauthorized", "not_entitled" -> MessageId.PROVIDER_ERROR_UNAUTHORIZED
    "storage" -> MessageId.PROVIDER_ERROR_STORAGE
    "binding" -> MessageId.PROVIDER_ERROR_BINDING
    "revoked" -> MessageId.PROVIDER_SUSPENDED
    "unsupported" -> MessageId.PROVIDER_ERROR_GENERIC
    "malformed" -> MessageId.PROVIDER_ERROR_GENERIC
    else -> MessageId.PROVIDER_ERROR_GENERIC
})
