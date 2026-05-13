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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fixcraft.yume.AndroidSystemSettings
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.supportsUserSelectablePerformanceProfiles
import com.fixcraft.yume.i18n.AppLanguageCatalog
import com.fixcraft.yume.i18n.AppLanguageGroup
import com.fixcraft.yume.i18n.AppLocaleManager
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.appDialogProperties
import com.fixcraft.yume.ui.appDialogWidth
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onSelectTechnique: (ConnectionTechnique) -> Unit,
    onSelectPerformanceProfile: (PerformanceProfile) -> Unit,
    onSelectThemeColorMode: (ThemeColorMode) -> Unit,
    onSetAmoledDarkTheme: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    onEnableAppDataPassword: suspend (String, String) -> String?,
    onChangeAppDataPassword: suspend (String, String, String) -> String?,
    onDisableAppDataPassword: suspend (String) -> String?,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectLogLanguage: (LogLanguage) -> Unit,
    onSetAdvancedMode: (Boolean) -> Unit,
    onDeleteDownloadedLanguage: (AppLanguage.AutoTranslated) -> Unit,
    onOpenCredits: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogLanguageDialog by remember { mutableStateOf(false) }
    var passwordDialogMode by remember { mutableStateOf<AppDataPasswordDialogMode?>(null) }
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
    val logLanguageSummary = strings.logLanguageSummary(uiState.appSettings.logLanguage)
    val notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val selectedTechniqueSupportsProfiles =
        uiState.appSettings.connectionTechnique.supportsUserSelectablePerformanceProfiles()

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

    if (showLogLanguageDialog) {
        val logLanguageOptions = remember {
            listOf(
                LogLanguage.FollowApp,
                LogLanguage.English,
                LogLanguage.Russian,
                LogLanguage.Japanese,
                LogLanguage.Ukrainian,
            )
        }
        AlertDialog(
            onDismissRequest = { showLogLanguageDialog = false },
            modifier = Modifier.appDialogWidth(),
            title = { Text(strings.logLanguageDialogTitle) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = logLanguageOptions,
                        key = { language -> language.wireValue },
                    ) { language ->
                        SettingChoiceCard(
                            title = strings.logLanguageName(language),
                            description = strings.logLanguageDescription(language),
                            selected = uiState.appSettings.logLanguage == language,
                            enabled = true,
                            onClick = {
                                onSelectLogLanguage(language)
                                showLogLanguageDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLanguageDialog = false }) {
                    Text(strings.close)
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
            SettingGroupCard(
                title = strings.connectionGroupTitle,
                subtitle = null,
            ) {
                ConnectionTechnique.entries.forEach { technique ->
                    val enabled = when (technique) {
                        ConnectionTechnique.Tun2SocksStable -> true
                        ConnectionTechnique.NativeDirectBeta -> BuildConfig.ENABLE_NATIVE_DIRECT_BETA
                    }
                    SettingChoiceCard(
                        title = strings.connectionTechniqueName(technique),
                        description = if (!enabled) {
                            strings.unavailableInBuild
                        } else {
                            strings.connectionTechniqueDescription(technique)
                        },
                        selected = uiState.appSettings.connectionTechnique == technique,
                        enabled = enabled,
                        onClick = { onSelectTechnique(technique) },
                    )
                }
            }
        }

        item {
            SettingGroupCard(
                title = strings.profileGroupTitle,
                subtitle = if (selectedTechniqueSupportsProfiles) {
                    null
                } else {
                    strings.nativeDirectFixedTuningDescription
                },
            ) {
                if (selectedTechniqueSupportsProfiles) {
                    PerformanceProfile.entries.forEach { profile ->
                        SettingChoiceCard(
                            title = strings.performanceProfileName(profile),
                            description = strings.performanceProfileDescription(profile),
                            selected = uiState.appSettings.performanceProfile == profile,
                            enabled = true,
                            onClick = { onSelectPerformanceProfile(profile) },
                        )
                    }
                } else {
                    SettingFact(
                        strings.profileLabel,
                        strings.nativeDirectFixedTuningName,
                    )
                }
            }
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
                if (uiState.appSettings.advancedMode) {
                    SettingActionRow(
                        label = strings.logLanguageLabel,
                        value = logLanguageSummary,
                        iconRes = R.drawable.ic_translate_action,
                        onClick = { showLogLanguageDialog = true },
                    )
                }
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

        item {
            SettingGroupCard(
                title = strings.deviceGroupTitle,
                subtitle = null,
            ) {
                SettingFact(strings.notificationsLabel, if (notificationsAllowed) strings.allowed else strings.permissionRequired)
                SettingFact(strings.batteryOptimizationLabel, if (batteryOptimized) strings.optimized else strings.unrestricted)
                SettingFact(strings.nativeDirectBetaLabel, if (BuildConfig.ENABLE_NATIVE_DIRECT_BETA) strings.enabled else strings.disabled)
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
                SettingFact(strings.yumeLabel, BuildConfig.YUME_PROTOCOL_VERSION)
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
                FilledTonalButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.diagnosticsButton)
                }
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
}

private enum class AppDataPasswordDialogMode {
    Enable,
    Change,
    Disable,
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
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingChoiceCard(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

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
            .clickable { onCheckedChange(!checked) },
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingFact(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
