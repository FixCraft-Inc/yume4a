package com.fixcraft.yume.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fixcraft.yume.AndroidSystemSettings
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.TuningUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.TuningLimits
import com.fixcraft.yume.data.TuningPreset
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AppLanguageGroup
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.FactRow
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import com.fixcraft.yume.ui.tabMovesFocusOn
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onSelectThemeColorMode: (ThemeColorMode) -> Unit,
    onSetAmoledDarkTheme: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    onEnableAppDataPassword: suspend (String, String) -> String?,
    onChangeAppDataPassword: suspend (String, String, String) -> String?,
    onDisableAppDataPassword: suspend (String) -> String?,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSetAdvancedMode: (Boolean) -> Unit,
    onSetDnsServers: (String) -> Boolean,
    onSetCarryIpv6: (Boolean) -> Unit,
    onMoveTuningSlider: (String, Int) -> Unit,
    onApplyTuning: () -> Unit,
    onUnlockTuningExpert: () -> Unit,
    onApplyTuningExpert: (String, String) -> Boolean,
    onDeleteDownloadedLanguage: (AppLanguage.AutoTranslated) -> Unit,
    onOpenCredits: () -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var passwordDialogMode by remember { mutableStateOf<AppDataPasswordDialogMode?>(null) }
    var showExpertWarning by remember { mutableStateOf(false) }
    var showExpertEditor by remember { mutableStateOf(false) }
    val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        !AndroidSystemSettings.isIgnoringBatteryOptimizations(context)
    } else {
        false
    }
    val languageSummary = if (uiState.appSettings.language == AppLanguage.SystemDefault) {
        val effectiveSystemLanguage = AppLanguage.fromLocaleTag(
            AppLocaleManager.resolveSystemLocale(context).toLanguageTag()
        )
        val effectiveLanguageName = when (effectiveSystemLanguage) {
            is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(effectiveSystemLanguage)
            else -> strings.languageName(effectiveSystemLanguage)
        }
        "${strings.languageName(AppLanguage.SystemDefault)} ($effectiveLanguageName)"
    } else {
        strings.languageSummary(uiState.appSettings.language)
    }
    val notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    if (showLanguageDialog) {
        val languageOptions = remember { AppLanguageCatalog.groupedOptions() }
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            modifier = Modifier.appDialogWidth(),
            title = { Text(strings.languageGroupTitle) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguageGroup.entries.forEach { group ->
                        val groupItems = languageOptions.filter { it.group == group }
                        item(key = "header_${group.name}") {
                            Text(
                                text = when (group) {
                                    AppLanguageGroup.Maintained -> strings.maintainedLanguagesTitle
                                    AppLanguageGroup.AutoTranslated -> strings.autoTranslatedLanguagesTitle
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(
                            items = groupItems,
                            key = { option -> option.language.wireValue },
                        ) { option ->
                            SettingChoiceCard(
                                title = when (option.language) {
                                    is AppLanguage.AutoTranslated -> option.displayName
                                    else -> strings.languageName(option.language)
                                },
                                description = when (option.language) {
                                    is AppLanguage.AutoTranslated -> {
                                        buildString {
                                            append(strings.languageDescription(option.language))
                                            if (option.language.localeTag in uiState.downloadedLanguageTags) {
                                                append(" (")
                                                append(strings.downloadedLabel)
                                                append(')')
                                            }
                                        }
                                    }

                                    else -> strings.languageDescription(option.language)
                                },
                                selected = uiState.appSettings.language == option.language,
                                enabled = true,
                                trailingContent = when (option.language) {
                                    is AppLanguage.AutoTranslated -> {
                                        if (option.language.localeTag in uiState.downloadedLanguageTags) {
                                            {
                                                IconButton(
                                                    onClick = { onDeleteDownloadedLanguage(option.language) },
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_delete_action),
                                                        contentDescription = strings.removeDownloadedLanguageLabel,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                    }

                                    else -> null
                                },
                                onClick = {
                                    onSelectLanguage(option.language)
                                    showLanguageDialog = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(strings.close)
                }
            },
            properties = appDialogProperties,
        )
    }

    if (showDnsDialog) {
        var text by remember { mutableStateOf(uiState.appSettings.dnsServers.joinToString(", ")) }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            modifier = Modifier.appDialogWidth(),
            title = { Text(strings.dnsServersLabel) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(200)
                        invalid = false
                    },
                    supportingText = {
                        Text(if (invalid) strings.dnsServerFormatError else strings.dnsServersHelper)
                    },
                    isError = invalid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tabMovesFocusOn(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onSetDnsServers(text)) showDnsDialog = false else invalid = true
                    },
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDnsDialog = false }) {
                    Text(strings.cancel)
                }
            },
            properties = appDialogProperties,
        )
    }

    passwordDialogMode?.let { mode ->
        AppDataPasswordDialog(
            mode = mode,
            onDismiss = { passwordDialogMode = null },
            onEnable = onEnableAppDataPassword,
            onChange = onChangeAppDataPassword,
            onDisable = onDisableAppDataPassword,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TuningCard(
                uiState = uiState,
                onMoveSlider = onMoveTuningSlider,
                onApply = onApplyTuning,
                onOpenExpert = { showExpertWarning = true },
            )
        }

        item {
            SettingGroupCard(
                title = strings.themeGroupTitle,
                subtitle = null,
            ) {
                ThemeColorMode.entries.forEach { mode ->
                    SettingChoiceCard(
                        title = strings.themeColorModeName(mode),
                        description = strings.themeColorModeDescription(mode),
                        selected = uiState.appSettings.themeColorMode == mode,
                        enabled = true,
                        onClick = { onSelectThemeColorMode(mode) },
                    )
                }
                if (isDarkTheme) {
                    SettingChoiceCard(
                        title = strings.amoledTitle,
                        description = strings.amoledDescription,
                        selected = uiState.appSettings.amoledDarkTheme,
                        enabled = true,
                        toggles = true,
                        onClick = { onSetAmoledDarkTheme(!uiState.appSettings.amoledDarkTheme) },
                    )
                }
            }
        }

        item {
            SettingGroupCard(
                title = strings.appDataGroupTitle,
                subtitle = strings.appDataSubtitle,
            ) {
                SettingFact(
                    strings.appDataProtectionLabel,
                    if (uiState.appDataPasswordEnabled) strings.passwordProtected else strings.androidProtected,
                )
                if (uiState.appDataPasswordEnabled) {
                    FilledTonalButton(
                        onClick = { passwordDialogMode = AppDataPasswordDialogMode.Change },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.changePasswordButton)
                    }
                    OutlinedButton(
                        onClick = { passwordDialogMode = AppDataPasswordDialogMode.Disable },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.removePasswordButton)
                    }
                } else {
                    OutlinedButton(
                        onClick = { passwordDialogMode = AppDataPasswordDialogMode.Enable },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.setPasswordButton)
                    }
                }
            }
        }

        item {
            SettingGroupCard(
                title = strings.languageGroupTitle,
                subtitle = null,
            ) {
                SettingActionRow(
                    label = strings.appLanguageLabel,
                    value = languageSummary,
                    iconRes = R.drawable.ic_translate_action,
                    onClick = { showLanguageDialog = true },
                )
            }
        }

        item {
            SettingGroupCard(
                title = strings.advancedGroupTitle,
                subtitle = null,
            ) {
                SettingToggleRow(
                    title = strings.advancedModeTitle,
                    description = strings.advancedModeDescription,
                    checked = uiState.appSettings.advancedMode,
                    onCheckedChange = onSetAdvancedMode,
                )
            }
        }

        if (uiState.appSettings.advancedMode) {
            item {
                SettingGroupCard(
                    title = strings.networkGroupTitle,
                    subtitle = strings.appliesOnNextConnect,
                ) {
                    SettingActionRow(
                        label = strings.dnsServersLabel,
                        value = uiState.appSettings.dnsServers.joinToString(", "),
                        onClick = { showDnsDialog = true },
                    )
                    SettingToggleRow(
                        title = strings.carryIpv6Title,
                        description = strings.carryIpv6Description,
                        checked = uiState.appSettings.carryIpv6,
                        onCheckedChange = onSetCarryIpv6,
                    )
                }
            }
        }

        item {
            SettingGroupCard(
                title = strings.deviceGroupTitle,
                subtitle = null,
            ) {
                SettingFact(strings.notificationsLabel, if (notificationsAllowed) strings.allowed else strings.permissionRequired)
                SettingFact(strings.batteryOptimizationLabel, if (batteryOptimized) strings.optimized else strings.unrestricted)
                SettingFact(strings.dynamicColorsLabel, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) strings.available else strings.unavailableOnAndroidVersion)
            }
        }

        item {
            SettingGroupCard(
                title = strings.aboutGroupTitle,
                subtitle = null,
            ) {
                SettingFact(strings.authorLabel, "F1xGOD")
                SettingFact(strings.companyLabel, "FixCraft, Inc.")
                SettingFact(strings.appLabel, "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                SettingFact(strings.yumeLabel, BuildConfig.YUME_VERSION)
                SettingFact(strings.basefwxLabel, BuildConfig.BASEFWX_ENGINE_VERSION)
                OutlinedButton(
                    onClick = onOpenCredits,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.creditsTitle)
                }
            }
        }

        item {
            SettingGroupCard(
                title = strings.systemGroupTitle,
                subtitle = null,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    OutlinedButton(
                        onClick = { AndroidSystemSettings.openBatteryOptimizationControls(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (batteryOptimized) strings.allowBackground else strings.batterySettingsButton)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.notificationSettingsButton)
                    }
                }
            }
        }
    }

    if (showExpertWarning) {
        ExpertWarningDialog(
            onDismiss = { showExpertWarning = false },
            onConfirm = {
                showExpertWarning = false
                onUnlockTuningExpert()
                showExpertEditor = true
            },
        )
    }
    if (showExpertEditor && uiState.tuning.expertUnlocked) {
        ExpertEditorDialog(
            tuning = uiState.tuning,
            onDismiss = { showExpertEditor = false },
            onApply = onApplyTuningExpert,
        )
    }
}

private enum class AppDataPasswordDialogMode {
    Enable,
    Change,
    Disable,
}

@Composable
private fun TuningCard(
    uiState: MainUiState,
    onMoveSlider: (String, Int) -> Unit,
    onApply: () -> Unit,
    onOpenExpert: () -> Unit,
) {
    val strings = appStrings()
    val tuning = uiState.tuning
    SettingGroupCard(title = strings.tuningGroupTitle, subtitle = strings.tuningSubtitle) {
        if (uiState.selectedKit == null || tuning.limits == null) {
            Text(strings.tuningNoKit, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingGroupCard
        }
        val preset = tuning.preset
        TuningSliderRow(strings.tuningStealth, strings.tuningStealthNote, "stealth", preset, tuning.saving, onMoveSlider)
        TuningSliderRow(strings.tuningSpeed, strings.tuningSpeedNote, "speed", preset, tuning.saving, onMoveSlider)
        TuningSliderRow(strings.tuningSecurity, strings.tuningSecurityNote, "security", preset, tuning.saving, onMoveSlider)
        TuningLimitsFacts(tuning.limits, strings)
        preset?.let { selected ->
            val measured = if (selected.measurementReference == "epoch-comparison") {
                strings.tuningEpochConditions
            } else strings.tuningWindowConditions
            Text(strings.tuningMeasuredTitle, style = MaterialTheme.typography.titleSmall)
            Text(measured, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (selected.measurementReference == "epoch-comparison") strings.tuningMeasurementLimit else strings.tuningWindowLimit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                selected.measurementValues.mapIndexed { index, value ->
                    val rtt = selected.measurementRttMs.getOrElse(index) { 0 }
                    "$rtt ms: $value ${if (selected.measurementReference == "epoch-comparison") "%" else "Mbit/s"}"
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(strings.tuningCustom, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(
            onClick = onApply,
            enabled = !tuning.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(strings.tuningApply) }
        OutlinedButton(onClick = onOpenExpert, modifier = Modifier.fillMaxWidth()) {
            Text(strings.tuningExpert)
        }
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    note: String,
    axis: String,
    preset: TuningPreset?,
    saving: Boolean,
    onMoveSlider: (String, Int) -> Unit,
) {
    val strings = appStrings()
    val level = preset?.levels?.get(axis) ?: 3
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                String.format(strings.tuningLevelTemplate, level),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = level.toFloat(),
            onValueChange = { onMoveSlider(axis, it.toInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            enabled = preset != null && !saving,
        )
    }
}

@Composable
private fun TuningLimitsFacts(limits: TuningLimits, strings: com.fixcraft.yume.i18n.AppStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FactRow(strings.tuningFieldEpoch, "${limits.maxEpochBytes / 1048576} MiB")
        FactRow(strings.tuningFieldFlow, "${limits.maxQueuedBytes / 1048576} MiB")
        FactRow(strings.tuningFieldCredits, limits.creditReturnsPerWindow.toString())
        FactRow(strings.tuningFieldIdle, if (limits.idleEpochRotation) strings.tuningOn else strings.tuningOff)
    }
}

@Composable
private fun ExpertWarningDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = appStrings()
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.tuningExpert) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.tuningExpertWarning)
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = acknowledged,
                        role = Role.Checkbox,
                        onValueChange = { acknowledged = it },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text(strings.tuningExpertConfirm)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = acknowledged) { Text(strings.tuningExpertUnlock) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
        properties = appDialogProperties,
    )
}

@Composable
private fun ExpertEditorDialog(
    tuning: TuningUiState,
    onDismiss: () -> Unit,
    onApply: (String, String) -> Boolean,
) {
    val strings = appStrings()
    val limits = tuning.limits ?: return
    var epoch by remember(limits) { mutableStateOf(limits.maxEpochBytes.toString()) }
    var flow by remember(limits) { mutableStateOf(limits.maxQueuedBytes.toString()) }
    var credits by remember(limits) { mutableStateOf(limits.creditReturnsPerWindow.toString()) }
    var idle by remember(limits) { mutableStateOf(limits.idleEpochRotation.toString()) }
    val fields = listOf(
        Triple(strings.tuningFieldEpoch, "max_epoch_bytes", epoch),
        Triple(strings.tuningFieldFlow, "max_queued_bytes", flow),
        Triple(strings.tuningFieldCredits, "credit_returns_per_window", credits),
        Triple(strings.tuningFieldIdle, "idle_epoch_rotation", idle),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogWidth(),
        title = { Text(strings.tuningExpert) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { (label, key, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { next ->
                                when (key) {
                                    "max_epoch_bytes" -> epoch = next.take(32)
                                    "max_queued_bytes" -> flow = next.take(32)
                                    "credit_returns_per_window" -> credits = next.take(32)
                                    else -> idle = next.take(32)
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(onClick = { onApply(key, value) }) { Text(strings.save) }
                    }
                }
                Text(strings.tuningExpertOneAtTime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
        properties = appDialogProperties,
    )
}

@Composable
private fun AppDataPasswordDialog(
    mode: AppDataPasswordDialogMode,
    onDismiss: () -> Unit,
    onEnable: suspend (String, String) -> String?,
    onChange: suspend (String, String, String) -> String?,
    onDisable: suspend (String) -> String?,
) {
    val strings = appStrings()
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!submitting) {
                onDismiss()
            }
        },
        modifier = Modifier.appDialogWidth(),
        title = {
            Text(
                when (mode) {
                    AppDataPasswordDialogMode.Enable -> strings.setPasswordButton
                    AppDataPasswordDialogMode.Change -> strings.changePasswordButton
                    AppDataPasswordDialogMode.Disable -> strings.removePasswordButton
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (mode) {
                        AppDataPasswordDialogMode.Enable -> strings.enableAppDataPasswordDetail
                        AppDataPasswordDialogMode.Change -> strings.changeAppDataPasswordDetail
                        AppDataPasswordDialogMode.Disable -> strings.disableAppDataPasswordDetail
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode != AppDataPasswordDialogMode.Enable) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.currentPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (mode != AppDataPasswordDialogMode.Disable) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.newPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.confirmPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (submitting) return@TextButton
                    scope.launch {
                        submitting = true
                        error = when (mode) {
                            AppDataPasswordDialogMode.Enable -> onEnable(newPassword, confirmPassword)
                            AppDataPasswordDialogMode.Change -> onChange(currentPassword, newPassword, confirmPassword)
                            AppDataPasswordDialogMode.Disable -> onDisable(currentPassword)
                        }
                        if (error == null) {
                            onDismiss()
                        }
                        submitting = false
                    }
                },
            ) {
                Text(strings.save)
            }
        },
        properties = appDialogProperties,
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) {
                Text(strings.cancel)
            }
        },
    )
}

@Composable
private fun SettingGroupCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * One choice of a group, marked by its radio button, or with [toggles] an option that
 * turns on and off by itself, marked by its checkbox. The card is the one target. Its
 * state is in its semantics and its mark, not only in its color.
 */
@Composable
private fun SettingChoiceCard(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    toggles: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val choice = if (toggles) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(choice),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            !enabled -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (toggles) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            } else {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

/**
 * A setting that turns on and off. The row is its one target, named by its text, and
 * the switch only shows the state.
 */
@Composable
private fun SettingToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun SettingActionRow(
    label: String,
    value: String,
    iconRes: Int? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconRes?.let { resId ->
                Icon(
                    painter = painterResource(resId),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                FactRow(
                    label = label,
                    value = value,
                    labelStyle = MaterialTheme.typography.bodyLarge,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingFact(
    label: String,
    value: String,
) {
    FactRow(label, value, labelStyle = MaterialTheme.typography.bodySmall)
}
