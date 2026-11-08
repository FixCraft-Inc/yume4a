package com.fixcraft.yume.ui.kits

import com.fixcraft.yume.consumer.ServiceEntry

/**
 * A server's operator-declared country as the screen shows it. Both fields are null
 * when the operator gave none or the code names no region. The location is never
 * guessed from an address, and it says nothing about where traffic leaves.
 */
internal data class ServerPlace(val flag: String?, val name: String?)

/** [displayName] turns a region code into words in the app's language. */
internal fun serverPlace(country: String?, displayName: (String) -> String): ServerPlace {
    if (country == null || country.length != 2 || country.any { it !in 'A'..'Z' }) return ServerPlace(null, null)
    val name = displayName(country).takeIf { it.isNotBlank() && it != country } ?: return ServerPlace(null, null)
    return ServerPlace(country.map { String(Character.toChars(REGIONAL_INDICATOR_A + (it - 'A'))) }.joinToString(""), name)
}

/** Android draws two of these letters as the region's flag. */
private const val REGIONAL_INDICATOR_A = 0x1F1E6

/**
 * What the installed server's lease allows, for the service row only. The shared
 * library still checks every lease, renewal and clock itself.
 */
internal enum class GrantAccess {
    /** Usable, and too early to renew. */
    Active,

    /** Usable, and choosing the server again renews it. */
    Renewable,

    /** Expired or suspended. Choosing the server again requests a new lease. */
    Ended,
}

/** Null when no server is installed. [nowSeconds] is UTC. */
internal fun grantAccess(entry: ServiceEntry, nowSeconds: Long): GrantAccess? {
    if (entry.selection == null) return null
    val expiresAt = entry.expiresAt
    val renewAfter = entry.renewAfter
    return when {
        entry.suspended || (expiresAt != null && nowSeconds >= expiresAt) -> GrantAccess.Ended
        renewAfter != null && nowSeconds >= renewAfter -> GrantAccess.Renewable
        else -> GrantAccess.Active
    }
}

/** Origins are always HTTPS, so the host says everything a sentence needs. */
internal fun originHost(origin: String): String = origin.removePrefix("https://")
