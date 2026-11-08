package com.fixcraft.yume.provider

/**
 * An account or device credential usable only at its explicitly bound API
 * origin. Never print it. With a proof it is sent as a DPoP-bound token.
 */
class ProviderBearer(val origin: String, val value: String, val deviceId: String? = null) {
    override fun toString() = "ProviderBearer(redacted)"
}

/**
 * Bounded application HTTP boundary. Production uses [ProviderHttp], whose TLS,
 * origin, redirect, cookie and deadline checks cannot be overridden by providers.
 * Synthetic transports test lifecycle only and do not qualify HTTPS delivery.
 */
interface ProviderTransport : AutoCloseable {
    suspend fun request(
        origin: String,
        endpoint: String,
        body: ByteArray? = null,
        bearer: ProviderBearer? = null,
        limit: Int = ProviderCodec.SMALL_LIMIT,
        responseType: String = "application/json",
        requestType: String = "application/json",
        /** An RFC 9449 proof for this exact method and URL. */
        proof: String? = null,
    ): ByteArray
}
