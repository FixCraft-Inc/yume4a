package com.fixcraft.yume.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.KitRefusal
import com.fixcraft.yume.ui.kits.ServiceActions
import com.fixcraft.yume.ui.theme.YumeVPNTheme

private val nothing = ServiceActions(
    add = { _, _ -> }, authorize = {}, catalog = {}, choose = { _, _, _ -> }, useProvider = {},
    cancel = {}, remove = {}, code = { _, code -> code.fill('\u0000') }, reload = {},
)

/** The production app drawn from [state], with callbacks that do nothing. */
@Composable
internal fun FixtureApp(state: MainUiState, preparing: LanguagePreparationUiState? = null) {
    YumeVPNTheme(
        darkTheme = isSystemInDarkTheme(),
        themeColorMode = state.appSettings.themeColorMode,
        amoledDarkTheme = state.appSettings.amoledDarkTheme,
    ) {
        YumeApp(
            uiState = state, serviceActions = nothing, onConnect = {}, onDisconnect = {},
            onRecoverKitIndex = {}, onSelectKit = {}, onRenameKit = { _, _ -> }, onDeleteKit = {},
            onImportKit = { _, code, _ -> code.fill('\u0000'); KitImport.Refused(KitRefusal.Storage) },
            onReportNotice = {}, onBuildReport = { "" }, onClearLog = {}, onSelectThemeColorMode = {},
            onSetAmoledDarkTheme = {}, isDarkTheme = isSystemInDarkTheme(),
            onEnableAppDataPassword = { _, _ -> null }, onChangeAppDataPassword = { _, _, _ -> null },
            onDisableAppDataPassword = { null }, onSelectLanguage = {}, onSetAdvancedMode = {},
            onSetDnsServers = { true }, onSetCarryIpv6 = {}, onSelectTuningPreset = {},
            onMoveTuningSlider = { _, _ -> }, onApplyTuning = {}, onUnlockTuningExpert = {},
            onApplyTuningExpert = { _, _ -> true }, onDeleteDownloadedLanguage = {},
            languagePreparationState = preparing, onClearNotice = {},
        )
    }
}
