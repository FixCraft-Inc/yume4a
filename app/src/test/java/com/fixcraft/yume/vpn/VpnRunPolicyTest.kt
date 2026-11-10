package com.fixcraft.yume.vpn

import com.fixcraft.yume.core.YumeStatusCode
import com.fixcraft.yume.vpn.VpnRunPolicy.NetworkFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRunPolicyTest {
    private val wifi = NetworkFacts(validated = true, unmetered = true)
    private val cellular = NetworkFacts(validated = true, unmetered = false)
    private val captivePortal = NetworkFacts(validated = false, unmetered = true)

    @Test
    fun aValidatedUnmeteredNetworkIsChosen() {
        assertNull(VpnRunPolicy.choose(emptyMap<String, NetworkFacts>()))
        assertEquals("cell", VpnRunPolicy.choose(linkedMapOf("cell" to cellular)))
        // Wi-Fi returning beside cellular wins, whichever was known first.
        assertEquals("wifi", VpnRunPolicy.choose(linkedMapOf("cell" to cellular, "wifi" to wifi)))
        assertEquals("wifi", VpnRunPolicy.choose(linkedMapOf("wifi" to wifi, "cell" to cellular)))
        // Validation outranks being unmetered: a portal does not take over.
        assertEquals("cell", VpnRunPolicy.choose(linkedMapOf("portal" to captivePortal, "cell" to cellular)))
        // Between equals the first known stays.
        assertEquals("a", VpnRunPolicy.choose(linkedMapOf("a" to cellular, "b" to cellular)))
    }

    @Test
    fun addressesFollowTheNetworksFamily() {
        val addresses = listOf("2001:db8::1", "192.0.2.1", "2001:db8::2", "192.0.2.2")
        val isIpv4 = { a: String -> !a.contains(':') }
        assertEquals(listOf("192.0.2.1", "192.0.2.2", "2001:db8::1", "2001:db8::2"),
            VpnRunPolicy.order(addresses, networkHasIpv4 = true, isIpv4))
        assertEquals(listOf("2001:db8::1", "2001:db8::2", "192.0.2.1", "192.0.2.2"),
            VpnRunPolicy.order(addresses, networkHasIpv4 = false, isIpv4))
    }

    @Test
    fun anActiveMeteredSessionMovesOnlyToADifferentUnmeteredNetwork() {
        assertTrue(VpnRunPolicy.moveToUnmetered(true, "cell", false, "wifi", true))
        assertFalse("not while the session is down", VpnRunPolicy.moveToUnmetered(false, "cell", false, "wifi", true))
        assertFalse("already unmetered", VpnRunPolicy.moveToUnmetered(true, "wifi", true, "wifi2", true))
        assertFalse("same network", VpnRunPolicy.moveToUnmetered(true, "cell", false, "cell", false))
        assertFalse("preferred is metered", VpnRunPolicy.moveToUnmetered(true, "cell", false, "cell2", false))
        assertFalse("no preferred network", VpnRunPolicy.moveToUnmetered<String>(true, "cell", false, null, false))
        assertFalse("no session network yet", VpnRunPolicy.moveToUnmetered(true, null, false, "wifi", true))
    }

    @Test
    fun theNameIsLookedUpAgainOncePerOutage() {
        fun check(active: Boolean = false, resolvedHere: Boolean = true, checked: Boolean = false,
                  changed: Boolean = false, failed: Long = 0) =
            VpnRunPolicy.recheckAddress(active, resolvedHere, checked, changed, failed, afterFailures = 3)
        assertTrue(check(changed = true))
        assertTrue(check(failed = 3))
        assertFalse(check(failed = 2))
        assertFalse("session up", check(active = true, changed = true))
        assertFalse("literal or connect_address", check(resolvedHere = false, changed = true))
        assertFalse("already checked", check(checked = true, changed = true))
    }

    @Test
    fun refusalsAndBadConfigurationsEndTheRun() {
        assertEquals(VpnFailure.Refused, VpnRunPolicy.failureKind(YumeStatusCode.PERMISSION_DENIED))
        for (status in listOf(YumeStatusCode.INVALID_ARGUMENT, YumeStatusCode.UNSUPPORTED,
                YumeStatusCode.PARSE_ERROR, YumeStatusCode.INCOMPATIBLE)) {
            assertEquals(VpnFailure.Configuration, VpnRunPolicy.failureKind(status))
        }
        for (status in listOf(YumeStatusCode.TIMEOUT, YumeStatusCode.IO_ERROR, YumeStatusCode.CLOSED)) {
            assertEquals(VpnFailure.Unreachable, VpnRunPolicy.failureKind(status))
        }
        assertTrue(VpnRunPolicy.startFailureEndsRun(VpnFailure.Refused, keepTrying = true))
        assertTrue(VpnRunPolicy.startFailureEndsRun(VpnFailure.Configuration, keepTrying = true))
        assertFalse(VpnRunPolicy.startFailureEndsRun(VpnFailure.Unreachable, keepTrying = true))
        assertTrue(VpnRunPolicy.startFailureEndsRun(VpnFailure.Unreachable, keepTrying = false))
    }

    /** A server that refuses the kit's limits after a run started is a kit problem, not the device's. */
    @Test
    fun anIncompatibleStopIsTheKitsConfiguration() {
        assertEquals(VpnFailure.Configuration, VpnRunPolicy.stoppedKind(YumeStatusCode.INCOMPATIBLE))
        for (status in listOf(YumeStatusCode.OK, YumeStatusCode.CLOSED, YumeStatusCode.IO_ERROR)) {
            assertEquals(VpnFailure.Device, VpnRunPolicy.stoppedKind(status))
        }
    }

    @Test
    fun retriesBackOffToTheLongestWait() {
        var wait = 1_000L
        val seen = (1..7).map { wait.also { wait = VpnRunPolicy.nextRetryMs(it, 30_000L) } }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun onlyALockedRestartKeepsItsKitAndAsksForThePassword() {
        assertTrue(VpnRunPolicy.lockedRestart(patient = true, VpnFailure.KitUnavailable, unlocked = false))
        assertFalse("a user's connect shows its error", VpnRunPolicy.lockedRestart(false, VpnFailure.KitUnavailable, false))
        assertFalse("unlocked meanwhile", VpnRunPolicy.lockedRestart(true, VpnFailure.KitUnavailable, true))
        assertFalse("another failure", VpnRunPolicy.lockedRestart(true, VpnFailure.Unreachable, false))
    }

    @Test
    fun ipv6ResolversNeedTheTunnelToCarryIpv6() {
        val defaults = listOf("1.1.1.1", "9.9.9.9")
        assertEquals(listOf("1.1.1.1"), VpnRunPolicy.usableDnsServers(listOf("1.1.1.1", "2606:4700:4700::1111"), false, defaults))
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"),
            VpnRunPolicy.usableDnsServers(listOf("1.1.1.1", "2606:4700:4700::1111"), true, defaults))
        assertEquals(defaults, VpnRunPolicy.usableDnsServers(listOf("2606:4700:4700::1111"), false, defaults))
    }
}
