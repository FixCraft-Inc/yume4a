package com.fixcraft.yume.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserSelector
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Supplied by the consuming application, never selected by a descriptor. */
data class ProviderRegistration(val applicationId: String, val clientId: String, val callback: String)

fun interface ProviderClock {
    /** Null suspends authority. A device wall clock alone is not trusted time. */
    fun now(): Reading?
    data class Reading(val seconds: Long, val elapsedMillis: Long)
}

/** Android's network clock is independent of user changes to the wall clock. */
object AndroidProviderClock : ProviderClock {
    override fun now(): ProviderClock.Reading? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            ProviderClock.Reading(SystemClock.currentNetworkTimeClock().millis() / 1000, SystemClock.elapsedRealtime())
        } catch (_: java.time.DateTimeException) { null }
    }
}

object ProviderAppLinks {
    fun verify(context: Context, registration: ProviderRegistration) {
        ProviderCodec.endpoint(registration.callback)
        val uri = Uri.parse(registration.callback)
        if (context.packageName != registration.applicationId || Build.VERSION.SDK_INT < 31) {
            throw ProviderCodec.Refusal("unsupported")
        }
        val manager = context.getSystemService(DomainVerificationManager::class.java)
        val state = manager?.getDomainVerificationUserState(context.packageName)
        if (state?.isLinkHandlingAllowed != true ||
            state.hostToStateMap[uri.host] != DomainVerificationUserState.DOMAIN_STATE_VERIFIED) {
            throw ProviderCodec.Refusal("binding")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.packageName != context.packageName) throw ProviderCodec.Refusal("binding")
    }
}

/**
 * Pending attempts live only in this process. Restart cancels them. Neither
 * AppAuth AuthState nor AuthorizationRequest is serialized into Activity state.
 * AppAuth owns request construction/S256 and browser selection. Its management
 * Activity is deliberately unused because it saves the verifier in a Bundle.
 * The sign-in page opens in a Custom Tab of the selected browser when it
 * supports one, otherwise in that browser. Either way the password stays in
 * the browser and the verified app link returns the result.
 */
class ProviderAuthorization(
    private val clock: ProviderClock,
) : AutoCloseable {
    private class Pending(
        val context: ProviderCodec.Context,
        val auth: ProviderCodec.Auth,
        val epoch: String,
        val state: String,
        val verifier: String,
        val deadline: ProviderAuthorizationDeadline,
        val generation: Long,
    ) {
        var expiry: ScheduledFuture<*>? = null
    }
    internal class Exchange(
        val provider: String, val epoch: String, val endpoint: String, val body: ByteArray, val generation: Long,
        val deadline: ProviderAuthorizationDeadline,
    ) : AutoCloseable {
        override fun toString() = "Exchange(redacted)"
        override fun close() { body.fill(0) }
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()
    private var closed = false

    fun begin(
        context: ProviderCodec.Context,
        descriptor: ProviderCodec.Descriptor,
        epoch: String,
        registration: ProviderRegistration,
        platform: Context,
        generation: Long = 0,
    ): Intent {
        ProviderAppLinks.verify(platform, registration)
        val request = beginRequest(context, descriptor, epoch, registration, generation)
        val browser = BrowserSelector.select(platform, AnyBrowserMatcher.INSTANCE)
            ?: run { cancel(context.provider); throw ProviderCodec.Refusal("unsupported") }
        val intent = if (browser.useCustomTab) CustomTabsIntent.Builder().setShowTitle(true).build().intent
            else Intent(Intent.ACTION_VIEW)
        return intent.setData(request.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser.packageName)
    }

    /** Internal seam for platform tests; production always verifies app links. */
    internal fun beginRequest(
        context: ProviderCodec.Context,
        descriptor: ProviderCodec.Descriptor,
        epoch: String,
        registration: ProviderRegistration,
        generation: Long = 0,
    ): AuthorizationRequest = synchronized(lock) {
        if (closed || generation < 0) throw ProviderCodec.Refusal("state")
        val now = clock.now() ?: run { clearLocked(); throw ProviderCodec.Refusal("stale") }
        // An expired browser tab must not occupy a provider's one-attempt slot.
        pending.values.filter { expired(it, now) }.forEach { removeLocked(it.context.provider) }
        val auth = descriptor.auth ?: throw ProviderCodec.Refusal("state")
        if (descriptor.provider != context.provider || descriptor.expiresAt <= now.seconds ||
            auth.issuer != context.acceptedIssuer || auth.clientId != registration.clientId ||
            auth.redirectUri != registration.callback || context.clientId != registration.clientId ||
            context.redirectUri != registration.callback) throw ProviderCodec.Refusal("binding")
        if (pending.containsKey(context.provider) || pending.size >= 8) throw ProviderCodec.Refusal("state")
        val state = random(32)
        val verifier = random(32)
        val request = AuthorizationRequest.Builder(configuration(auth), auth.clientId, ResponseTypeValues.CODE,
            Uri.parse(auth.redirectUri)).setScope("provider.enroll").setState(state).setNonce(null)
            .setCodeVerifier(verifier, CodeVerifierUtil.deriveCodeVerifierChallenge(verifier), "S256")
            .setAdditionalParameters(mapOf("resource" to context.audience)).build()
        val attempt = Pending(context, auth, epoch, state, verifier, ProviderAuthorizationDeadline(now), generation)
        pending[context.provider] = attempt
        // The scheduler bounds retention even when no callback or API call arrives.
        attempt.expiry = expiryExecutor.schedule({
            synchronized(lock) {
                if (pending[context.provider] === attempt) removeLocked(context.provider)
            }
        }, 300, TimeUnit.SECONDS)
        request
    }

    internal fun consume(callback: String): Exchange = synchronized(lock) {
        if (closed) throw ProviderCodec.Refusal("state")
        if (callback.length > 16384 || callback.any { it.code !in 0x21..0x7e }) throw ProviderCodec.Refusal("malformed")
        val uri = try { URI(callback) } catch (_: java.net.URISyntaxException) { throw ProviderCodec.Refusal("malformed") }
        if (uri.rawFragment != null || uri.rawQuery == null) throw ProviderCodec.Refusal("binding")
        val query = query(uri.rawQuery)
        val denied = query.keys == setOf("iss", "state", "error") && query["error"] == "access_denied"
        if (!denied && query.keys != setOf("iss", "state", "code")) throw ProviderCodec.Refusal("malformed")
        val attempt = pending.values.singleOrNull { equal(it.state, query.getValue("state")) }
            ?: throw ProviderCodec.Refusal("state")
        val now = clock.now() ?: run { removeLocked(attempt.context.provider); throw ProviderCodec.Refusal("stale") }
        if (callback.substringBefore('?') != attempt.auth.redirectUri || query["iss"] != attempt.auth.issuer) {
            throw ProviderCodec.Refusal("binding")
        }
        if (expired(attempt, now)) {
            removeLocked(attempt.context.provider)
            throw ProviderCodec.Refusal("stale")
        }
        if (denied) {
            removeLocked(attempt.context.provider)
            throw ProviderCodec.Refusal("unauthorized")
        }
        val code = query.getValue("code")
        if (code.length !in 1..512 || code.any { it.code !in 0x21..0x7e }) throw ProviderCodec.Refusal("malformed")
        // Consume before any exchange. An ambiguous exchange is never retried.
        removeLocked(attempt.context.provider)
        val token = TokenRequest.Builder(configuration(attempt.auth), attempt.auth.clientId)
            .setGrantType("authorization_code").setAuthorizationCode(code).setRedirectUri(Uri.parse(attempt.auth.redirectUri))
            .setCodeVerifier(attempt.verifier).setAdditionalParameters(mapOf("resource" to attempt.context.audience)).build()
        Exchange(attempt.context.provider, attempt.epoch, attempt.auth.tokenEndpoint,
            form(token.requestParameters + ("client_id" to attempt.auth.clientId)), attempt.generation, attempt.deadline)
    }

    private fun expired(attempt: Pending, now: ProviderClock.Reading): Boolean =
        attempt.deadline.remainingMillis(now) == 0L

    private fun removeLocked(provider: String) { pending.remove(provider)?.expiry?.cancel(false) }
    private fun clearLocked() {
        pending.values.forEach { it.expiry?.cancel(false) }
        pending.clear()
    }
    fun cancel(provider: String) { synchronized(lock) { removeLocked(provider) } }
    fun clear() { synchronized(lock) { clearLocked() } }
    override fun close() {
        synchronized(lock) {
            closed = true
            clearLocked()
        }
    }

    companion object {
        // One process-wide daemon, with cancelled tasks removed rather than retaining PKCE state.
        private val expiryExecutor = ScheduledThreadPoolExecutor(1) { work ->
            Thread(work, "provider-auth-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        fun randomIdentifier(entropy: SecureRandom = SecureRandom()): String = "r" + random(16, entropy)

        fun random(bytes: Int, entropy: SecureRandom = SecureRandom()): String {
            val data = ByteArray(bytes)
            return try { entropy.nextBytes(data); Base64.getUrlEncoder().withoutPadding().encodeToString(data) }
            finally { data.fill(0) }
        }
        fun configuration(auth: ProviderCodec.Auth) = AuthorizationServiceConfiguration(
            Uri.parse(auth.authorizationEndpoint), Uri.parse(auth.tokenEndpoint))
        fun form(values: Map<String, String>): ByteArray = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.US_ASCII).also {
            if (it.size > 16384) { it.fill(0); throw ProviderCodec.Refusal("malformed") }
        }
        private fun query(raw: String): Map<String, String> {
            val values = linkedMapOf<String, String>()
            for (part in raw.split('&')) {
                val pair = part.split('=', limit = 2)
                if (pair.size != 2 || values.size >= 3) throw ProviderCodec.Refusal("malformed")
                val key: String
                val value: String
                try {
                    key = URLDecoder.decode(pair[0], "UTF-8")
                    value = URLDecoder.decode(pair[1], "UTF-8")
                } catch (_: IllegalArgumentException) { throw ProviderCodec.Refusal("malformed") }
                if (values.put(key, value) != null || value.isEmpty() || value.any { it.code !in 0x21..0x7e }) {
                    throw ProviderCodec.Refusal("malformed")
                }
            }
            return values
        }
        private fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }
}
