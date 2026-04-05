package com.fixcraft.yume.ui.tools

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fixcraft.yume.crypto.BaseFwxCompat
import com.fixcraft.yume.i18n.appStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()

    busyDetail?.let { detail ->
        Dialog(onDismissRequest = {}) {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label, maxLines = 1)
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
