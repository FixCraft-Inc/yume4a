package com.fixcraft.yume.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.ui.diagnostics.DiagnosticsScreen
import com.fixcraft.yume.ui.home.HomeDashboardScreen
import com.fixcraft.yume.ui.security.SecurityScreen
import com.fixcraft.yume.ui.servers.ServersScreen
import com.fixcraft.yume.ui.settings.SettingsScreen

private data class TopLevelDestination(
    val route: String,
    val label: String,
)

private object AppRoute {
    const val Home = "home"
    const val Servers = "servers"
    const val Security = "security"
    const val Settings = "settings"
    const val Diagnostics = "diagnostics"
}

private val topLevelDestinations = listOf(
    TopLevelDestination(AppRoute.Home, "Home"),
    TopLevelDestination(AppRoute.Servers, "Servers"),
    TopLevelDestination(AppRoute.Security, "Security"),
    TopLevelDestination(AppRoute.Settings, "Settings"),
)

@Composable
fun YumeApp(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: (String?, String, String, String, String, String, String, Boolean, String, String, String) -> String?,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onSaveCaText: (String, String) -> String?,
    onSaveAuthKeyText: (String, String) -> String?,
    onDeleteMaterial: (String) -> Unit,
    onSelectTechnique: (ConnectionTechnique) -> Unit,
    onSelectPerformanceProfile: (PerformanceProfile) -> Unit,
    onSelectThemeColorMode: (ThemeColorMode) -> Unit,
    diagnosticsReport: String,
    onClearNotice: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: AppRoute.Home
    val title = when (currentRoute) {
        AppRoute.Home -> "Yume"
        AppRoute.Servers -> "Servers"
        AppRoute.Security -> "Security"
        AppRoute.Settings -> "Settings"
        AppRoute.Diagnostics -> "Diagnostics"
        else -> "Yume"
    }

    LaunchedEffect(uiState.bannerMessage) {
        uiState.bannerMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(uiState.noticeMessage) {
        uiState.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearNotice()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                showBack = currentRoute == AppRoute.Diagnostics,
                onBack = { navController.popBackStack() },
            )
        },
        bottomBar = {
            if (currentRoute != AppRoute.Diagnostics) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.Home,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppRoute.Home) {
                HomeDashboardScreen(
                    uiState = uiState,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenServers = {
                        navController.navigate(AppRoute.Servers) { launchSingleTop = true }
                    },
                )
            }
            composable(AppRoute.Servers) {
                ServersScreen(
                    uiState = uiState,
                    onSelectProfile = onSelectProfile,
                    onDeleteProfile = onDeleteProfile,
                    onSaveProfile = onSaveProfile,
                )
            }
            composable(AppRoute.Security) {
                SecurityScreen(
                    uiState = uiState,
                    onImportCaFile = onImportCaFile,
                    onImportAuthKeyFile = onImportAuthKeyFile,
                    onSaveCaText = onSaveCaText,
                    onSaveAuthKeyText = onSaveAuthKeyText,
                    onDeleteMaterial = onDeleteMaterial,
                )
            }
            composable(AppRoute.Settings) {
                SettingsScreen(
                    uiState = uiState,
                    onSelectTechnique = onSelectTechnique,
                    onSelectPerformanceProfile = onSelectPerformanceProfile,
                    onSelectThemeColorMode = onSelectThemeColorMode,
                    onOpenDiagnostics = {
                        navController.navigate(AppRoute.Diagnostics)
                    },
                )
            }
            composable(AppRoute.Diagnostics) {
                DiagnosticsScreen(
                    uiState = uiState,
                    diagnosticsReport = diagnosticsReport,
                )
            }
        }
    }
}

@Composable
private fun AppTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.statusBarsPadding(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (showBack) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart),
                    ) {
                        Text("Back")
                    }
                }
                Text(
                    text = title,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                topLevelDestinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    if (selected) {
                        Button(
                            onClick = { onNavigate(destination.route) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            Text(destination.label)
                        }
                    } else {
                        TextButton(
                            onClick = { onNavigate(destination.route) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(destination.label)
                        }
                    }
                }
            }
        }
    }
}
