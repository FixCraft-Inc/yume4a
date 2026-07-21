package com.fixcraft.yume.data

/**
 * Complete client transport fixtures accepted by the shared YUME 2.0 core.
 *
 * A browser name is added here only when its TLS and HTTP/2 behavior is
 * implemented and validated end to end; changing a User-Agent alone is not a
 * transport profile. UI and native-session validation consume this registry so
 * Firefox or another browser can be added later without scattered conditionals.
 */
object TransportProfileRegistry {
    data class Profile(val id: String, val displayName: String)

    val supported: List<Profile> = listOf(
        Profile(id = "chrome", displayName = "Chrome"),
    )

    val ids: List<String> = supported.map(Profile::id)

    fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .takeIf(ids::contains)
        ?: ServerProfile.DEFAULT_TLS_STEALTH_PROFILE
}
