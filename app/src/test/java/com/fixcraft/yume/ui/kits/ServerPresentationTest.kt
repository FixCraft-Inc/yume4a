package com.fixcraft.yume.ui.kits

import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun entry(selection: ServiceSelection?, renewAfter: Long? = 200, expiresAt: Long? = 300, suspended: Boolean = false) =
        ServiceEntry("https://api.example.org", "Example", null, selection, expiresAt, suspended, renewAfter = renewAfter)

    private val selection = ServiceSelection("https://api.example.org", "00000000-0000-4000-8000-000000000001", "d", "s", "e", "g",
        "a".repeat(64), "b".repeat(64), "jp1.example.org", 443, "JP1", "vpn")

    @Test fun noInstalledServerHasNoLease() = assertNull(grantAccess(entry(null), 100))

    @Test fun aLeaseMovesFromActiveToRenewableToEnded() {
        assertEquals(GrantAccess.Active, grantAccess(entry(selection), 199))
        assertEquals(GrantAccess.Renewable, grantAccess(entry(selection), 200))
        assertEquals(GrantAccess.Ended, grantAccess(entry(selection), 300))
    }

    @Test fun suspensionEndsAccessAtOnce() = assertEquals(GrantAccess.Ended, grantAccess(entry(selection, suspended = true), 100))

    @Test fun originHostDropsOnlyTheScheme() {
        assertEquals("api.example.org", originHost("https://api.example.org"))
        assertEquals("api.example.org:8443", originHost("https://api.example.org:8443"))
    }
}
