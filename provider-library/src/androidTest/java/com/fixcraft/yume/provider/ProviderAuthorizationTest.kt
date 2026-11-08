package com.fixcraft.yume.provider

import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.openid.appauth.AuthorizationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Android Uri and real AppAuth requests; a request seam does not verify app-link delivery. */
@RunWith(AndroidJUnit4::class)
class ProviderAuthorizationTest {
    private class Clock : ProviderClock {
        var seconds = 1_800_000_000L
        var elapsedMillis = 10_000L
        var trusted = true
        override fun now(): ProviderClock.Reading? =
            if (trusted) ProviderClock.Reading(seconds, elapsedMillis) else null
    }

    private val registration = ProviderRegistration("com.fixcraft.yume", "synthetic-public-client", CALLBACK)
    private val binding = ProviderCodec.Context(PROVIDER, "alpha-device-1", "vpn",
        registration.clientId, registration.callback, ISSUER)
    private val auth = ProviderCodec.Auth(ISSUER, "$ISSUER/authorize", "$ISSUER/token", "$ISSUER/revoke",
        registration.clientId, registration.callback)

    private fun descriptor(clock: Clock) = ProviderCodec.Descriptor(PROVIDER, "Synthetic alpha",
        clock.seconds + 3600, listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256"), listOf("vpn"), auth)

    private fun begin(owner: ProviderAuthorization, clock: Clock): AuthorizationRequest =
        owner.beginRequest(binding, descriptor(clock), "epoch-one", registration)

    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun callback(request: AuthorizationRequest, issuer: String = ISSUER,
                         redirect: String = CALLBACK, state: String = request.toUri().getQueryParameter("state")!!): String =
        "$redirect?iss=${encoded(issuer)}&state=${encoded(state)}&code=synthetic-single-use-code"

    private fun form(bytes: ByteArray): Map<String, String> = bytes.toString(Charsets.US_ASCII).split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    private fun refusal(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(ProviderCodec.Refusal::class.java, action).code)
    }

    @Test
    fun identifiersRetainEntropyAndStartWithAnAlphanumericCharacter() {
        for (value in listOf(0xfb, 0xff)) {
            val entropy = object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) { bytes.fill(value.toByte()) }
            }
            val id = ProviderAuthorization.randomIdentifier(entropy)
            assertEquals(id, ProviderCodec.identifier(id))
            assertEquals(23, id.length)
            assertTrue(Base64.getUrlDecoder().decode(id.substring(1)).all { it == value.toByte() })
        }
    }

    @Test
    fun appAuthUsesFresh32ByteStateAndVerifierS256AndOnlyTheBoundResource() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        val uri = request.toUri()
        assertEquals("https", uri.scheme)
        assertEquals("login.alpha.example", uri.host)
        assertEquals("/authorize", uri.path)
        assertEquals(setOf("response_type", "client_id", "redirect_uri", "scope", "state",
            "code_challenge", "code_challenge_method", "resource"), uri.queryParameterNames)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals(registration.clientId, uri.getQueryParameter("client_id"))
        assertEquals(CALLBACK, uri.getQueryParameter("redirect_uri"))
        assertEquals("provider.enroll", uri.getQueryParameter("scope"))
        assertEquals("$PROVIDER/provider/v1", uri.getQueryParameter("resource"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        val state = uri.getQueryParameter("state")!!
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(state))
        assertEquals(32, Base64.getUrlDecoder().decode(state).size)
        owner.consume(callback(request)).use { exchange ->
            assertEquals(PROVIDER, exchange.provider)
            assertEquals("epoch-one", exchange.epoch)
            assertEquals("$ISSUER/token", exchange.endpoint)
            val values = form(exchange.body)
            assertEquals(setOf("grant_type", "code", "redirect_uri", "code_verifier", "resource", "client_id"), values.keys)
            assertEquals("authorization_code", values["grant_type"])
            assertEquals(CALLBACK, values["redirect_uri"])
            assertEquals("$PROVIDER/provider/v1", values["resource"])
            assertEquals(registration.clientId, values["client_id"])
            val verifier = values.getValue("code_verifier")
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(verifier))
            assertEquals(32, Base64.getUrlDecoder().decode(verifier).size)
            assertFalse(uri.toString().contains(verifier))
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            assertEquals(challenge, uri.getQueryParameter("code_challenge"))
            assertFalse(exchange.toString().contains(verifier))
        }
        val second = begin(owner, clock).toUri()
        assertNotEquals(state, second.getQueryParameter("state"))
        assertNotEquals(uri.getQueryParameter("code_challenge"), second.getQueryParameter("code_challenge"))
        owner.clear()
    }

    @Test
    fun aConsumedCallbackCannotBeReplayedAndExchangeBuffersAreWiped() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val redirect = callback(begin(owner, clock))
        val exchange = owner.consume(redirect)
        assertTrue(exchange.body.any { it != 0.toByte() })
        exchange.close()
        assertTrue(exchange.body.all { it == 0.toByte() })
        exchange.close()
        refusal("state") { owner.consume(redirect) }
    }

    @Test
    fun forgedStateAndUnsolicitedCallbacksCannotSelectAnAccount() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        refusal("state") { owner.consume(callback(request, state = "forged-state")) }
        refusal("state") { ProviderAuthorization(clock).consume(callback(request)) }
        owner.consume(callback(request)).close()
    }

    @Test
    fun issuerAndCallbackMustMatchExactly() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        for (issuer in listOf("https://login.beta.example", "$ISSUER/", "https://LOGIN.alpha.example")) {
            refusal("binding") { owner.consume(callback(request, issuer = issuer)) }
        }
        for (redirect in listOf("https://other.example/oauth/callback", "$CALLBACK/",
            "https://client.example:443/oauth/callback", "https://CLIENT.example/oauth/callback",
            "https://client.example/oauth/%63allback", "https://client.example/other")) {
            refusal("binding") { owner.consume(callback(request, redirect = redirect)) }
        }
        refusal("binding") { owner.consume(callback(request) + "#fragment") }
        owner.consume(callback(request)).close()
    }

    @Test
    fun duplicateAndExtraResponseParametersCannotOverrideTheBinding() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        val redirect = callback(request)
        for (suffix in listOf("&state=forged", "&%73tate=forged", "&iss=${encoded(ISSUER)}",
            "&code=other", "&id_token=untrusted-identity", "&access_token=untrusted-token", "&extra=value")) {
            refusal("malformed") { owner.consume(redirect + suffix) }
        }
        owner.consume(redirect).close()
    }

    @Test
    fun malformedAndOversizeCallbacksHaveNoTokenExchange() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        val redirect = callback(request)
        for (bad in listOf(redirect + "x".repeat(16_384), redirect.replace("code=synthetic-single-use-code", "code="),
            redirect.replace("code=synthetic-single-use-code", "code=${"x".repeat(513)}"),
            redirect.replace("code=synthetic-single-use-code", "code=%00"),
            redirect.replace("code=synthetic-single-use-code", "code=%C0%AF"),
            redirect.replace("code=synthetic-single-use-code", "code=%GG"), "$redirect\n", "${redirect}é")) {
            refusal("malformed") { owner.consume(bad) }
        }
        owner.consume(redirect).close()
    }

    @Test
    fun cancellationClearsTheAttemptBeforeAnotherCanStart() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val first = begin(owner, clock)
        refusal("state") { begin(owner, clock) }
        owner.cancel(PROVIDER)
        owner.cancel(PROVIDER)
        refusal("state") { owner.consume(callback(first)) }
        val next = begin(owner, clock)
        refusal("state") { owner.consume(callback(first)) }
        owner.consume(callback(next)).close()
    }

    @Test
    fun concurrentProviderAttemptsKeepIndependentStateIssuerAndEpochBindings() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val alpha = begin(owner, clock)
        val betaIssuer = "https://login.beta.example"
        val betaBinding = binding.copy(provider = "https://beta.example", deviceId = "beta-device-1",
            acceptedIssuer = betaIssuer)
        val betaAuth = auth.copy(issuer = betaIssuer, authorizationEndpoint = "$betaIssuer/authorize",
            tokenEndpoint = "$betaIssuer/token", revocationEndpoint = "$betaIssuer/revoke")
        val betaDescriptor = descriptor(clock).copy(provider = betaBinding.provider, auth = betaAuth)
        val beta = owner.beginRequest(betaBinding, betaDescriptor, "epoch-beta", registration)
        assertNotEquals(alpha.toUri().getQueryParameter("state"), beta.toUri().getQueryParameter("state"))
        refusal("binding") { owner.consume(callback(alpha, issuer = betaIssuer)) }
        refusal("binding") { owner.consume(callback(beta, issuer = ISSUER)) }
        owner.cancel(PROVIDER)
        refusal("state") { owner.consume(callback(alpha)) }
        owner.consume(callback(beta, issuer = betaIssuer)).use { exchange ->
            assertEquals("https://beta.example", exchange.provider)
            assertEquals("epoch-beta", exchange.epoch)
            assertEquals("$betaIssuer/token", exchange.endpoint)
            assertEquals("https://beta.example/provider/v1", form(exchange.body)["resource"])
        }
    }

    @Test
    fun clearCancelsEveryProviderAndFreshOwnerHasNoRecoverablePendingSecret() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val first = begin(owner, clock)
        owner.clear()
        refusal("state") { owner.consume(callback(first)) }
        val next = begin(owner, clock)
        // A new owner establishes the restart rule, not an actual killed-process gate.
        refusal("state") { ProviderAuthorization(clock).consume(callback(next)) }
        owner.cancel(PROVIDER)
    }

    @Test
    fun untrustedTimeBlocksAuthorizationAndCallbackExchange() {
        val clock = Clock().apply { trusted = false }
        val owner = ProviderAuthorization(clock)
        refusal("stale") { begin(owner, clock) }
        clock.trusted = true
        val request = begin(owner, clock)
        clock.trusted = false
        refusal("stale") { owner.consume(callback(request)) }
        owner.clear()
    }

    @Test
    fun wallAndMonotonicDeadlinesEachExpireAndInvalidateTheAttempt() {
        for (change in listOf<(Clock) -> Unit>(
            { it.seconds += 300 }, { it.elapsedMillis += 300_000 },
            { it.seconds-- }, { it.elapsedMillis-- },
        )) {
            val clock = Clock()
            val owner = ProviderAuthorization(clock)
            val redirect = callback(begin(owner, clock))
            change(clock)
            refusal("stale") { owner.consume(redirect) }
            refusal("state") { owner.consume(redirect) }
        }
    }

    @Test
    fun expiredAndRolledBackAttemptsArePrunedBeforeReplacementAuthorization() {
        for (change in listOf<(Clock) -> Unit>(
            { it.seconds += 300 }, { it.elapsedMillis += 300_000 },
            { it.seconds-- }, { it.elapsedMillis-- },
        )) {
            val clock = Clock()
            ProviderAuthorization(clock).use { owner ->
                val old = begin(owner, clock)
                change(clock)
                val replacement = begin(owner, clock)
                assertNotEquals(old.toUri().getQueryParameter("state"), replacement.toUri().getQueryParameter("state"))
                refusal("state") { owner.consume(callback(old)) }
                owner.consume(callback(replacement)).close()
            }
        }
    }

    @Test
    fun boundAccessDeniedConsumesOnlyItsAttemptAndReturnsNoPeerText() {
        val clock = Clock()
        ProviderAuthorization(clock).use { owner ->
            val request = begin(owner, clock)
            val denied = callback(request).replace("code=synthetic-single-use-code", "error=access_denied")
            refusal("state") { owner.consume(denied.replace(encoded(request.toUri().getQueryParameter("state")!!), "forged-state")) }
            refusal("binding") { owner.consume(denied.replace(encoded(ISSUER), encoded("https://login.beta.example"))) }
            refusal("binding") { owner.consume(denied.replace(CALLBACK, "https://other.example/oauth/callback")) }
            refusal("malformed") { owner.consume("$denied&error_description=untrusted-peer-text") }
            refusal("malformed") { owner.consume(denied.replace("access_denied", "unsupported_error")) }
            val rejected = assertThrows(ProviderCodec.Refusal::class.java) { owner.consume(denied) }
            assertEquals("unauthorized", rejected.code)
            assertEquals("unauthorized", rejected.message)
            refusal("state") { owner.consume(denied) }
            refusal("state") { owner.consume(callback(request)) }
            owner.consume(callback(begin(owner, clock))).close()
        }
    }

    @Test
    fun consumedExchangeKeepsTheGenerationForAdmissionAfterConcurrentCancellation() {
        val clock = Clock()
        ProviderAuthorization(clock).use { owner ->
            val first = owner.beginRequest(binding, descriptor(clock), "epoch-one", registration, generation = 17)
            owner.consume(callback(first)).use { retired ->
                assertEquals(17L, retired.generation)
                owner.cancel(PROVIDER)
                val next = owner.beginRequest(binding, descriptor(clock), "epoch-one", registration, generation = 18)
                owner.consume(callback(next)).use { current ->
                    assertEquals(18L, current.generation)
                    assertNotEquals(current.generation, retired.generation)
                }
            }
        }
    }

    @Test
    fun closingInvalidatesPendingAttemptsAndPermanentlyRejectsReuse() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        owner.close()
        owner.close()
        refusal("state") { owner.consume(callback(request)) }
        refusal("state") { begin(owner, clock) }
    }

    @Test
    fun theLastValidSecondDoesNotCreateAnExpiryGracePeriod() {
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val redirect = callback(begin(owner, clock))
        clock.seconds += 299
        clock.elapsedMillis += 299_999
        owner.consume(redirect).close()
    }

    @Test
    fun descriptorRegistrationIssuerAndClientCannotBeReboundAtBegin() {
        val clock = Clock()
        for (changed in listOf(
            descriptor(clock).copy(provider = "https://beta.example"),
            descriptor(clock).copy(auth = auth.copy(issuer = "https://login.beta.example")),
            descriptor(clock).copy(auth = auth.copy(clientId = "other-client")),
            descriptor(clock).copy(auth = auth.copy(redirectUri = "https://other.example/oauth/callback")),
            descriptor(clock).copy(expiresAt = clock.seconds),
        )) refusal("binding") {
            ProviderAuthorization(clock).beginRequest(binding, changed, "epoch-one", registration)
        }
    }

    @Test
    fun idTokensAreUnsupportedOnTheAndroidPath() {
        val raw = """{"token_type":"Bearer","access_token":"SYNTHETIC-ACCESS","expires_in":60,"refresh_token":"SYNTHETIC-REFRESH","refresh_expires_in":3600,"scope":"provider.enroll","id_token":"SYNTHETIC-ID"}"""
        refusal("malformed") { ProviderCodec.tokens(raw.toByteArray()) }
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        val request = begin(owner, clock)
        assertEquals(null, request.toUri().getQueryParameter("nonce"))
        assertEquals("provider.enroll", request.toUri().getQueryParameter("scope"))
        owner.clear()
    }

    @Test
    fun productionRefusesTheUnverifiedHarnessSentinelUsingActualOsState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("P3 app-link checks require Android 12 or newer", Build.VERSION.SDK_INT >= 31)
        val state = context.getSystemService(DomainVerificationManager::class.java)
            .getDomainVerificationUserState(context.packageName)
        assertTrue(state?.hostToStateMap?.get(Uri.parse(CALLBACK).host) != DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        val exactApp = registration.copy(applicationId = context.packageName)
        refusal("binding") { ProviderAppLinks.verify(context, exactApp) }
        val clock = Clock()
        val owner = ProviderAuthorization(clock)
        refusal("binding") { owner.begin(binding, descriptor(clock), "epoch-one", exactApp, context) }
        // No pending state was created before OS verification failed.
        begin(owner, clock)
        owner.clear()
    }

    companion object {
        private const val PROVIDER = "https://alpha.example"
        private const val ISSUER = "https://login.alpha.example"
        private const val CALLBACK = "https://client.example/oauth/callback"
    }
}
