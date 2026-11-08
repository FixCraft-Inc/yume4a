package com.fixcraft.yume.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCodecTest {
    private val now = 1_800_000_000L
    private val context = ProviderCodec.Context(
        "https://alpha.example", "alpha-device-1", "vpn", "synthetic-public-client",
        "https://client.example/oauth/callback", "https://login.alpha.example",
    )
    private val auth = mapOf(
        "issuer" to "https://login.alpha.example",
        "authorization_endpoint" to "https://login.alpha.example/authorize",
        "token_endpoint" to "https://login.alpha.example/token",
        "revocation_endpoint" to "https://login.alpha.example/revoke",
        "client_id" to "synthetic-public-client",
        "redirect_uri" to "https://client.example/oauth/callback",
    )
    private val descriptor = mapOf(
        "v" to 1L, "provider" to context.provider, "label" to "Synthetic alpha",
        "expires_at" to now + 3600,
        "required" to listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256"),
        "roles" to listOf("vpn"), "auth" to auth,
    )
    private val server = mapOf(
        "id" to "alpha-edge", "label" to "Synthetic edge", "host" to "edge.alpha.example",
        "port" to 443L, "server_key_sha256" to "a".repeat(64),
    )
    private val service = mapOf(
        "id" to "access", "label" to "Synthetic access", "role" to "vpn",
        "required" to listOf("sealed-kit-1"), "servers" to listOf(server),
    )
    private val catalog = mapOf(
        "v" to 1L, "provider" to context.provider, "device_id" to context.deviceId,
        "audience" to context.audience, "issued_at" to now, "expires_at" to now + 300,
        "services" to listOf(service),
    )
    private val request = ProviderCodec.GrantRequest(context.provider, context.deviceId,
        "alpha-request-1", "access", "alpha-edge", "vpn", null)
    private val grant = request.fields() + mapOf(
        "grant_id" to "alpha-grant-1", "generation" to 1L, "issued_at" to now,
        "refresh_after" to now + 120, "expires_at" to now + 600,
        "client_identity" to "1".repeat(64), "kit_sha256" to "3".repeat(64), "kit_size" to 1068L,
    )
    private val tokens = mapOf(
        "token_type" to "DPoP", "access_token" to "SYNTHETIC-ACCESS", "expires_in" to 60L,
        "refresh_token" to "SYNTHETIC-REFRESH", "refresh_expires_in" to 86400L,
        "scope" to "provider.enroll",
    )

    private fun bytes(value: Any?): ByteArray = ProviderCodec.encode(value, ProviderCodec.METADATA_LIMIT)
    private fun description() = ProviderCodec.descriptor(bytes(descriptor), context, now)
    private fun listing() = ProviderCodec.catalog(bytes(catalog), description(), context, now)
    private fun refusal(code: String, action: () -> Unit) {
        val failure = assertThrows(ProviderCodec.Refusal::class.java, action)
        assertEquals(code, failure.code)
        assertEquals(code, failure.message)
    }

    @Test fun syntheticOperatorsStayIndependent() {
        val d = description()
        val c = listing()
        assertEquals("Synthetic alpha", d.label)
        assertEquals("edge.alpha.example", ProviderCodec.request(request, c, context, now).host)
        assertEquals("3".repeat(64), ProviderCodec.grant(bytes(grant), request, null, now).kitSha256)
        val beta = ProviderCodec.Context("https://beta.example", "beta-device-1", "vpn")
        val b = descriptor + mapOf("provider" to beta.provider, "auth" to null,
            "required" to listOf("sealed-kit-1", "device-grants-1", "anonymous-device-1"))
        assertEquals(null, ProviderCodec.descriptor(bytes(b), beta, now).auth)
        refusal("binding") { ProviderCodec.catalog(bytes(catalog), d, beta, now) }
    }

    @Test fun originsUseOnlyTheExactCanonicalSpelling() {
        assertEquals("https://alpha.example:8443", ProviderCodec.origin("https://alpha.example:8443"))
        for (origin in listOf("http://alpha.example", "https://Alpha.example", "https://alpha.example/",
            "https://alpha.example:443", "https://alpha.example:0443", "https://alpha.example:65536",
            "https://a@alpha.example", "https://alpha.example?x=1", "https://alpha.example#fragment",
            "https://127.0.0.1", "https://[::1]", "https://alpha.example.", "https://alpha%2eexample")) {
            assertThrows(origin, ProviderCodec.Refusal::class.java) { ProviderCodec.origin(origin) }
        }
    }

    @Test fun endpointsNeverEscapeThePinnedIssuer() {
        for (endpoint in listOf("https://other.example/token", "https://login.alpha.example/token?x=1",
            "https://login.alpha.example/../token", "https://login.alpha.example/%74oken",
            "https://login.alpha.example/token#fragment", "https://login.alpha.example/token\\part")) {
            refusal("binding") { ProviderCodec.endpoint(endpoint, "https://login.alpha.example") }
        }
    }

    @Test fun issuerClientCallbackAndProviderAreAllBound() {
        for (changed in listOf(
            descriptor + ("provider" to "https://beta.example"),
            descriptor + ("auth" to auth + ("issuer" to "https://other.example")),
            descriptor + ("auth" to auth + ("client_id" to "other-client")),
            descriptor + ("auth" to auth + ("redirect_uri" to "https://other.example/oauth/callback")),
            descriptor + ("auth" to auth + ("token_endpoint" to "https://other.example/token")),
        )) refusal("binding") { ProviderCodec.descriptor(bytes(changed), context, now) }
    }

    @Test fun unknownRequirementsAndOidcAreUnsupported() {
        for (required in listOf(
            listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256", "oidc"),
            listOf("sealed-kit-1", "device-grants-1"),
            listOf("sealed-kit-1", "browser-pkce-s256"),
            listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256", "anonymous-device-1"),
            listOf("sealed-kit-1", "device-grants-1", "anonymous-device-1", "device-bound-enrollment-1"),
        )) refusal("unsupported") {
            ProviderCodec.descriptor(bytes(descriptor + ("required" to required)), context, now)
        }
        refusal("unsupported") { ProviderCodec.descriptor(bytes(descriptor), context.copy(role = "relay"), now) }
    }

    @Test fun duplicateAndUnknownObjectKeysAreRefused() {
        refusal("malformed") { ProviderCodec.decode("{\"v\":1,\"v\":1}".toByteArray()) }
        refusal("malformed") { ProviderCodec.decode("{\"v\":1,\"\\u0076\":1}".toByteArray()) }
        refusal("malformed") { ProviderCodec.descriptor(bytes(descriptor + ("extra" to 1L)), context, now) }
        refusal("malformed") { ProviderCodec.descriptor(bytes(descriptor - "label"), context, now) }
    }

    @Test fun byteAndDepthBoundsPrecedeRecursiveDecoding() {
        refusal("malformed") { ProviderCodec.decode(ByteArray(ProviderCodec.SMALL_LIMIT + 1) { ' '.code.toByte() }) }
        refusal("malformed") { ProviderCodec.decode(ByteArray(0)) }
        var expected: Any = 0L
        repeat(8) { expected = listOf(expected) }
        assertEquals(expected, ProviderCodec.decode("[[[[[[[[0]]]]]]]]".toByteArray()))
        refusal("malformed") { ProviderCodec.decode("[[[[[[[[[0]]]]]]]]]".toByteArray()) }
        refusal("malformed") { ProviderCodec.decode(("[".repeat(10_000)).toByteArray()) }
    }

    @Test fun onlyStrictUtf8WithoutBomOrSurrogateErrorsIsAccepted() {
        for (raw in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            "\uFEFF{}".toByteArray(), "\"\\uD800\"".toByteArray(),
            "\"\\uDC00\"".toByteArray(), "\"\\uD800x\"".toByteArray())) {
            refusal("malformed") { ProviderCodec.decode(raw) }
        }
        assertEquals("\uD83D\uDE00", ProviderCodec.decode("\"\\uD83D\\uDE00\"".toByteArray()))
    }

    @Test fun controlCharactersAndOversizeStringsAreRefused() {
        for (raw in listOf("\"\\n\"", "\"\\u000a\"", "\"\\u007f\"", "\"line\nline\"",
            "\"${"a".repeat(4097)}\"", "\"${"é".repeat(2049)}\"", "\"\"")) {
            refusal("malformed") { ProviderCodec.decode(raw.toByteArray()) }
        }
        assertEquals("é".repeat(2048), ProviderCodec.decode("\"${"é".repeat(2048)}\"".toByteArray()))
    }

    @Test fun numbersAreNonnegativeSafeIntegersAndNeverBooleans() {
        for (raw in listOf("true", "false", "1.0", "1e0", "-1", "-0", "01", "NaN", "Infinity",
            "9007199254740992", "999999999999999999999999999999999")) {
            refusal("malformed") { ProviderCodec.decode(raw.toByteArray()) }
        }
        assertEquals(9_007_199_254_740_991L, ProviderCodec.decode("9007199254740991".toByteArray()))
        refusal("malformed") {
            ProviderCodec.descriptor("""{"v":true,"provider":"https://alpha.example"}""".toByteArray(), context, now)
        }
    }

    @Test fun arraysAreBoundedAndDistinct() {
        refusal("malformed") { ProviderCodec.decode("[1,1]".toByteArray()) }
        refusal("malformed") { ProviderCodec.decode("[{\"a\":1},{\"a\":1}]".toByteArray()) }
        refusal("malformed") { ProviderCodec.decode((0..64).joinToString(",", "[", "]").toByteArray()) }
        assertEquals(64, (ProviderCodec.decode((0..63).joinToString(",", "[", "]").toByteArray()) as List<*>).size)
    }

    @Test fun malformedJsonHasNoLenientFallback() {
        for (raw in listOf("{}{}", "[1,]", "{\"a\":1,}", "{a:1}", "{'a':1}", "/*x*/{}", "[", "null null")) {
            refusal("malformed") { ProviderCodec.decode(raw.toByteArray()) }
        }
    }

    @Test fun descriptorAndCatalogExpiryHaveNoGrace() {
        for (expiry in listOf(now, now - 1, now + 3601)) refusal("stale") {
            ProviderCodec.descriptor(bytes(descriptor + ("expires_at" to expiry)), context, now)
        }
        for (changed in listOf(catalog + ("issued_at" to now + 1), catalog + ("expires_at" to now),
            catalog + ("expires_at" to now + 301))) refusal("stale") {
            ProviderCodec.catalog(bytes(changed), description(), context, now)
        }
        refusal("stale") { ProviderCodec.request(request, listing(), context, now + 300) }
    }

    @Test fun catalogDeviceAudienceAndSelectionAreBound() {
        for (changed in listOf(catalog + ("device_id" to "beta-device-1"),
            catalog + ("audience" to "https://beta.example/provider/v1"),
            catalog + ("provider" to "https://beta.example"))) refusal("binding") {
            ProviderCodec.catalog(bytes(changed), description(), context, now)
        }
        refusal("not_entitled") { ProviderCodec.request(request.copy(serverId = "other-edge"), listing(), context, now) }
        refusal("not_entitled") { ProviderCodec.request(request.copy(serviceId = "other-service"), listing(), context, now) }
    }

    @Test fun repeatedServiceAndServerIdentifiersAreRejected() {
        val repeatedServers = service + ("servers" to listOf(server, server + ("label" to "Different")))
        for (changed in listOf(
            catalog + ("services" to listOf(service, service + ("label" to "Different"))),
            catalog + ("services" to listOf(repeatedServers)),
        )) refusal("malformed") { ProviderCodec.catalog(bytes(changed), description(), context, now) }
    }

    @Test fun grantEchoCannotChangeAnyRequestBinding() {
        for ((key, value) in mapOf("provider" to "https://beta.example", "device_id" to "beta-device-1",
            "audience" to "https://beta.example/provider/v1", "request_id" to "other-request",
            "service_id" to "other-service", "server_id" to "other-edge", "role" to "relay",
            "previous_grant" to "unexpected-grant")) {
            refusal("binding") { ProviderCodec.grant(bytes(grant + (key to value)), request, null, now) }
        }
    }

    @Test fun nativeKitSizeAndHashMetadataAreStrict() {
        for (size in listOf(1067L, 1069L, 1049649L)) refusal("malformed") {
            ProviderCodec.grant(bytes(grant + ("kit_size" to size)), request, null, now)
        }
        refusal("malformed") { ProviderCodec.grant(bytes(grant + ("kit_sha256" to "A".repeat(64))), request, null, now) }
        refusal("malformed") { ProviderCodec.grant(bytes(grant + ("client_identity" to "1".repeat(63))), request, null, now) }
        for (expiry in listOf(now, now + 3601)) refusal("stale") {
            ProviderCodec.grant(bytes(grant + ("expires_at" to expiry)), request, null, now)
        }
    }

    @Test fun refreshRequiresNewIdentityGrantAndExactNextGeneration() {
        val old = ProviderCodec.grant(bytes(grant), request, null, now)
        val nextRequest = request.copy(requestId = "alpha-request-2", previousGrant = old.grantId)
        val next = grant + nextRequest.fields() + mapOf(
            "grant_id" to "alpha-grant-2", "generation" to 2L, "issued_at" to now,
            "refresh_after" to now + 240, "expires_at" to now + 720, "client_identity" to "2".repeat(64),
        )
        assertEquals(2L, ProviderCodec.grant(bytes(next), nextRequest, old, now + 120).generation)
        refusal("state") { ProviderCodec.grant(bytes(next), nextRequest, old, now + 119) }
        for (changed in listOf(next + ("generation" to 3L), next + ("grant_id" to old.grantId),
            next + ("client_identity" to old.clientIdentity))) refusal("state") {
            ProviderCodec.grant(bytes(changed), nextRequest, old, now + 120)
        }
    }

    @Test fun accountTokensAreDistinctBoundedRotatingAndNotPrinted() {
        val pair = ProviderCodec.tokens(bytes(tokens))
        assertEquals(60L, pair.expiresIn)
        assertFalse(pair.toString().contains(pair.accessToken))
        assertFalse(pair.toString().contains(pair.refreshToken))
        refusal("binding") { ProviderCodec.tokens(bytes(tokens + ("refresh_token" to pair.accessToken))) }
        refusal("revoked") { ProviderCodec.tokens(bytes(tokens), pair) }
        for (changed in listOf(tokens + ("expires_in" to 59L), tokens + ("expires_in" to 901L),
            tokens + ("refresh_expires_in" to 60L), tokens + ("refresh_expires_in" to 2592001L),
            tokens + ("access_token" to "not visible ascii é"))) refusal("malformed") {
            ProviderCodec.tokens(bytes(changed))
        }
        refusal("unsupported") { ProviderCodec.tokens(bytes(tokens + ("scope" to "openid"))) }
        refusal("unsupported") { ProviderCodec.tokens(bytes(tokens + ("token_type" to "Bearer"))) }
        refusal("malformed") { ProviderCodec.tokens(bytes(tokens + ("id_token" to "unexpected"))) }
    }

    @Test fun errorMessagesCannotEchoPeerTextOrInventRetryPolicy() {
        assertEquals("rate_limited", ProviderCodec.error(bytes(mapOf("v" to 1L, "error" to "rate_limited", "retry_after" to 30L))))
        refusal("malformed") { ProviderCodec.error(bytes(mapOf("v" to 1L, "error" to "unauthorized", "retry_after" to 1L))) }
        refusal("malformed") { ProviderCodec.error(bytes(mapOf("v" to 1L, "error" to "SECRET-PEER-TEXT", "retry_after" to 0L))) }
    }

    @Test fun onlyAnIssuersInvalidGrantEndsTheSignIn() {
        assertTrue(ProviderCodec.refusedGrant(bytes(mapOf("error" to "invalid_grant"))))
        assertTrue(ProviderCodec.refusedGrant(bytes(mapOf("error" to "invalid_grant", "error_description" to "revoked"))))
        assertFalse(ProviderCodec.refusedGrant(bytes(mapOf("error" to "invalid_request"))))
        assertFalse(ProviderCodec.refusedGrant(bytes(mapOf("error" to "invalid_grant", "access_token" to "unexpected"))))
        assertFalse(ProviderCodec.refusedGrant(bytes(mapOf("v" to 1L, "error" to "unauthorized", "retry_after" to 0L))))
        assertFalse(ProviderCodec.refusedGrant("not json".toByteArray()))
    }

    @Test fun locatedCatalogsRequireANullableIsoCountryOnEveryServer() {
        val located = descriptor + ("required" to listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256",
            "device-bound-enrollment-1", "server-location-1"))
        val d = ProviderCodec.descriptor(bytes(located), context, now)
        fun list(servers: List<Map<String, Any?>>) = ProviderCodec.catalog(
            bytes(catalog + ("services" to listOf(service + ("servers" to servers)))), d, context, now)
        assertEquals("JP", list(listOf(server + ("country" to "JP"))).services[0].servers[0].country)
        assertEquals(null, list(listOf(server + ("country" to null))).services[0].servers[0].country)
        refusal("malformed") { list(listOf(server)) }
        for (country in listOf("jp", "JPN", "", 1L)) refusal("malformed") { list(listOf(server + ("country" to country))) }
        // Without the capability the closed server object has no country member.
        refusal("malformed") { ProviderCodec.catalog(bytes(catalog + ("services" to listOf(service +
            ("servers" to listOf(server + ("country" to "JP")))))), description(), context, now) }
        assertEquals(null, listing().services[0].servers[0].country)
    }

    @Test fun deliveredOpeningCodeIsBoundToContextAndGrant() {
        val reply = mapOf("v" to 1L, "provider" to context.provider, "device_id" to context.deviceId,
            "audience" to context.audience, "grant_id" to "alpha-grant-1", "opening_code" to "0123456789ABCDEFGHJKMNPQR")
        val code = ProviderCodec.openingCode(bytes(reply), context, "alpha-grant-1")
        assertEquals("0123456789ABCDEFGHJKMNPQR", String(code))
        refusal("binding") { ProviderCodec.openingCode(bytes(reply), context, "alpha-grant-2") }
        refusal("binding") { ProviderCodec.openingCode(bytes(reply + ("provider" to "https://beta.example")), context, "alpha-grant-1") }
        refusal("binding") { ProviderCodec.openingCode(bytes(reply + ("device_id" to "other-device")), context, "alpha-grant-1") }
        refusal("binding") { ProviderCodec.openingCode(bytes(reply + ("audience" to "https://beta.example/provider/v1")), context, "alpha-grant-1") }
        for (bad in listOf("0123456789ABCDEFGHJKMNPQ", "0123456789ABCDEFGHJKMNPQRS", "0123456789abcdefghjkmnpqr",
            "01234-56789-ABCDE-FGHJK-MNPQR", "0123456789ABCDEFGHIJKLMNO")) refusal("malformed") {
            ProviderCodec.openingCode(bytes(reply + ("opening_code" to bad)), context, "alpha-grant-1")
        }
        refusal("malformed") { ProviderCodec.openingCode(bytes(reply + ("extra" to 1L)), context, "alpha-grant-1") }
    }
}
