package com.fixcraft.yume.ui.kits

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPresentationTest {
    private val names = mapOf("JP" to "Japan", "US" to "United States", "ZZ" to "ZZ", "QM" to "")

    private fun place(code: String?) = serverPlace(code) { names[it] ?: it }

    @Test fun aKnownCountryHasItsFlagAndName() {
        assertEquals(ServerPlace("🇯🇵", "Japan"), place("JP"))
        assertEquals(ServerPlace("🇺🇸", "United States"), place("US"))
    }

    @Test fun anAbsentCountryStaysUnknown() = assertEquals(ServerPlace(null, null), place(null))

    /** A region the platform cannot name draws no flag, since a flag must never be the only label. */
    @Test fun aRegionWithoutANameStaysUnknown() {
        assertEquals(ServerPlace(null, null), place("ZZ"))
        assertEquals(ServerPlace(null, null), place("QM"))
    }

    @Test fun anythingButTwoCapitalsStaysUnknown() {
        for (code in listOf("", "J", "JPN", "jp", "J1", "🇯🇵")) assertEquals(code, ServerPlace(null, null), place(code))
    }

    @Test fun serversGroupByCountryInTheOrderTheyFirstAppear() {
        assertEquals(listOf(CountryGroup("US", listOf(0, 3)), CountryGroup("JP", listOf(1)), CountryGroup(null, listOf(2, 4))),
            countryGroups(listOf("US", "JP", null, "US", null)))
    }

    @Test fun anEmptyListHasNoGroups() = assertEquals(emptyList<CountryGroup>(), countryGroups(emptyList()))

    @Test fun originHostDropsOnlyTheScheme() {
        assertEquals("api.example.org", originHost("https://api.example.org"))
        assertEquals("api.example.org:8443", originHost("https://api.example.org:8443"))
    }
}
