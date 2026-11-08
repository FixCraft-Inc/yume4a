package com.fixcraft.yume.ui.kits

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.R
import com.fixcraft.yume.consumer.CodePrompt
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.MessageId
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.ui.FactRow
import com.fixcraft.yume.ui.SectionHeader
import com.fixcraft.yume.ui.StatusPill
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import com.fixcraft.yume.ui.theme.mainActionColors
import java.time.Instant

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

/**
 * The Services section: one row per provider, its account state and the next useful
 * step. Refreshing, removal and technical facts stay in each row's menu. The shared
 * controller owns every enrollment transition, and this code only asks for them.
 */
@Composable
fun ProviderPanel(
    state: ServiceUiState,
    actions: ServiceActions,
    advanced: Boolean,
    vpnActive: Boolean = false,
    onConnect: () -> Unit = {},
    kitsPresent: Boolean = false,
    suggestedProvider: String = BuildConfig.SUGGESTED_PROVIDER,
    suggestedIssuer: String = BuildConfig.SUGGESTED_ISSUER,
) {
    val strings = appStrings()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ServiceEntry?>(null) }
    var details by remember { mutableStateOf<ServiceEntry?>(null) }
    if (adding) AddServiceDialog(suggestedProvider, suggestedIssuer, onDismiss = { adding = false }) { origin, issuer ->
        adding = false
        actions.add(origin, issuer)
    }
    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            modifier = Modifier.appDialogWidth(),
            properties = appDialogProperties,
            title = { Text(strings.message(MessageId.PROVIDER_REMOVE)) },
            text = { Text(strings.providerRemoveNotice(originHost(entry.provider)), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { removing = null; actions.remove(entry.provider) }) {
                    Text(strings.message(MessageId.PROVIDER_REMOVE), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strings.cancel) } },
        )
    }
    details?.let { entry -> ServiceDetailsDialog(entry, advanced) { details = null } }
    state.codePrompt?.let { prompt ->
        CodeDialog(prompt, advanced, onCancel = { actions.cancel(prompt.provider) }, onSubmit = { actions.code(prompt.id, it) })
    }
    // A suggestion waits for an explicit Continue. Nothing is contacted before that.
    val suggestion = suggestedProvider.takeIf { it.isNotEmpty() && state.browserRegistered && state.loaded && state.entries.isEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = strings.message(MessageId.PROVIDER_SERVICES)) {
            when {
                suggestion != null -> Unit
                // With nothing set up yet, adding is the next step. Afterwards it steps back.
                state.entries.isEmpty() && !kitsPresent -> FilledTonalButton(onClick = { adding = true }) { Text(strings.message(MessageId.PROVIDER_ADD_SERVICE)) }
                else -> TextButton(onClick = { adding = true }) { Text(strings.message(MessageId.PROVIDER_ADD_SERVICE)) }
            }
        }
        state.error?.let { code ->
            Text(providerFailure(strings, code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            // Stored records that could not be read can be read again without the network.
            if (code == "storage") TextButton(onClick = actions.reload) { Text(strings.message(MessageId.PROVIDER_REFRESH)) }
        }
        if (state.removed) Muted(strings.message(MessageId.PROVIDER_REMOVED))
        if (suggestion != null) {
            SuggestedService(suggestion, suggestedIssuer, busy = suggestion in state.busy,
                onContinue = { actions.add(suggestion, suggestedIssuer.ifEmpty { null }) }, onOther = { adding = true })
        } else if (state.loaded && state.entries.isEmpty()) {
            Muted(strings.message(MessageId.PROVIDER_NO_SELECTION))
        }
        state.entries.forEach { entry ->
            ServiceRow(entry, state, actions, advanced, vpnActive, onConnect, onDetails = { details = entry }, onRemove = { removing = entry })
        }
    }
}

/** States what will be contacted before anything is. */
@Composable
private fun SuggestedService(origin: String, issuer: String, busy: Boolean, onContinue: () -> Unit, onOther: () -> Unit) {
    val strings = appStrings()
    ServiceCard(highlighted = false) {
        Text(strings.message(MessageId.PROVIDER_SUGGESTED_TITLE), style = MaterialTheme.typography.titleMedium)
        Text(strings.providerSuggestedBody(originHost(origin), originHost(issuer.ifEmpty { origin })), style = MaterialTheme.typography.bodyMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = onContinue, enabled = !busy, colors = mainActionColors()) { Text(strings.message(MessageId.PROVIDER_CONTINUE)) }
        TextButton(onClick = onOther) { Text(strings.message(MessageId.PROVIDER_OTHER)) }
    }
}

@Composable
private fun ServiceCard(highlighted: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ServiceRow(
    entry: ServiceEntry,
    state: ServiceUiState,
    actions: ServiceActions,
    advanced: Boolean,
    vpnActive: Boolean,
    onConnect: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = appStrings()
    val busy = entry.provider in state.busy
    val pending = state.authorizing == entry.provider
    val inUse = !state.manual && state.selection?.provider == entry.provider
    val canAskForServers = entry.issuer == null || entry.authorized
    val catalog = state.catalogs[entry.provider]
    val access = grantAccess(entry, System.currentTimeMillis() / 1000)
    ServiceCard(highlighted = inUse) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.message(when {
                        entry.suspended -> MessageId.PROVIDER_SUSPENDED
                        entry.issuer == null -> MessageId.PROVIDER_NO_ACCOUNT
                        entry.authorized -> MessageId.PROVIDER_SIGNED_IN
                        else -> MessageId.PROVIDER_SIGNED_OUT
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.suspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ServiceMenu(entry, enabled = !busy && !pending, canAskForServers, onRefresh = { actions.catalog(entry.provider) }, onDetails, onRemove)
        }
        // Before authorization, the row names what the person is about to trust.
        if (entry.issuer != null && !entry.authorized) {
            Facts {
                Muted(strings.message(MessageId.PROVIDER_VPN_ACCESS))
                FactRow(strings.message(MessageId.PROVIDER_LABEL_PROVIDER), originHost(entry.provider))
                FactRow(strings.message(MessageId.PROVIDER_LABEL_SIGN_IN), originHost(entry.issuer!!))
            }
        }
        when {
            pending -> Working(strings.message(MessageId.PROVIDER_AUTHORIZATION), strings.message(MessageId.PROVIDER_CANCEL_SIGN_IN)) { actions.cancel(entry.provider) }
            busy -> Working(strings.message(MessageId.PROVIDER_BUSY), strings.cancel) { actions.cancel(entry.provider) }
        }
        val issuer = entry.issuer
        if (issuer != null && !entry.authorized && !pending) {
            if (state.browserRegistered) {
                Button(onClick = { actions.authorize(entry.provider) }, enabled = !busy && state.authorizing == null, colors = mainActionColors()) {
                    Text(strings.message(MessageId.PROVIDER_SIGN_IN))
                }
                Muted(strings.providerSignInNote(originHost(issuer)))
            } else {
                Muted(strings.message(MessageId.PROVIDER_REGISTRATION))
            }
        }
        if (canAskForServers) {
            ServerList(entry, catalog, inUse, vpnActive, advanced, busy, actions)
            if (catalog == null) {
                val load = strings.message(MessageId.PROVIDER_SERVERS)
                // With a server installed, the list is a refresh and Connect stays the next step.
                if (entry.selection == null || entry.suspended) {
                    Button(onClick = { actions.catalog(entry.provider) }, enabled = !busy, colors = mainActionColors()) { Text(load) }
                } else {
                    TextButton(onClick = { actions.catalog(entry.provider) }, enabled = !busy) { Text(load) }
                }
            }
        }
        // A suspended row already says what to do, and a lease cannot open until then.
        if (!entry.suspended) {
            access?.let { AccessLine(it, entry.expiresAt) }
            val installed = entry.selection
            when {
                installed == null || busy -> Unit
                !inUse -> Button(onClick = { actions.select(installed) }, colors = mainActionColors()) {
                    Text(strings.message(MessageId.PROVIDER_SELECT))
                }
                !vpnActive -> Button(onClick = onConnect, colors = mainActionColors()) { Text(strings.connect) }
            }
        }
    }
}

/** Refresh, details and removal, out of the way of the next step. */
@Composable
private fun ServiceMenu(entry: ServiceEntry, enabled: Boolean, canRefresh: Boolean, onRefresh: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    val strings = appStrings()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), strings.providerMore(entry.label), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canRefresh) DropdownMenuItem(
                text = { Text(strings.message(MessageId.PROVIDER_REFRESH_SERVERS)) },
                enabled = enabled,
                onClick = { open = false; onRefresh() },
            )
            DropdownMenuItem(text = { Text(strings.message(MessageId.PROVIDER_DETAILS)) }, onClick = { open = false; onDetails() })
            // Removal stays available during other work, because it also ends that work.
            DropdownMenuItem(
                text = { Text(strings.message(MessageId.PROVIDER_REMOVE), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

@Composable
private fun Working(text: String, action: String, onAction: () -> Unit) {
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
    Muted(text)
    TextButton(onClick = onAction) { Text(action) }
}

/**
 * The account's servers by place and name. Choosing one requests a device lease and
 * selects it. An installed choice cannot silently move to another server, and
 * choosing it again renews its lease once that is allowed.
 */
@Composable
private fun ServerList(entry: ServiceEntry, catalog: ProviderCodec.Catalog?, inUse: Boolean, vpnActive: Boolean, advanced: Boolean, busy: Boolean, actions: ServiceActions) {
    val strings = appStrings()
    val installed = entry.selection
    val tag = when { !inUse -> null; vpnActive -> strings.live; else -> strings.selected }
    if (catalog == null) {
        // The list is not loaded yet, but the installed server is known from the last one.
        val server = entry.server
        if (installed != null && server != null) ServerRow(server.country, installed.label, server.host, server.port, advanced, tag, selected = inUse, enabled = false) {}
        return
    }
    val servers = catalog.services.flatMap { service -> service.servers.map { service to it } }
    if (servers.isEmpty()) Muted(strings.message(MessageId.PROVIDER_EMPTY))
    servers.forEach { (service, server) ->
        val current = installed?.service == service.id && installed.server == server.id
        ServerRow(server.country, server.label, server.host, server.port, advanced, tag.takeIf { current }, selected = current && inUse,
            enabled = !busy && (installed == null || current)) {
            // Decided at the tap: the screen may have been open since the lease could renew.
            val renew = current && grantAccess(entry, System.currentTimeMillis() / 1000) != GrantAccess.Active
            when {
                !current || renew -> actions.install(entry.provider, catalog, service.id, server.id)
                !inUse -> actions.select(installed)
            }
        }
    }
    if (installed != null && servers.size > 1) Muted(strings.message(MessageId.PROVIDER_CHANGE_SERVER))
}

@Composable
private fun ServerRow(
    country: String?,
    label: String,
    host: String,
    port: Int,
    advanced: Boolean,
    tag: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val strings = appStrings()
    val place = serverPlace(country, strings::providerCountry)
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        LocationMark(place)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(place.name ?: strings.message(MessageId.PROVIDER_LOCATION_UNKNOWN), style = MaterialTheme.typography.bodyLarge)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (advanced) Text("$host:$port", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            tag?.let { StatusPill(it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

/** The flag, or a plain globe when the place is unknown. Decoration only: the name beside it is what gets read aloud. */
@Composable
internal fun LocationMark(place: ServerPlace) {
    val flag = place.flag
    if (flag != null) {
        Text(flag, fontSize = 22.sp, modifier = Modifier.widthIn(min = 28.dp).clearAndSetSemantics {})
    } else {
        Icon(painterResource(R.drawable.ic_globe), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Leases last up to an hour, so the row says when this one ends. */
@Composable
private fun AccessLine(access: GrantAccess, expiresAt: Long?) {
    val strings = appStrings()
    val time = expiresAt?.let(strings::shortTime)
    when {
        access == GrantAccess.Ended -> Muted(strings.message(MessageId.PROVIDER_ACCESS_ENDED))
        time == null -> Unit
        access == GrantAccess.Renewable -> Muted(strings.providerAccessRenew(time))
        else -> Muted(strings.providerAccessUntil(time))
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Facts(content: @Composable () -> Unit) {
    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/** The provider and its issuer are distinct identities, so each keeps its own label even when the origins match. */
@Composable
private fun ServiceDetailsDialog(entry: ServiceEntry, advanced: Boolean, onDismiss: () -> Unit) {
    val strings = appStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogWidth(),
        properties = appDialogProperties,
        title = { Text(entry.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FactRow(strings.message(MessageId.PROVIDER_LABEL_PROVIDER), entry.provider)
                entry.issuer?.let { FactRow(strings.message(MessageId.PROVIDER_LABEL_SIGN_IN), it) }
                entry.selection?.let { selected ->
                    FactRow(strings.serverAddressLabel, "${selected.host}:${selected.port}", monospace = true)
                    Muted(strings.message(MessageId.PROVIDER_SERVER_ADDRESS_NOTE))
                    if (advanced) {
                        entry.expiresAt?.let { FactRow(strings.message(MessageId.PROVIDER_EXPIRY), Instant.ofEpochSecond(it).toString()) }
                        FactRow(strings.message(MessageId.PROVIDER_GRANT), selected.grant)
                        FactRow(strings.message(MessageId.PROVIDER_IDENTITY), selected.identity.chunked(16).joinToString("\n"), monospace = true)
                        FactRow(strings.message(MessageId.PROVIDER_SERVER_TRUST), selected.serverKey.chunked(16).joinToString("\n"), monospace = true)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}

/** Provider origins are URLs: no capitals, suggestions or autocorrection. */
private val urlKeyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

@Composable
private fun AddServiceDialog(suggestedProvider: String, suggestedIssuer: String, onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    val strings = appStrings()
    var origin by remember { mutableStateOf(suggestedProvider) }
    var issuer by remember { mutableStateOf(suggestedIssuer) }
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
private fun CodeDialog(prompt: CodePrompt, advanced: Boolean, onCancel: () -> Unit, onSubmit: (CharArray) -> Unit) {
    val strings = appStrings()
    // Process-local UI state only. Never use rememberSaveable for a secret.
    var code by remember(prompt.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { code = ""; onCancel() },
        title = { Text(strings.message(MessageId.PROVIDER_AWAIT_CODE)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.message(MessageId.PROVIDER_OPENING_CODE))
            FactRow(strings.message(MessageId.PROVIDER_LABEL_PROVIDER), originHost(prompt.provider))
            if (advanced) FactRow(strings.message(MessageId.PROVIDER_GRANT), prompt.grant)
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
    "unauthorized" -> MessageId.PROVIDER_ERROR_UNAUTHORIZED
    "not_entitled" -> MessageId.PROVIDER_ERROR_NOT_ENTITLED
    "unavailable" -> MessageId.PROVIDER_ERROR_UNAVAILABLE
    "rate_limited" -> MessageId.PROVIDER_ERROR_RATE_LIMITED
    "cancelled" -> MessageId.PROVIDER_ERROR_CANCELLED
    "timeout" -> MessageId.PROVIDER_ERROR_TIMEOUT
    "conflict" -> MessageId.PROVIDER_ERROR_CONFLICT
    "unsupported" -> MessageId.PROVIDER_ERROR_UNSUPPORTED
    "storage", "state" -> MessageId.PROVIDER_ERROR_STORAGE
    "binding" -> MessageId.PROVIDER_ERROR_BINDING
    "revoked" -> MessageId.PROVIDER_SUSPENDED
    else -> MessageId.PROVIDER_ERROR_GENERIC
})
