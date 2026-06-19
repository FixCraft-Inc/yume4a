package com.fixcraft.yume.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fixcraft.yume.crypto.BaseFwxCompat
import com.fixcraft.yume.diagnostics.YumeLocalSelfTest
import com.fixcraft.yume.diagnostics.YumeLocalSelfTestReport
import com.fixcraft.yume.diagnostics.YumeLocalSelfTestRow
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.YumeVpnRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class BaseFwxTextMode {
    FwxAes,
    FwxAesHeavy,
    B512,
    Pb512,
}

@Composable
fun ToolsScreen() {
    val strings = appStrings()
    var mode by remember { mutableStateOf(BaseFwxTextMode.FwxAes) }
    var input by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busyDetail by remember { mutableStateOf<String?>(null) }
    var selfTestReport by remember { mutableStateOf<YumeLocalSelfTestReport?>(null) }
    var selfTestError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    busyDetail?.let { detail ->
        Dialog(
            onDismissRequest = {},
            properties = appDialogProperties,
        ) {
            Card(
                modifier = Modifier.appDialogWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = strings.workingTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LocalSelfTestCard(
                report = selfTestReport,
                error = selfTestError,
                busy = busyDetail != null,
                onRun = {
                    if (busyDetail == null) {
                        scope.launch {
                            busyDetail = strings.localSelfTestRunning
                            val result = withContext(Dispatchers.Default) {
                                runCatching { YumeLocalSelfTest.runQuick() }
                            }
                            busyDetail = null
                            result.fold(
                                onSuccess = { report ->
                                    selfTestReport = report
                                    selfTestError = null
                                    YumeVpnRuntime.appendLog(
                                        VpnLogLevel.Info,
                                        "Local self-test completed:\n${report.toPlainText()}",
                                    )
                                },
                                onFailure = { throwable ->
                                    selfTestError = throwable.message ?: strings.toolOperationFailed
                                },
                            )
                        }
                    }
                },
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = strings.textToolsTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = strings.textToolsDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolModeButton(
                            modifier = Modifier.fillMaxWidth(),
                            label = strings.toolsModeFwxAes,
                            selected = mode == BaseFwxTextMode.FwxAes,
                            onClick = { mode = BaseFwxTextMode.FwxAes },
                        )
                        ToolModeButton(
                            modifier = Modifier.fillMaxWidth(),
                            label = strings.toolsModeFwxAesHeavy,
                            selected = mode == BaseFwxTextMode.FwxAesHeavy,
                            onClick = { mode = BaseFwxTextMode.FwxAesHeavy },
                        )
                        ToolModeButton(
                            modifier = Modifier.fillMaxWidth(),
                            label = strings.toolsModeB512,
                            selected = mode == BaseFwxTextMode.B512,
                            onClick = { mode = BaseFwxTextMode.B512 },
                        )
                        ToolModeButton(
                            modifier = Modifier.fillMaxWidth(),
                            label = strings.toolsModePb512,
                            selected = mode == BaseFwxTextMode.Pb512,
                            onClick = { mode = BaseFwxTextMode.Pb512 },
                        )
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        label = { Text(strings.toolInputLabel) },
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.passwordLabel) },
                        supportingText = { Text(strings.toolPasswordHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            if (busyDetail != null) return@Button
                            scope.launch {
                                busyDetail = strings.processingText(strings.encryptTextButton, mode.label(strings))
                                val result = withContext(Dispatchers.Default) {
                                    runTextTool(
                                        mode = mode,
                                        input = input,
                                        password = password,
                                        inputRequiredMessage = strings.toolInputRequired,
                                        encrypt = true,
                                    )
                                }
                                busyDetail = null
                                result.fold(
                                    onSuccess = { value ->
                                        output = value
                                        error = null
                                    },
                                    onFailure = { throwable ->
                                        error = throwable.message ?: strings.toolOperationFailed
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busyDetail == null,
                    ) {
                        Text(strings.encryptTextButton)
                    }
                    FilledTonalButton(
                        onClick = {
                            if (busyDetail != null) return@FilledTonalButton
                            scope.launch {
                                busyDetail = strings.processingText(strings.decryptTextButton, mode.label(strings))
                                val result = withContext(Dispatchers.Default) {
                                    runTextTool(
                                        mode = mode,
                                        input = input,
                                        password = password,
                                        inputRequiredMessage = strings.toolInputRequired,
                                        encrypt = false,
                                    )
                                }
                                busyDetail = null
                                result.fold(
                                    onSuccess = { value ->
                                        output = value
                                        error = null
                                    },
                                    onFailure = { throwable ->
                                        error = throwable.message ?: strings.toolOperationFailed
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busyDetail == null,
                    ) {
                        Text(strings.decryptTextButton)
                    }
                    OutlinedButton(
                        onClick = {
                            input = ""
                            output = ""
                            password = ""
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = busyDetail == null,
                    ) {
                        Text(strings.clearTextButton)
                    }
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        label = { Text(strings.toolOutputLabel) },
                    )
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalSelfTestCard(
    report: YumeLocalSelfTestReport?,
    error: String?,
    busy: Boolean,
    onRun: () -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var copied by remember(report) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = strings.localSelfTestTitle,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = strings.localSelfTestDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRun,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(if (report == null) strings.runLocalSelfTestButton else strings.rerunLocalSelfTestButton)
            }
            if (report == null && error.isNullOrBlank()) {
                Text(
                    text = strings.localSelfTestNoResults,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            report?.let { current ->
                Text(
                    text = strings.localSelfTestCompleted(current.elapsedLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        LocalSelfTestRow(row)
                    }
                }
                OutlinedButton(
                    onClick = {
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText("Yume Android local self-test", current.toPlainText()),
                        )
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.copyLocalSelfTestReport)
                }
                if (copied) {
                    Text(
                        text = strings.localSelfTestCopied,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LocalSelfTestRow(row: YumeLocalSelfTestRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text(
                text = row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.metric,
            style = MaterialTheme.typography.labelLarge,
            color = if (row.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            textAlign = TextAlign.End,
        )
    }
}

private fun YumeLocalSelfTestReport.elapsedLabel(): String {
    return if (elapsedMs < 1_000L) {
        "$elapsedMs ms"
    } else {
        String.format(Locale.US, "%.2f s", elapsedMs / 1_000.0)
    }
}

private fun BaseFwxTextMode.label(strings: com.fixcraft.yume.i18n.AppStrings): String {
    return when (this) {
        BaseFwxTextMode.FwxAes -> strings.toolsModeFwxAes
        BaseFwxTextMode.FwxAesHeavy -> strings.toolsModeFwxAesHeavy
        BaseFwxTextMode.B512 -> strings.toolsModeB512
        BaseFwxTextMode.Pb512 -> strings.toolsModePb512
    }
}

@Composable
private fun ToolModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(
                text = label,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(
                text = label,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun runTextTool(
    mode: BaseFwxTextMode,
    input: String,
    password: String,
    inputRequiredMessage: String,
    encrypt: Boolean,
): Result<String> {
    return runCatching {
        require(input.isNotBlank()) { inputRequiredMessage }
        when (mode) {
            BaseFwxTextMode.FwxAes -> {
                if (encrypt) BaseFwxCompat.fwxAesEncryptText(input, password)
                else BaseFwxCompat.fwxAesDecryptText(input, password)
            }

            BaseFwxTextMode.FwxAesHeavy -> {
                if (encrypt) BaseFwxCompat.fwxAesHeavyEncryptText(input, password)
                else BaseFwxCompat.fwxAesHeavyDecryptText(input, password)
            }

            BaseFwxTextMode.B512 -> {
                if (encrypt) BaseFwxCompat.b512Encode(input, password)
                else BaseFwxCompat.b512Decode(input, password)
            }

            BaseFwxTextMode.Pb512 -> {
                if (encrypt) BaseFwxCompat.pb512Encode(input, password)
                else BaseFwxCompat.pb512Decode(input, password)
            }
        }
    }
}
