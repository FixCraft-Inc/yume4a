package com.fixcraft.yume.ui.overview

import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The overview's status heading never repeats the phase its pill already shows. */
class StatusHeadlineTest {
    private val strings = AppStrings.forLanguage(ResolvedAppLanguage.English)

    @Test
    fun aPlainPhaseHasNoHeadingBesideItsPill() {
        for (phase in VpnPhase.entries.filter { it != VpnPhase.Error }) {
            assertNull(phase.name, statusHeadline(strings, VpnConnectionState(phase = phase)))
        }
    }

    @Test
    fun aFailureIsNamedInTheHeading() {
        val state = VpnConnectionState(phase = VpnPhase.Error, failure = VpnFailure.Refused)
        assertEquals(strings.failureTitle(VpnFailure.Refused), statusHeadline(strings, state))
    }

    @Test
    fun anActiveRunWaitingForANetworkSaysSo() {
        val state = VpnConnectionState(phase = VpnPhase.Reconnecting, waitingForNetwork = true)
        assertEquals(strings.waitingForNetworkTitle, statusHeadline(strings, state))
        // An idle run has no network to wait for.
        assertNull(statusHeadline(strings, VpnConnectionState(phase = VpnPhase.Idle, waitingForNetwork = true)))
    }
}
