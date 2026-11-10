package com.fixcraft.yume.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceRoute
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.i18n.MessageId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Which page the app opens on. A completed sign-in relaunches the app, so this is what the person sees next. */
@RunWith(AndroidJUnit4::class)
class YumeAppStartTest {
    @get:Rule val compose = createComposeRule()

    private val entry = ServiceEntry("https://operator.example", "Synthetic operator", "https://issuer.example", null, null, false, authorized = true)

    private fun show(services: ServiceUiState) = compose.setContent { FixtureApp(MainUiState(services = services)) }

    private fun onConnectPage() = compose.onNodeWithText(MessageId.PROVIDER_SERVICES.source)

    @Test fun aServiceWithoutAServerOpensOnConnect() {
        show(ServiceUiState(entries = listOf(entry), loaded = true, browserRegistered = true))
        onConnectPage().assertExists()
    }

    @Test fun nothingSetUpStaysOnOverview() {
        show(ServiceUiState(loaded = true, manual = true))
        onConnectPage().assertDoesNotExist()
        compose.onNodeWithText(MessageId.NO_SERVER_YET.source).assertExists()
    }

    @Test fun aChosenServerStaysOnOverview() {
        val server = ProviderCodec.Server("jp", "JP1 VPN", "jp1.operator.example", 443, "b".repeat(64), "JP")
        val choice = ProviderClient.Choice("vpn", server)
        val route = ServiceRoute(entry.provider, "00000000-0000-4000-8000-000000000001", "vpn", choice)
        show(ServiceUiState(entries = listOf(entry.copy(choice = choice)), selection = route, loaded = true, browserRegistered = true))
        onConnectPage().assertDoesNotExist()
        compose.onNodeWithText("JP1 VPN").assertExists()
    }

    /** The move happens once. A person who then opens Overview stays there. */
    @Test fun openingOverviewAfterwardsIsNotOverridden() {
        var services by mutableStateOf(ServiceUiState(loaded = true, manual = true))
        compose.setContent { FixtureApp(MainUiState(services = services)) }
        compose.runOnIdle { services = ServiceUiState(entries = listOf(entry), loaded = true, browserRegistered = true) }
        onConnectPage().assertExists()
        compose.onNode(hasContentDescription(MessageId.OVERVIEW.source) and hasClickAction()).performClick()
        compose.waitForIdle()
        onConnectPage().assertDoesNotExist()
    }
}
