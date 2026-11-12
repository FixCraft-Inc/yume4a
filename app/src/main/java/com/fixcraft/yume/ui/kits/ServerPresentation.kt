package com.fixcraft.yume.ui.kits

import androidx.annotation.DrawableRes
import com.fixcraft.yume.consumer.ProviderFlags

/**
 * A server's operator-declared country as the screen shows it. Both fields are null
 * when the operator gave none or the code names no region, and [flag] is also null
 * for a named region without flag art. The location is never guessed from an
 * address, and it says nothing about where traffic leaves.
 */
internal data class ServerPlace(@DrawableRes val flag: Int?, val name: String?)

/**
 * [displayName] turns a region code into words in the app's language. The flag is
 * the provider library's flat art, never an emoji, which some phones draw waving.
 */
internal fun serverPlace(country: String?, displayName: (String) -> String): ServerPlace {
    if (country == null || country.length != 2 || country.any { it !in 'A'..'Z' }) return ServerPlace(null, null)
    val name = displayName(country).takeIf { it.isNotBlank() && it != country } ?: return ServerPlace(null, null)
    return ServerPlace(ProviderFlags.drawable(country), name)
}

/** Servers of one operator-declared country, as indices into the list they came from. */
internal data class CountryGroup(val country: String?, val indices: List<Int>)

/** Groups by country in order of first appearance. Servers without a country form one group. */
internal fun countryGroups(countries: List<String?>): List<CountryGroup> =
    countries.indices.groupBy { countries[it] }.map { (country, indices) -> CountryGroup(country, indices) }

/** Origins are always HTTPS, so the host says everything a sentence needs. */
internal fun originHost(origin: String): String = origin.removePrefix("https://")
