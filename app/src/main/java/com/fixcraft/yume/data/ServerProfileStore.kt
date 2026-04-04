package com.fixcraft.yume.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class ServerProfileStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(
        prefs.getString(KEY_SELECTED_PROFILE_ID, null)
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
        prefs.edit {
            putString(KEY_SELECTED_PROFILE_ID, _selectedProfileId.value)
        }
    }

    fun getProfile(profileId: String): ServerProfile? = _profiles.value.firstOrNull { it.id == profileId }

    private fun loadProfiles(): List<ServerProfile> {
        val raw = prefs.getString(KEY_PROFILES_JSON, null).orEmpty()
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
        prefs.edit {
            putString(KEY_PROFILES_JSON, payload.toString())
        }
        _profiles.value = profiles
    }

    companion object {
        private const val PREFS_NAME = "yume_vpn_profiles"
        private const val KEY_PROFILES_JSON = "profiles_json"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"

        @Volatile
        private var instance: ServerProfileStore? = null

        fun get(context: Context): ServerProfileStore {
            return instance ?: synchronized(this) {
                instance ?: ServerProfileStore(context).also { instance = it }
            }
        }
    }
}
