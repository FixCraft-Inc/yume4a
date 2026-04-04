package com.fixcraft.yume.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ThemeColorMode

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onSelectTechnique: (ConnectionTechnique) -> Unit,
    onSelectPerformanceProfile: (PerformanceProfile) -> Unit,
    onSelectThemeColorMode: (ThemeColorMode) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true
    } else {
        false
    }
    val notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingGroupCard(
                title = "Tunnel mode",
                subtitle = "Stable is the default.",
            ) {
                ConnectionTechnique.entries.forEach { technique ->
                    val enabled = when (technique) {
                        ConnectionTechnique.Tun2SocksStable -> true
                        ConnectionTechnique.NativeDirectAlpha -> BuildConfig.ENABLE_NATIVE_DIRECT_ALPHA
                    }
                    SettingChoiceCard(
                        title = technique.displayName + if (technique == ConnectionTechnique.NativeDirectAlpha) " (Alpha)" else "",
                        description = technique.description + if (!enabled && technique == ConnectionTechnique.NativeDirectAlpha) {
                            " Not available in this build."
                        } else {
                            ""
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
                title = "Performance",
                subtitle = "Latency, balanced, or throughput.",
            ) {
                PerformanceProfile.entries.forEach { profile ->
                    SettingChoiceCard(
                        title = profile.displayName,
                        description = profile.description,
                        selected = uiState.appSettings.performanceProfile == profile,
                        enabled = true,
                        onClick = { onSelectPerformanceProfile(profile) },
                    )
                }
            }
        }

        item {
            SettingGroupCard(
                title = "Theme",
                subtitle = "Yume, system, or AMOLED.",
            ) {
                ThemeColorMode.entries.forEach { mode ->
                    SettingChoiceCard(
                        title = mode.displayName,
                        description = mode.description,
                        selected = uiState.appSettings.themeColorMode == mode,
                        enabled = true,
                        onClick = { onSelectThemeColorMode(mode) },
                    )
                }
            }
        }

        item {
            SettingGroupCard(
                title = "Device",
                subtitle = "Battery, notifications, and platform support.",
            ) {
                SettingFact("Notifications", if (notificationsAllowed) "Allowed" else "Permission required")
                SettingFact("Battery optimization", if (batteryOptimized) "Optimized" else "Unrestricted")
                SettingFact("Native direct alpha", if (BuildConfig.ENABLE_NATIVE_DIRECT_ALPHA) "Enabled" else "Disabled")
                SettingFact("Dynamic colors", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Available" else "Unavailable on this Android version")
            }
        }

        item {
            SettingGroupCard(
                title = "About",
                subtitle = null,
            ) {
                SettingFact("Author", "F1xGOD")
                SettingFact("Company", "FixCraft, Inc.")
                SettingFact("App", "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                SettingFact("Yume", BuildConfig.YUME_PROTOCOL_VERSION)
                SettingFact("BaseFWX", BuildConfig.BASEFWX_ENGINE_VERSION)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Tools",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FilledTonalButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Diagnostics")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Battery")
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                })
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Notifications")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingGroupCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
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

@Composable
private fun SettingChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
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
        Column(
            modifier = Modifier.padding(16.dp),
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
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingFact(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
