package com.fixcraft.yume.data

import android.content.Context
import androidx.core.content.edit
import com.fixcraft.yume.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionTechnique(
    val wireValue: String,
    val displayName: String,
    val description: String,
) {
    Tun2SocksStable(
        wireValue = "tun2socks_stable",
        displayName = "Tun2Socks stable",
        description = "Stable Android path through tun2socks.",
    ),
    NativeDirectAlpha(
        wireValue = "native_direct_alpha",
        displayName = "Native direct",
        description = "Experimental native path.",
    );

    companion object {
        fun fromWireValue(value: String): ConnectionTechnique? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PerformanceProfile(
    val wireValue: String,
    val displayName: String,
    val description: String,
) {
    Latency(
        wireValue = "latency",
        displayName = "Latency",
        description = "Lower queueing for interactive traffic.",
    ),
    Balanced(
        wireValue = "balanced",
        displayName = "Balanced",
        description = "Default mixed-use profile.",
    ),
    Throughput(
        wireValue = "throughput",
        displayName = "Throughput",
        description = "Larger buffers for sustained transfers.",
    );

    companion object {
        fun fromWireValue(value: String): PerformanceProfile? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class ThemeColorMode(
    val wireValue: String,
    val displayName: String,
    val description: String,
) {
    Yume(
        wireValue = "yume",
        displayName = "Yume",
        description = "Muted built-in colors.",
    ),
    System(
        wireValue = "system",
        displayName = "System",
        description = "Material You on Android 12+.",
    ),
    Amoled(
        wireValue = "amoled",
        displayName = "AMOLED",
        description = "True black dark theme.",
    );

    companion object {
        fun fromWireValue(value: String): ThemeColorMode? = entries.firstOrNull { it.wireValue == value }
    }
}

data class AppSettings(
    val connectionTechnique: ConnectionTechnique = ConnectionTechnique.Tun2SocksStable,
    val performanceProfile: PerformanceProfile = PerformanceProfile.Balanced,
    val themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
)

class AppSettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun updateConnectionTechnique(technique: ConnectionTechnique) {
        val persistedTechnique = sanitizeTechnique(technique)
        persist(_settings.value.copy(connectionTechnique = persistedTechnique))
    }

    fun updatePerformanceProfile(profile: PerformanceProfile) {
        persist(_settings.value.copy(performanceProfile = profile))
    }

    fun updateThemeColorMode(mode: ThemeColorMode) {
        persist(_settings.value.copy(themeColorMode = mode))
    }

    private fun loadSettings(): AppSettings {
        val storedTechnique = prefs.getString(KEY_CONNECTION_TECHNIQUE, null)
            ?.let(ConnectionTechnique::fromWireValue)
            ?: ConnectionTechnique.Tun2SocksStable
        val storedProfile = prefs.getString(KEY_PERFORMANCE_PROFILE, null)
            ?.let(PerformanceProfile::fromWireValue)
            ?: PerformanceProfile.Balanced
        val storedThemeColorMode = prefs.getString(KEY_THEME_COLOR_MODE, null)
            ?.let(ThemeColorMode::fromWireValue)
            ?: ThemeColorMode.Yume
        return AppSettings(
            connectionTechnique = sanitizeTechnique(storedTechnique),
            performanceProfile = storedProfile,
            themeColorMode = storedThemeColorMode,
        )
    }

    private fun persist(settings: AppSettings) {
        prefs.edit {
            putString(KEY_CONNECTION_TECHNIQUE, settings.connectionTechnique.wireValue)
            putString(KEY_PERFORMANCE_PROFILE, settings.performanceProfile.wireValue)
            putString(KEY_THEME_COLOR_MODE, settings.themeColorMode.wireValue)
        }
        _settings.value = settings
    }

    private fun sanitizeTechnique(technique: ConnectionTechnique): ConnectionTechnique {
        return if (technique == ConnectionTechnique.NativeDirectAlpha && !BuildConfig.ENABLE_NATIVE_DIRECT_ALPHA) {
            ConnectionTechnique.Tun2SocksStable
        } else {
            technique
        }
    }

    companion object {
        private const val PREFS_NAME = "yume_app_settings"
        private const val KEY_CONNECTION_TECHNIQUE = "connection_technique"
        private const val KEY_PERFORMANCE_PROFILE = "performance_profile"
        private const val KEY_THEME_COLOR_MODE = "theme_color_mode"

        @Volatile
        private var instance: AppSettingsStore? = null

        fun get(context: Context): AppSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsStore(context).also { instance = it }
            }
        }
    }
}
