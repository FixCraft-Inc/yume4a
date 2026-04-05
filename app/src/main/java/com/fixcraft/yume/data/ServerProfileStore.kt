package com.fixcraft.yume.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class ServerProfileStore private constructor(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vaultStore = VaultJsonMapStore(appContext, VAULT_NAME)

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(
        loadSelectedProfileId()
            ?.takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.firstOrNull()?.id
    )
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    fun saveProfile(profile: ServerProfile) {
        val existing = _profiles.value.toMutableList()
        val index = existing.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            existing[index] = profile
        } else {
            existing += profile
        }
        persistProfiles(existing)
        if (_selectedProfileId.value == null) {
            selectProfile(profile.id)
        }
    }

    fun deleteProfile(profileId: String) {
        val filtered = _profiles.value.filterNot { it.id == profileId }
        persistProfiles(filtered)
        if (_selectedProfileId.value == profileId) {
            selectProfile(filtered.firstOrNull()?.id)
        }
    }

    fun selectProfile(profileId: String?) {
        _selectedProfileId.value = profileId?.takeIf { id -> _profiles.value.any { it.id == id } }
        vaultStore.putString(KEY_SELECTED_PROFILE_ID, _selectedProfileId.value)
    }

    fun getProfile(profileId: String): ServerProfile? = _profiles.value.firstOrNull { it.id == profileId }

    private fun loadProfiles(): List<ServerProfile> {
        migrateLegacyPrefsIfNeeded()
        val raw = vaultStore.getString(KEY_PROFILES_JSON).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(ServerProfile.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistProfiles(profiles: List<ServerProfile>) {
        val payload = JSONArray().apply {
            profiles.forEach { put(it.toJson()) }
        }
        vaultStore.putString(KEY_PROFILES_JSON, payload.toString())
        _profiles.value = profiles
    }

    private fun loadSelectedProfileId(): String? {
        migrateLegacyPrefsIfNeeded()
        return vaultStore.getString(KEY_SELECTED_PROFILE_ID)
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (!vaultStore.isEmpty()) {
            return
        }
        val legacyProfiles = legacyPrefs.getString(KEY_PROFILES_JSON, null)
        val legacySelected = legacyPrefs.getString(KEY_SELECTED_PROFILE_ID, null)
        if (legacyProfiles == null && legacySelected == null) {
            return
        }
        legacyProfiles?.let { vaultStore.putString(KEY_PROFILES_JSON, it) }
        legacySelected?.let { vaultStore.putString(KEY_SELECTED_PROFILE_ID, it) }
        legacyPrefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "yume_vpn_profiles"
        private const val KEY_PROFILES_JSON = "profiles_json"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val VAULT_NAME = "server_profiles"

        @Volatile
        private var instance: ServerProfileStore? = null

        fun get(context: Context): ServerProfileStore {
            return instance ?: synchronized(this) {
                instance ?: ServerProfileStore(context).also { instance = it }
            }
        }
    }
}
