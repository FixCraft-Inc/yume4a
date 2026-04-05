package com.fixcraft.yume.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.BusyUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.i18n.ProvideAppStrings
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.diagnostics.DiagnosticsScreen
import com.fixcraft.yume.ui.home.AuthKeyImportDialog
import com.fixcraft.yume.ui.home.HomeDashboardScreen
import com.fixcraft.yume.ui.home.PendingAuthKeyImport
import com.fixcraft.yume.ui.security.SecurityScreen
import com.fixcraft.yume.ui.servers.ServersScreen
import com.fixcraft.yume.ui.settings.CreditsScreen
import com.fixcraft.yume.ui.settings.SettingsScreen
import com.fixcraft.yume.ui.tools.ToolsScreen

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val iconRes: Int,
)

private object AppRoute {
    const val Home = "home"
    const val Servers = "servers"
    const val Security = "security"
    const val Tools = "tools"
    const val Settings = "settings"
    const val Diagnostics = "diagnostics"
    const val Credits = "credits"
}

data class LanguagePreparationUiState(
    val title: String,
    val detail: String,
    val progress: Float?,
)

@Composable
fun YumeApp(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveProfile: suspend (String?, String, String, String, String, String, String, Boolean, String, String, String) -> String?,
    onImportCaFile: () -> Unit,
    onImportAuthKeyFile: () -> Unit,
    onSaveCaText: suspend (String, String) -> String?,
    onSaveAuthKeyText: suspend (String, String, Boolean, String) -> String?,
    pendingAuthKeyImport: PendingAuthKeyImport?,
    onImportAuthKeyPayload: suspend (String, ByteArray, Boolean, String) -> String?,
    onDismissAuthKeyImport: () -> Unit,
    onDeleteMaterial: (String) -> Unit,
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
    languagePreparationState: LanguagePreparationUiState?,
    busyState: BusyUiState?,
    diagnosticsReport: String,
    onClearNotice: () -> Unit,
) {
    val context = LocalContext.current
    ProvideAppStrings(context = context, language = uiState.appSettings.language) {
        val strings = appStrings()
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val currentRoute = currentDestination?.route ?: AppRoute.Home
        val showToolsTab = uiState.appSettings.advancedMode || currentRoute == AppRoute.Tools
        val topLevelDestinations = buildList {
            add(TopLevelDestination(AppRoute.Home, strings.homeTab, R.drawable.ic_home_nav))
            add(TopLevelDestination(AppRoute.Servers, strings.serversTab, R.drawable.ic_server_nav))
            add(TopLevelDestination(AppRoute.Security, strings.securityTab, R.drawable.ic_security_nav))
            if (showToolsTab) {
                add(TopLevelDestination(AppRoute.Tools, strings.toolsTab, R.drawable.ic_tools_nav))
            }
            add(TopLevelDestination(AppRoute.Settings, strings.settingsTab, R.drawable.ic_settings_nav))
        }
        val title = when (currentRoute) {
            AppRoute.Home -> "Yume"
            AppRoute.Servers -> strings.serversTitle
            AppRoute.Security -> strings.securityTab
            AppRoute.Tools -> strings.toolsTitle
            AppRoute.Settings -> strings.settingsTab
            AppRoute.Diagnostics -> strings.diagnosticsTitle
            AppRoute.Credits -> strings.creditsTitle
            else -> "Yume"
        }
        val navigateToTopLevel: (String) -> Unit = { route ->
            if (route != currentRoute) {
                val restored = navController.popBackStack(route, inclusive = false)
                if (!restored) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
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
                    showBack = currentRoute == AppRoute.Diagnostics || currentRoute == AppRoute.Credits,
                    onBack = { navController.popBackStack() },
                )
            },
            bottomBar = {
                if (currentRoute != AppRoute.Diagnostics && currentRoute != AppRoute.Credits) {
                    BottomNavBar(
                        currentRoute = currentRoute,
                        destinations = topLevelDestinations,
                        onNavigate = navigateToTopLevel,
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
                enterTransition = { fadeIn(animationSpec = tween(120)) },
                exitTransition = { fadeOut(animationSpec = tween(90)) },
                popEnterTransition = { fadeIn(animationSpec = tween(120)) },
                popExitTransition = { fadeOut(animationSpec = tween(90)) },
            ) {
                composable(AppRoute.Home) {
                    HomeDashboardScreen(
                        uiState = uiState,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onOpenServers = { navigateToTopLevel(AppRoute.Servers) },
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
                        onSetAmoledDarkTheme = onSetAmoledDarkTheme,
                        isDarkTheme = isDarkTheme,
                        onEnableAppDataPassword = onEnableAppDataPassword,
                        onChangeAppDataPassword = onChangeAppDataPassword,
                        onDisableAppDataPassword = onDisableAppDataPassword,
                        onSelectLanguage = onSelectLanguage,
                        onSelectLogLanguage = onSelectLogLanguage,
                        onSetAdvancedMode = onSetAdvancedMode,
                        onDeleteDownloadedLanguage = onDeleteDownloadedLanguage,
                        onOpenCredits = {
                            navController.navigate(AppRoute.Credits)
                        },
                        onOpenDiagnostics = {
                            navController.navigate(AppRoute.Diagnostics)
                        },
                    )
                }
                composable(AppRoute.Tools) {
                    ToolsScreen()
                }
                composable(AppRoute.Credits) {
                    CreditsScreen()
                }
                composable(AppRoute.Diagnostics) {
                    DiagnosticsScreen(
                        uiState = uiState,
                        diagnosticsReport = diagnosticsReport,
                    )
                }
            }
        }
        pendingAuthKeyImport?.let { pendingImport ->
            AuthKeyImportDialog(
                pending = pendingImport,
                onDismiss = onDismissAuthKeyImport,
                onImport = onImportAuthKeyPayload,
            )
        }
        languagePreparationState?.let { state ->
            LanguagePreparationDialog(state)
        }
        busyState?.let { state ->
            BusyDialog(state)
        }
    }
}

@Composable
private fun LanguagePreparationDialog(
    state: LanguagePreparationUiState,
) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun BusyDialog(
    state: BusyUiState,
) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
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
    }
}

@Composable
private fun AppTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val strings = appStrings()
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.statusBarsPadding(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(72.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showBack,
                        enter = fadeIn(animationSpec = tween(90)),
                        exit = fadeOut(animationSpec = tween(70)),
                    ) {
                        TextButton(onClick = onBack) {
                            Text(strings.back)
                        }
                    }
                }

                AnimatedContent(
                    targetState = title,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(100, delayMillis = 20)) togetherWith
                            fadeOut(animationSpec = tween(80))
                    },
                    contentAlignment = Alignment.Center,
                    label = "top_bar_title",
                ) { activeTitle ->
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(72.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String,
    destinations: List<TopLevelDestination>,
    onNavigate: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { onNavigate(destination.route) },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = destination.label,
                                modifier = if (destination.route == AppRoute.Home) {
                                    Modifier.offset(y = (-1).dp)
                                } else {
                                    Modifier
                                },
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                maxLines = 1,
                            )
                        },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}
