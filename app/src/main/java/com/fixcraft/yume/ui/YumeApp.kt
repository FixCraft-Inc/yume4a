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
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.i18n.ProvideAppStrings
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.kits.KitsScreen
import com.fixcraft.yume.ui.logs.LogsScreen
import com.fixcraft.yume.ui.overview.OverviewScreen
import com.fixcraft.yume.ui.posture.PostureScreen
import com.fixcraft.yume.ui.settings.CreditsScreen
import com.fixcraft.yume.ui.settings.OpenSourceComponentsScreen
import com.fixcraft.yume.ui.settings.SettingsScreen

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val iconRes: Int,
)

private object AppRoute {
    const val Overview = "overview"
    const val Connect = "connect"
    const val Logs = "logs"
    const val Posture = "posture"
    const val Settings = "settings"
    const val Credits = "credits"
    const val Components = "components"
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
    onSelectKit: (String) -> Unit,
    onRenameKit: (String, String) -> Unit,
    onDeleteKit: (String) -> Unit,
    onImportKit: suspend (ByteArray, CharArray, String) -> KitImport,
    onReportNotice: (String) -> Unit,
    onBuildReport: () -> String,
    onClearLog: () -> Unit,
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
    onDeleteDownloadedLanguage: (AppLanguage.AutoTranslated) -> Unit,
    languagePreparationState: LanguagePreparationUiState?,
    onClearNotice: () -> Unit,
) {
    val context = LocalContext.current
    ProvideAppStrings(context = context, language = uiState.appSettings.language) {
        val strings = appStrings()
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: AppRoute.Overview
        val topLevelDestinations = listOf(
            TopLevelDestination(AppRoute.Overview, strings.overviewTab, R.drawable.ic_home_nav),
            TopLevelDestination(AppRoute.Connect, strings.connectTab, R.drawable.ic_server_nav),
            TopLevelDestination(AppRoute.Logs, strings.logsTab, R.drawable.ic_logs_nav),
            TopLevelDestination(AppRoute.Posture, strings.postureTab, R.drawable.ic_security_nav),
            TopLevelDestination(AppRoute.Settings, strings.settingsTab, R.drawable.ic_settings_nav),
        )
        val nested = currentRoute == AppRoute.Credits || currentRoute == AppRoute.Components
        val title = when (currentRoute) {
            AppRoute.Connect -> strings.connectTab
            AppRoute.Logs -> strings.logsTab
            AppRoute.Posture -> strings.postureTab
            AppRoute.Settings -> strings.settingsTab
            AppRoute.Credits -> strings.creditsTitle
            AppRoute.Components -> strings.openSourceComponentsTitle
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
                    showBack = nested,
                    onBack = { navController.popBackStack() },
                )
            },
            bottomBar = {
                if (!nested) {
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
                startDestination = AppRoute.Overview,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { fadeIn(animationSpec = tween(120)) },
                exitTransition = { fadeOut(animationSpec = tween(90)) },
                popEnterTransition = { fadeIn(animationSpec = tween(120)) },
                popExitTransition = { fadeOut(animationSpec = tween(90)) },
            ) {
                composable(AppRoute.Overview) {
                    OverviewScreen(
                        uiState = uiState,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onOpenKits = { navigateToTopLevel(AppRoute.Connect) },
                    )
                }
                composable(AppRoute.Connect) {
                    KitsScreen(
                        uiState = uiState,
                        onSelectKit = onSelectKit,
                        onRenameKit = onRenameKit,
                        onDeleteKit = onDeleteKit,
                        onImportKit = onImportKit,
                        onReportNotice = onReportNotice,
                    )
                }
                composable(AppRoute.Logs) {
                    LogsScreen(
                        uiState = uiState,
                        onBuildReport = onBuildReport,
                        onClearLog = onClearLog,
                        onReportNotice = onReportNotice,
                    )
                }
                composable(AppRoute.Posture) {
                    PostureScreen(uiState = uiState)
                }
                composable(AppRoute.Settings) {
                    SettingsScreen(
                        uiState = uiState,
                        onSelectThemeColorMode = onSelectThemeColorMode,
                        onSetAmoledDarkTheme = onSetAmoledDarkTheme,
                        isDarkTheme = isDarkTheme,
                        onEnableAppDataPassword = onEnableAppDataPassword,
                        onChangeAppDataPassword = onChangeAppDataPassword,
                        onDisableAppDataPassword = onDisableAppDataPassword,
                        onSelectLanguage = onSelectLanguage,
                        onSetAdvancedMode = onSetAdvancedMode,
                        onSetDnsServers = onSetDnsServers,
                        onSetCarryIpv6 = onSetCarryIpv6,
                        onDeleteDownloadedLanguage = onDeleteDownloadedLanguage,
                        onOpenCredits = {
                            navController.navigate(AppRoute.Credits)
                        },
                    )
                }
                composable(AppRoute.Credits) {
                    CreditsScreen(
                        onOpenComponents = { navController.navigate(AppRoute.Components) },
                    )
                }
                composable(AppRoute.Components) {
                    OpenSourceComponentsScreen()
                }
            }
        }
        languagePreparationState?.let { state ->
            LanguagePreparationDialog(state)
        }
        uiState.busyState?.let { state ->
            BusyDialog(state)
        }
    }
}

@Composable
private fun LanguagePreparationDialog(
    state: LanguagePreparationUiState,
) {
    Dialog(
        onDismissRequest = {},
        properties = appDialogProperties,
    ) {
        Card(
            modifier = Modifier.appDialogWidth(),
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
    Dialog(
        onDismissRequest = {},
        properties = appDialogProperties,
    ) {
        Card(
            modifier = Modifier.appDialogWidth(),
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
                                modifier = if (destination.route == AppRoute.Overview) {
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
