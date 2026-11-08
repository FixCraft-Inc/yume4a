package com.fixcraft.yume.provider

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Contract-1 decoding is independent of Android lifecycle, storage and HTTP. */
object ProviderCodec {
    const val SMALL_LIMIT = 16_384
    const val METADATA_LIMIT = 65_536
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private val supported = setOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256", "anonymous-device-1",
        DEVICE_BOUND, SERVER_LOCATION)
    /** Account tokens are DPoP-bound and the opening code is fetched by the bound device. */
    const val DEVICE_BOUND = "device-bound-enrollment-1"
    /** Catalog servers carry an operator-declared hosting country or null. */
    const val SERVER_LOCATION = "server-location-1"
    private val countryPattern = Regex("[A-Z]{2}")
    private val openingCodePattern = Regex("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{25}")
    private val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val hostLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    /** Only stable error codes cross this boundary; peer text is never an exception message. */
    class Refusal(val code: String) : Exception(code)

    data class Context(
        val provider: String,
        val deviceId: String,
        val role: String,
        val clientId: String? = null,
        val redirectUri: String? = null,
        val acceptedIssuer: String? = null,
    ) {
        val audience: String get() = "$provider/provider/v1"
    }

    data class Auth(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val revocationEndpoint: String,
        val clientId: String,
        val redirectUri: String,
    ) {
        fun fields(): Map<String, Any?> = mapOf(
            "issuer" to issuer, "authorization_endpoint" to authorizationEndpoint,
            "token_endpoint" to tokenEndpoint, "revocation_endpoint" to revocationEndpoint,
            "client_id" to clientId, "redirect_uri" to redirectUri,
        )
    }

    data class Descriptor(
        val provider: String, val label: String, val expiresAt: Long,
        val required: List<String>, val roles: List<String>, val auth: Auth?,
    ) {
        fun fields(): Map<String, Any?> = mapOf(
            "v" to 1L, "provider" to provider, "label" to label, "expires_at" to expiresAt,
            "required" to required, "roles" to roles, "auth" to auth?.fields(),
        )
    }

    /** [country] is an operator-declared ISO 3166-1 alpha-2 code, not an egress or location proof. */
    data class Server(val id: String, val label: String, val host: String, val port: Int, val serverKeySha256: String,
                      val country: String? = null)
    data class Service(val id: String, val label: String, val role: String, val required: List<String>, val servers: List<Server>)
    data class Catalog(
        val provider: String, val deviceId: String, val audience: String,
        val issuedAt: Long, val expiresAt: Long, val services: List<Service>,
    )

    data class GrantRequest(
        val provider: String, val deviceId: String, val requestId: String,
        val serviceId: String, val serverId: String, val role: String, val previousGrant: String?,
    ) {
        val audience: String get() = "$provider/provider/v1"
        fun fields(): Map<String, Any?> = mapOf(
            "v" to 1L, "provider" to provider, "device_id" to deviceId, "audience" to audience,
            "request_id" to requestId, "service_id" to serviceId, "server_id" to serverId,
            "role" to role, "previous_grant" to previousGrant,
        )
    }

    data class Grant(
        val request: GrantRequest, val grantId: String, val generation: Long,
        val issuedAt: Long, val refreshAfter: Long, val expiresAt: Long,
        val clientIdentity: String, val kitSha256: String, val kitSize: Int,
    ) {
        fun fields(): Map<String, Any?> = request.fields() + mapOf(
            "grant_id" to grantId, "generation" to generation, "issued_at" to issuedAt,
            "refresh_after" to refreshAfter, "expires_at" to expiresAt,
            "client_identity" to clientIdentity, "kit_sha256" to kitSha256, "kit_size" to kitSize.toLong(),
        )
    }

    class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long, val refreshExpiresIn: Long) {
        override fun toString(): String = "Tokens([redacted])"
    }

    private fun check(condition: Boolean, code: String = "malformed") {
        if (!condition) throw Refusal(code)
    }

    private fun integer(value: Any?, low: Long = 0, high: Long = MAX_INTEGER): Long {
        check(value is Long && value in low..high)
        return value as Long
    }

    private fun text(value: Any?, limit: Int = 4096): String {
        check(value is String)
        val result = value as String
        check(result.isNotEmpty() && result.toByteArray(Charsets.UTF_8).size <= limit)
        var index = 0
        while (index < result.length) {
            val ch = result[index++]
            check(ch.code >= 32 && ch.code != 127)
            if (ch.isHighSurrogate()) {
                check(index < result.length && result[index++].isLowSurrogate())
            } else {
                check(!ch.isLowSurrogate())
            }
        }
        return result
    }

    fun identifier(value: String): String {
        check(identifierPattern.matches(value))
        return value
    }

    private fun id(value: Any?): String = identifier(text(value, 64))
    private fun digest(value: Any?): String = text(value, 64).also { check(digestPattern.matches(it)) }

    fun dnsHost(value: String): String {
        text(value, 253)
        check('.' in value && !Regex("[0-9.]+").matches(value))
        check(value.split('.').all { hostLabel.matches(it) })
        return value
    }

    fun origin(value: String): String {
        text(value, 512)
        val match = Regex("https://([a-z0-9.-]+)(?::([1-9][0-9]{0,4}))?").matchEntire(value)
            ?: throw Refusal("binding")
        dnsHost(match.groupValues[1])
        val port = match.groupValues[2]
        if (port.isNotEmpty()) check(port.toInt() in 1..65535 && port != "443", "binding")
        return value
    }

    fun endpoint(value: String, expectedOrigin: String? = null): String {
        text(value, 512)
        val match = Regex("(https://[a-z0-9.:-]+)(/[A-Za-z0-9/._~-]*)").matchEntire(value)
            ?: throw Refusal("binding")
        val actual = origin(match.groupValues[1])
        check(match.groupValues[2].split('/').none { it == "." || it == ".." }, "binding")
        check(expectedOrigin == null || actual == expectedOrigin, "binding")
        return value
    }

    private fun fields(value: Any?, names: String): Map<String, Any?> {
        check(value is Map<*, *> && value.keys == names.split(' ').toSet())
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun array(value: Any?, low: Int = 0, high: Int = 64): List<*> {
        check(value is List<*> && value.size in low..high)
        return value as List<*>
    }

    private fun names(value: Any?, low: Int = 0, high: Int = 16): List<String> {
        val result = array(value, low, high).map(::id)
        check(result.distinct().size == result.size)
        return result
    }

    private fun capabilities(value: Any?, low: Int = 0): List<String> = names(value, low).also {
        check(supported.containsAll(it), "unsupported")
    }

    fun descriptor(raw: ByteArray, context: Context, now: Long): Descriptor {
        val d = fields(decode(raw), "v provider label expires_at required roles auth")
        integer(d["v"], 1, 1)
        val provider = origin(text(d["provider"], 512))
        check(provider == context.provider, "binding")
        val label = text(d["label"], 80)
        val expires = integer(d["expires_at"])
        check(now >= 0 && now < expires && expires - now <= 3600, "stale")
        val required = capabilities(d["required"], 2)
        check(required.containsAll(listOf("sealed-kit-1", "device-grants-1")), "unsupported")
        val roles = names(d["roles"], 1, 8)
        check(context.role in roles && context.role in setOf("vpn", "relay"), "unsupported")
        check(("browser-pkce-s256" in required) == (d["auth"] != null), "unsupported")
        check(("anonymous-device-1" in required) == (d["auth"] == null), "unsupported")
        check(DEVICE_BOUND !in required || d["auth"] != null, "unsupported")
        val auth = d["auth"]?.let { value ->
            val a = fields(value, "issuer authorization_endpoint token_endpoint revocation_endpoint client_id redirect_uri")
            val issuer = origin(text(a["issuer"], 512))
            check(issuer == context.acceptedIssuer, "binding")
            val client = id(a["client_id"])
            val redirect = endpoint(text(a["redirect_uri"], 512))
            check(client == context.clientId && redirect == context.redirectUri, "binding")
            Auth(issuer, endpoint(text(a["authorization_endpoint"], 512), issuer),
                endpoint(text(a["token_endpoint"], 512), issuer),
                endpoint(text(a["revocation_endpoint"], 512), issuer), client, redirect)
        }
        check(auth != null || context.acceptedIssuer == null, "binding")
        return Descriptor(provider, label, expires, required, roles, auth)
    }

    private fun bound(value: Map<String, Any?>, context: Context) {
        integer(value["v"], 1, 1)
        origin(text(value["provider"], 512))
        id(value["device_id"])
        text(value["audience"], 512)
        check(value["provider"] == context.provider && value["device_id"] == context.deviceId &&
            value["audience"] == context.audience, "binding")
    }

    fun catalog(raw: ByteArray, descriptor: Descriptor, context: Context, now: Long): Catalog {
        val c = fields(decode(raw, METADATA_LIMIT), "v provider device_id audience issued_at expires_at services")
        bound(c, context)
        val issued = integer(c["issued_at"])
        val expires = integer(c["expires_at"])
        check(issued <= now && now < expires && expires - issued <= 300 && expires <= descriptor.expiresAt, "stale")
        val services = array(c["services"], 0, 32).map { value ->
            val s = fields(value, "id label role required servers")
            val role = id(s["role"])
            check(role == context.role && role in descriptor.roles, "unsupported")
            val located = SERVER_LOCATION in descriptor.required
            val servers = array(s["servers"], 1, 32).map { server ->
                val e = fields(server, "id label host port server_key_sha256" + if (located) " country" else "")
                Server(id(e["id"]), text(e["label"], 80), dnsHost(text(e["host"], 253)),
                    integer(e["port"], 1, 65535).toInt(), digest(e["server_key_sha256"]), e["country"]?.let(::country))
            }
            check(servers.map { it.id }.distinct().size == servers.size)
            Service(id(s["id"]), text(s["label"], 80), role, capabilities(s["required"]), servers)
        }
        check(services.map { it.id }.distinct().size == services.size)
        return Catalog(context.provider, context.deviceId, context.audience, issued, expires, services)
    }

    fun request(request: GrantRequest, catalog: Catalog, context: Context, now: Long): Server {
        bound(request.fields(), context)
        listOf(request.requestId, request.serviceId, request.serverId, request.role).forEach(::identifier)
        request.previousGrant?.let(::identifier)
        check(now < catalog.expiresAt, "stale")
        check(request.role == context.role, "unsupported")
        check(catalog.provider == context.provider && catalog.deviceId == context.deviceId && catalog.audience == context.audience, "binding")
        val service = catalog.services.firstOrNull { it.id == request.serviceId } ?: throw Refusal("not_entitled")
        return service.servers.firstOrNull { it.id == request.serverId } ?: throw Refusal("not_entitled")
    }

    fun grant(raw: ByteArray, request: GrantRequest, previous: Grant?, now: Long): Grant {
        val g = fields(decode(raw, METADATA_LIMIT),
            "v provider device_id audience request_id service_id server_id role previous_grant grant_id generation issued_at refresh_after expires_at client_identity kit_sha256 kit_size")
        check(request.fields().all { (key, value) -> g[key] == value }, "binding")
        val grantId = id(g["grant_id"])
        val generation = integer(g["generation"], 1)
        val issued = integer(g["issued_at"])
        val refresh = integer(g["refresh_after"])
        val expires = integer(g["expires_at"])
        check(issued <= now && now < expires && expires - issued <= 3600 && refresh >= issued && refresh < expires, "stale")
        val identity = digest(g["client_identity"])
        val kitHash = digest(g["kit_sha256"])
        val size = integer(g["kit_size"], 1068, 1049648).toInt()
        check((size - 44) % 1024 == 0)
        if (previous == null) {
            check(request.previousGrant == null && generation == 1L, "state")
        } else {
            check(now >= previous.refreshAfter && request.previousGrant == previous.grantId &&
                generation == previous.generation + 1 && grantId != previous.grantId && identity != previous.clientIdentity, "state")
            val old = previous.request
            check(request.provider == old.provider && request.deviceId == old.deviceId &&
                request.serviceId == old.serviceId && request.serverId == old.serverId && request.role == old.role, "binding")
        }
        return Grant(request, grantId, generation, issued, refresh, expires, identity, kitHash, size)
    }

    /** Browser authorization always yields DPoP-bound tokens. */
    fun tokens(raw: ByteArray, previous: Tokens? = null): Tokens {
        val t = fields(decode(raw), "token_type access_token expires_in refresh_token refresh_expires_in scope")
        check(t["token_type"] == "DPoP" && t["scope"] == "provider.enroll", "unsupported")
        val access = text(t["access_token"])
        val refresh = text(t["refresh_token"])
        check(access.all { it.code in 33..126 } && refresh.all { it.code in 33..126 })
        check(access != refresh, "binding")
        val expires = integer(t["expires_in"], 60, 900)
        val refreshExpires = integer(t["refresh_expires_in"], expires + 1, 2592000)
        check(previous == null || (access != previous.accessToken && refresh != previous.refreshToken), "revoked")
        return Tokens(access, refresh, expires, refreshExpires)
    }

    /** Returns an owned array that the caller clears after native kit validation. */
    fun openingCode(raw: ByteArray, context: Context, grantId: String): CharArray {
        val o = fields(decode(raw), "v provider device_id audience grant_id opening_code")
        bound(o, context)
        check(o["grant_id"] == grantId, "binding")
        val code = text(o["opening_code"], 25)
        check(openingCodePattern.matches(code))
        return code.toCharArray()
    }

    private fun country(value: Any?): String = text(value, 2).also { check(countryPattern.matches(it)) }

    fun error(raw: ByteArray): String {
        val e = fields(decode(raw), "v error retry_after")
        integer(e["v"], 1, 1)
        val code = text(e["error"], 64)
        check(code in setOf("malformed", "binding", "unsupported", "unauthorized", "not_entitled", "conflict",
            "stale", "revoked", "rate_limited", "unavailable"))
        val retry = integer(e["retry_after"], 0, 300)
        check(retry == 0L || code in setOf("rate_limited", "unavailable"))
        return code
    }

    /**
     * True when an issuer's OAuth error (RFC 6749 section 5.2) refuses the
     * presented authorization code or refresh token. That ends the sign-in.
     * Any other or malformed body is false and stays unrecognized.
     */
    fun refusedGrant(raw: ByteArray): Boolean = try {
        val e = decode(raw)
        check(e is Map<*, *> && "error" in e.keys && e.keys.all { it in setOf("error", "error_description", "error_uri") })
        text((e as Map<*, *>)["error"], 64) == "invalid_grant"
    } catch (_: Exception) { false }

    /** Bounds are checked before UTF-8 decoding and before each recursive container. */
    fun decode(raw: ByteArray, limit: Int = SMALL_LIMIT): Any? {
        check(raw.isNotEmpty() && raw.size <= limit)
        val source = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) {
            throw Refusal("malformed")
        }
        return Parser(source).parse()
    }

    fun encode(value: Any?, limit: Int = SMALL_LIMIT): ByteArray {
        val out = StringBuilder()
        fun append(v: Any?, depth: Int) {
            when (v) {
                null -> out.append("null")
                is String -> {
                    text(v)
                    out.append('"')
                    for (c in v) {
                        if (c == '"' || c == '\\') out.append('\\')
                        out.append(c)
                    }
                    out.append('"')
                }
                is Long -> out.append(integer(v))
                is Int -> out.append(integer(v.toLong()))
                is Map<*, *> -> {
                    check(depth < 8)
                    out.append('{')
                    v.entries.forEachIndexed { index, entry ->
                        check(entry.key is String)
                        if (index > 0) out.append(',')
                        append(entry.key, depth + 1)
                        out.append(':')
                        append(entry.value, depth + 1)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    check(depth < 8 && v.size <= 64 && v.distinct().size == v.size)
                    out.append('[')
                    v.forEachIndexed { index, child ->
                        if (index > 0) out.append(',')
                        append(child, depth + 1)
                    }
                    out.append(']')
                }
                else -> throw Refusal("malformed")
            }
            check(out.length <= limit)
        }
        append(value, 0)
        return out.toString().toByteArray(Charsets.UTF_8).also { check(it.size <= limit) }
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun parse(): Any? {
            val result = value(0)
            whitespace()
            check(position == source.length)
            return result
        }

        private fun whitespace() {
            while (position < source.length && source[position] in " \r\n\t") position++
        }

        private fun take(c: Char): Boolean {
            if (position < source.length && source[position] == c) {
                position++
                return true
            }
            return false
        }

        private fun value(depth: Int): Any? {
            whitespace()
            check(position < source.length)
            return when (source[position]) {
                '{' -> {
                    check(depth < 8)
                    position++
                    val result = linkedMapOf<String, Any?>()
                    whitespace()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val key = quoted()
                            check(!result.containsKey(key))
                            whitespace()
                            check(take(':'))
                            result[key] = value(depth + 1)
                            whitespace()
                            if (take('}')) break
                            check(take(','))
                        } while (true)
                    }
                    result
                }
                '[' -> {
                    check(depth < 8)
                    position++
                    val result = mutableListOf<Any?>()
                    whitespace()
                    if (!take(']')) {
                        do {
                            check(result.size < 64)
                            val child = value(depth + 1)
                            check(child !in result)
                            result.add(child)
                            whitespace()
                            if (take(']')) break
                            check(take(','))
                        } while (true)
                    }
                    result
                }
                '"' -> quoted()
                'n' -> {
                    check(source.startsWith("null", position))
                    position += 4
                    null
                }
                in '0'..'9' -> {
                    val start = position
                    if (source[position++] != '0') {
                        while (position < source.length && source[position] in '0'..'9') position++
                    }
                    check(position - start <= 16)
                    integer(source.substring(start, position).toLongOrNull())
                }
                else -> throw Refusal("malformed")
            }
        }

        private fun quoted(): String {
            check(take('"'))
            val result = StringBuilder()
            while (position < source.length) {
                val c = source[position++]
                if (c == '"') return text(result.toString())
                if (c == '\\') {
                    check(position < source.length)
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'u' -> {
                            check(position + 4 <= source.length)
                            val digits = source.substring(position, position + 4)
                            check(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            result.append(digits.toInt(16).toChar())
                            position += 4
                        }
                        else -> throw Refusal("malformed")
                    }
                } else {
                    check(c.code >= 32 && c.code != 127)
                    result.append(c)
                }
                check(result.length <= 4096)
            }
            throw Refusal("malformed")
        }
    }
}
