package com.fixcraft.yume.consumer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.i18n.MessageId
import com.fixcraft.yume.i18n.ProvideAppStrings
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.ui.kits.ProviderPanel
import com.fixcraft.yume.ui.kits.ServiceActions
import com.fixcraft.yume.ui.theme.YumeVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered presentation with synthetic entries and callbacks. No provider,
 * browser, vault, native kit or transport validation runs in these tests.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPanelTest {
    @get:Rule val compose = createComposeRule()

    private class Actions {
        val added = mutableListOf<Pair<String, String?>>()
        val authorized = mutableListOf<String>()
        val catalogs = mutableListOf<String>()
        val installed = mutableListOf<List<Any>>()
        val selected = mutableListOf<ServiceSelection>()
        val cancelled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, Int>>()
        var reloads = 0
        val callbacks = ServiceActions(
            add = { provider, issuer -> added += provider to issuer },
            authorize = { authorized += it },
            catalog = { catalogs += it },
            install = { provider, catalog, service, server ->
                installed += listOf(provider, catalog, service, server)
            },
            select = { selected += it },
            cancel = { cancelled += it },
            remove = { removed += it },
            code = { id, code ->
                try {
                    assertTrue(code.all { it == '7' })
                    submitted += id to code.size
                } finally { code.fill('\u0000') }
            },
            reload = { reloads++ },
        )
    }

    private val actions = Actions()
    private val entry = ServiceEntry(PROVIDER, "Synthetic operator", ISSUER, null, null, false)
    private lateinit var state: MutableState<ServiceUiState>

    private fun render(initial: ServiceUiState = ServiceUiState(loaded = true), advanced: Boolean = false, language: AppLanguage = AppLanguage.Maintained.English, fontScale: Float? = null): StateRestorationTester {
        state = mutableStateOf(initial)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                ProvideAppStrings(context, language) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    YumeVPNTheme(darkTheme = false) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            ProviderPanel(state.value, actions.callbacks, advanced)
                        }
                    }
                    }
                }
            }
        }
    }

    @Test
    fun addingAServiceRequiresExplicitProviderAndIssuerConsent() {
        render()
        compose.onNodeWithText(MessageId.PROVIDER_ADD_SERVICE.source).performClick()
        compose.onNodeWithText(MessageId.ADD.source).assertIsNotEnabled()
        field(MessageId.PROVIDER_ORIGIN).performTextInput(PROVIDER)
        field(MessageId.PROVIDER_ISSUER).performTextInput(ISSUER)
        compose.onNodeWithText(MessageId.ADD.source).assertIsNotEnabled()
        compose.onNodeWithText(MessageId.PROVIDER_CONSENT.source).performClick()
        compose.onNodeWithText(MessageId.ADD.source).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER to ISSUER), actions.added) }
    }

    @Test
    fun cancellingAddDiscardsTheDraftAndItsConsent() {
        render()
        compose.onNodeWithText(MessageId.PROVIDER_ADD_SERVICE.source).performClick()
        field(MessageId.PROVIDER_ORIGIN).performTextInput(PROVIDER)
        field(MessageId.PROVIDER_ISSUER).performTextInput(ISSUER)
        compose.onNodeWithText(MessageId.PROVIDER_CONSENT.source).performClick()
        compose.onNodeWithText(MessageId.CANCEL.source).performClick()
        compose.runOnIdle { assertTrue(actions.added.isEmpty()) }
        compose.onNodeWithText(MessageId.PROVIDER_ADD_SERVICE.source).performClick()
        compose.onNodeWithText(MessageId.ADD.source).assertIsNotEnabled()
        compose.onNodeWithText(MessageId.PROVIDER_CONSENT.source).assertIsOff()
        compose.onNodeWithText(PROVIDER).assertDoesNotExist()
        compose.onNodeWithText(ISSUER).assertDoesNotExist()
    }

    @Test
    fun anUnregisteredBuildExplainsWhyBrowserAuthorizationIsUnavailable() {
        render(ServiceUiState(entries = listOf(entry), loaded = true, error = "unsupported"))
        compose.onNodeWithText(MessageId.PROVIDER_SIGN_IN.source).performScrollTo().assertExists()
        // One explanation belongs to the entry and the typed refusal may repeat it.
        compose.onAllNodesWithText(MessageId.PROVIDER_REGISTRATION.source)[0].performScrollTo().assertExists()
        compose.runOnIdle { assertTrue(actions.authorized.isEmpty()) }
    }

    @Test
    fun aRegisteredBuildOffersTheChosenIssuerAndCancelsItsPendingAttempt() {
        render(ServiceUiState(entries = listOf(entry), loaded = true,
            browserRegistered = true, authorizing = PROVIDER))
        compose.onNodeWithText(ISSUER).performScrollTo().assertExists()
        compose.onNodeWithText(MessageId.PROVIDER_REGISTRATION.source).assertDoesNotExist()
        compose.onNodeWithText(MessageId.PROVIDER_SIGN_IN.source).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), actions.authorized) }
        compose.onNodeWithText(MessageId.PROVIDER_CANCEL_SIGN_IN.source).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), actions.cancelled) }
    }

    @Test
    fun busyProvisioningKeepsCancellationReachable() {
        render(ServiceUiState(entries = listOf(entry), loaded = true, busy = setOf(PROVIDER)))
        compose.onNodeWithText(MessageId.PROVIDER_SERVERS.source).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(MessageId.CANCEL.source).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), actions.cancelled) }
    }

    @Test
    fun anEmptyCatalogIsVisibleInSimpleMode() = checkEmptyCatalog(advanced = false)

    @Test
    fun anEmptyCatalogIsVisibleInAdvancedMode() = checkEmptyCatalog(advanced = true)

    private fun checkEmptyCatalog(advanced: Boolean) {
        render(ServiceUiState(entries = listOf(entry.copy(issuer = null)), loaded = true,
            catalogs = mapOf(PROVIDER to emptyCatalog())), advanced)
        compose.onNodeWithText(MessageId.PROVIDER_EMPTY.source).performScrollTo().assertExists()
        compose.onNodeWithText(MessageId.PROVIDER_PROVISION.source).assertDoesNotExist()
        compose.onNodeWithText(MessageId.PROVIDER_SELECT.source).assertDoesNotExist()
        compose.onNodeWithText(MessageId.PROVIDER_SERVERS.source).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), actions.catalogs) }
    }

    @Test
    fun typedFailuresRemainActionableInSimpleMode() = checkTypedFailures(advanced = false)

    @Test
    fun typedFailuresRemainActionableInAdvancedMode() = checkTypedFailures(advanced = true)

    private fun checkTypedFailures(advanced: Boolean) {
        render(ServiceUiState(loaded = true, browserRegistered = true), advanced)
        val expected = mapOf(
            "stale" to MessageId.PROVIDER_ERROR_STALE,
            "unauthorized" to MessageId.PROVIDER_ERROR_UNAUTHORIZED,
            "storage" to MessageId.PROVIDER_ERROR_STORAGE,
            "binding" to MessageId.PROVIDER_ERROR_BINDING,
            "revoked" to MessageId.PROVIDER_SUSPENDED,
            "malformed" to MessageId.PROVIDER_ERROR_GENERIC,
            "unavailable" to MessageId.PROVIDER_ERROR_GENERIC,
        )
        expected.forEach { (error, message) ->
            compose.runOnIdle { state.value = state.value.copy(error = error) }
            compose.onNodeWithText(message.source).performScrollTo().assertExists()
            compose.onNodeWithText(MessageId.PROVIDER_ADD_SERVICE.source).performScrollTo().assertIsEnabled()
        }
    }

    @Test
    fun removingAServiceRequiresConfirmationAndAllowsCancellation() {
        render(ServiceUiState(entries = listOf(entry.copy(issuer = null)), loaded = true))
        compose.onNodeWithText(MessageId.PROVIDER_REMOVE.source).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(actions.removed.isEmpty()) }
        compose.onNodeWithText(MessageId.CANCEL.source).performClick()
        compose.runOnIdle { assertTrue(actions.removed.isEmpty()) }
        compose.onNodeWithText(MessageId.PROVIDER_REMOVE.source).performScrollTo().performClick()
        compose.onNode(hasText(MessageId.PROVIDER_REMOVE.source) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), actions.removed) }
    }

    @Test
    fun theOpeningCodePromptCanBeCancelledWithoutSubmitting() {
        render(ServiceUiState(loaded = true, codePrompt = prompt()))
        field(MessageId.CODE).performTextInput(SYNTHETIC_CODE)
        compose.onNodeWithText(MessageId.SAVE.source).assertIsEnabled()
        compose.onNodeWithText(MessageId.CANCEL.source).performClick()
        compose.runOnIdle {
            assertEquals(listOf(PROVIDER), actions.cancelled)
            assertTrue(actions.submitted.isEmpty())
            state.value = state.value.copy(codePrompt = null)
        }
        compose.onNodeWithText(MessageId.PROVIDER_AWAIT_CODE.source).assertDoesNotExist()
    }

    @Test
    fun submittingTheCodeTransfersItOnlyToTheCurrentPromptAndClearsTheField() {
        render(ServiceUiState(loaded = true, codePrompt = prompt()))
        compose.onNodeWithText(MessageId.SAVE.source).assertIsNotEnabled()
        field(MessageId.CODE).performTextInput(SYNTHETIC_CODE)
        compose.onNodeWithText(MessageId.SAVE.source).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-prompt" to 25), actions.submitted) }
        compose.onNodeWithText(MessageId.SAVE.source).assertIsNotEnabled()
    }

    @Test
    fun openingCodeDraftIsExcludedFromComposeSavedState() {
        val restoration = render(ServiceUiState(loaded = true, codePrompt = prompt()))
        field(MessageId.CODE).performTextInput(SYNTHETIC_CODE)
        compose.onNodeWithText(MessageId.SAVE.source).assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(MessageId.PROVIDER_AWAIT_CODE.source).assertExists()
        compose.onNodeWithText(MessageId.SAVE.source).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.submitted.isEmpty()) }
    }

    @Test
    fun aReplacementPromptDoesNotReuseThePreviousCodeDraft() {
        render(ServiceUiState(loaded = true, codePrompt = prompt()))
        field(MessageId.CODE).performTextInput(SYNTHETIC_CODE)
        compose.onNodeWithText(MessageId.SAVE.source).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(codePrompt = prompt().copy(id = "replacement-prompt")) }
        compose.onNodeWithText(MessageId.SAVE.source).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.submitted.isEmpty()) }
    }

    @Test
    fun wrappingLabelsStayAboveTheirFieldsAtLargeTextSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = com.fixcraft.yume.i18n.appStrings(context, AppLanguage.Maintained.Ukrainian)
        render(language = AppLanguage.Maintained.Ukrainian, fontScale = 2f)
        compose.onNodeWithText(strings.message(MessageId.PROVIDER_ADD_SERVICE)).performClick()
        for (id in listOf(MessageId.PROVIDER_ORIGIN, MessageId.PROVIDER_ISSUER)) {
            val label = strings.message(id)
            val input = compose.onNode(hasSetTextAction() and (hasText(label) or hasContentDescription(label)))
            input.performScrollTo()
            val caption = compose.onNode(hasText(label) and hasSetTextAction().not(), useUnmergedTree = true)
            assertTrue("The complete label must be above the input",
                caption.getUnclippedBoundsInRoot().bottom <= input.getUnclippedBoundsInRoot().top)
            input.performTextInput(PROVIDER)
            assertTrue("Entering text must not move the label into the input",
                caption.getUnclippedBoundsInRoot().bottom <= input.getUnclippedBoundsInRoot().top)
        }
    }

    private fun field(label: MessageId) = compose.onNode(hasSetTextAction() and
        (hasText(label.source) or hasContentDescription(label.source)))

    private fun emptyCatalog() = ProviderCodec.Catalog(PROVIDER, "synthetic-device",
        "$PROVIDER/provider/v1", 1_800_000_000, 1_800_000_300, emptyList())

    private fun prompt() = CodePrompt("synthetic-prompt", PROVIDER, "synthetic-grant")

    private companion object {
        const val PROVIDER = "https://operator.example"
        const val ISSUER = "https://issuer.example"
        const val SYNTHETIC_CODE = "7777777777777777777777777"
    }
}
