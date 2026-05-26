package com.fixcraft.yume.transport

import android.os.SystemClock
import com.fixcraft.basefwx.Constants
import com.fixcraft.basefwx.Crypto
import org.bouncycastle.crypto.SecretWithEncapsulation
// 1.0.x: direct BouncyCastle Argon2 imports removed when argon2idRaw()
// switched to Crypto.argon2idHashRaw, the documented single dispatch
// point per crypto-conventions Rule 6. Crypto manages the JNI-vs-BC
// fallback internally; calling Argon2BytesGenerator here re-routed
// past that helper and bypassed the JNI-first dispatch.
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Security
import java.util.Arrays
import kotlin.math.floor

internal enum class YumeInnerKdf {
    HKDF,
    ARGON2,
    PBKDF2,
}

internal data class YumeInnerPreference(
    val enabled: Boolean,
    val pqPublicKey: ByteArray? = null,
    val heavy: Boolean = true,
    val hopRequested: Boolean = true,
    val kdf: YumeInnerKdf = YumeInnerKdf.ARGON2,
) {
    companion object {
        fun disabled(): YumeInnerPreference = YumeInnerPreference(enabled = false)
    }
}

internal data class YumeInnerKdfParams(
    val name: String,
    val argon2Time: Int = 0,
    val argon2Memory: Int = 0,
    val argon2Parallelism: Int = 0,
    val pbkdf2Iterations: Int = 0,
)

internal data class YumeInnerPrepared(
    val pqCiphertext: ByteArray,
    val salt: ByteArray,
    val key: ByteArray,
    val mode: String,
    val hopRequested: Boolean,
    val kdfParams: YumeInnerKdfParams,
) {
    val enabled: Boolean
        get() = key.isNotEmpty()
}

internal class YumeInnerSessionState(
    val baseKey: ByteArray,
    val hopEnabled: Boolean,
    val hopIntervalMs: Int,
    val hopOffsetMs: Long,
) {
    // Per-session derived-hop-key cache. HKDF(baseKey, "yume-hop-v1:N")
    // is deterministic — same baseKey + same hopId → same key, every
    // time. Without this cache, decryptKeyCandidates() runs 49 HKDF-
    // SHA256 derivations PER INCOMING FRAME (HOP_DECRYPT_WINDOW = 24
    // either side of current), which on Android's pure-Java JCE
    // dominates the data path (~280 ms/s of CPU for 0.5 Mbps in real
    // measurements; ~98% of decrypt cost). With the cache, each
    // unique hopId is derived at most once per session, so a frame
    // received on the current (or any previously-seen) hop has zero
    // HKDF cost — only the AES-GCM decrypt itself runs.
    //
    // Bound on cache size: 2 * HOP_DECRYPT_WINDOW + a few = ~50 entries
    // over the long lifetime of a session, even with NTP-clock drift
    // walking through the window. Sub-KB total. No eviction needed.
    internal val hopKeyCache: java.util.concurrent.ConcurrentHashMap<Long, ByteArray> =
        java.util.concurrent.ConcurrentHashMap()
}

internal object YumeInnerCrypto {
    private const val USER_KDF_SALT_SIZE = 16
    private const val HEAVY_PBKDF2_ITERATIONS = 1_000_000
    // Mobile-tuned Argon2id: enough work to be meaningful on a phone CPU
    // (Snapdragon ~1-2s) without spending 10s blocking the auth handshake.
    // The CLI defaults are time=4 / memory=256MiB which take ~5-15s on phones.
    private const val HEAVY_ARGON2_TIME_COST = 3
    private const val HEAVY_ARGON2_MEMORY_COST = 1 shl 16
    private const val HEAVY_ARGON2_PARALLELISM = 4
    // Match the server's kHopDecryptWindow (120). At hop_interval_ms = 500 ms
    // that's ±60 s of tolerance for the time between a frame being encrypted
    // and the peer attempting to decrypt it. The old 24-hop (±12 s) window
    // wasn't wide enough under heavy mobile upload congestion: a DATA frame
    // could sit in the outbound channel + TLS tx_queue for 10+ seconds when
    // wifi upload saturated at ~10 Mbps, putting its hop_id just outside the
    // server's decrypt window — the server then failed AES-GCM tag verify
    // and forcibly closed the session, manifesting as "speedtest could not
    // connect" mid-test. 60 s covers the worst-case mobile-upload backlog
    // we've observed in real traces. The cache keeps the cost flat: each
    // unique hop_id is derived at most once per session.
    private const val HOP_DECRYPT_WINDOW = 120
    private val HKDF_INFO = "yume-inner-v1".toByteArray(StandardCharsets.US_ASCII)
    private const val HOP_INFO_PREFIX = "yume-hop-v1:"
    private val RNG = SecureRandom()

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        if (Security.getProvider(BouncyCastlePQCProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastlePQCProvider())
        }
    }

    fun prepare(preference: YumeInnerPreference): YumeInnerPrepared {
        val pqPublicKey = preference.pqPublicKey
        if (!preference.enabled || pqPublicKey == null || pqPublicKey.isEmpty()) {
            throw YumeBootstrapException("Inner crypto requested without a trusted PQ public key")
        }
        val kemResult = kemEncrypt(pqPublicKey)
        try {
            val salt = randomBytes(USER_KDF_SALT_SIZE)
            val mode = if (preference.heavy) "heavy" else "light"
            val derived = if (preference.heavy) {
                deriveHeavyKey(kemResult.shared, salt, preference.kdf)
            } else {
                DerivedInnerKey(
                    key = Crypto.hkdfSha256(kemResult.shared, HKDF_INFO, 32),
                    params = YumeInnerKdfParams(name = "hkdf"),
                )
            }
            return YumeInnerPrepared(
                pqCiphertext = kemResult.ciphertext,
                salt = salt,
                key = derived.key,
                mode = mode,
                hopRequested = preference.hopRequested,
                kdfParams = derived.params,
            )
        } finally {
            Arrays.fill(kemResult.shared, 0.toByte())
        }
    }

    fun encrypt(state: YumeInnerSessionState, frameType: YumeFrameType, streamId: Int, plaintext: ByteArray): ByteArray {
        val key = currentEncryptKey(state)
        val aad = buildAad(frameType, streamId)
        return try {
            Crypto.aesGcmEncrypt(key, plaintext, aad)
        } catch (throwable: IllegalArgumentException) {
            throw YumeBootstrapException("Failed to encrypt the Yume inner payload", throwable)
        } catch (throwable: IllegalStateException) {
            throw YumeBootstrapException("Failed to encrypt the Yume inner payload", throwable)
        }
    }

    /**
     * Encrypts directly into a caller-supplied output buffer. `output`
     * must be at least `plainLen + 28` bytes (12 IV + plaintext + 16 GCM tag).
     * Returns the number of bytes written (always `plainLen + 28` on
     * success). The IV is written into `output[0..12)` and the
     * ciphertext+tag follows.
     *
     * Used by the upload hot path: instead of [encrypt] allocating a
     * fresh ByteArray per frame, the caller pools a buffer of the
     * exact target size and reuses it.
     */
    fun encryptInto(
        state: YumeInnerSessionState,
        frameType: YumeFrameType,
        streamId: Int,
        plaintext: ByteArray,
        plainOff: Int,
        plainLen: Int,
        output: ByteArray,
    ): Int {
        val key = currentEncryptKey(state)
        val aad = buildAad(frameType, streamId)
        val iv = Crypto.randomBytes(Constants.AEAD_NONCE_LEN)
        return try {
            System.arraycopy(iv, 0, output, 0, iv.size)
            val cipherLen = Crypto.aesGcmEncryptWithIvInto(
                key, iv, plaintext, plainOff, plainLen, output, iv.size, aad,
            )
            iv.size + cipherLen
        } catch (throwable: IllegalArgumentException) {
            throw YumeBootstrapException("Failed to encrypt the Yume inner payload", throwable)
        } catch (throwable: IllegalStateException) {
            throw YumeBootstrapException("Failed to encrypt the Yume inner payload", throwable)
        }
    }

    fun decrypt(state: YumeInnerSessionState, frameType: YumeFrameType, streamId: Int, payload: ByteArray): ByteArray {
        val aad = buildAad(frameType, streamId)
        var lastError: Exception? = null
        // Iterate candidates LAZILY. The common case (frame on the
        // current hop) finds the right key on the first attempt and
        // never derives the other 48 candidates. Without lazy
        // iteration we used to pre-derive all 49 per frame; that
        // single change is the throughput fix.
        for (key in decryptKeyCandidates(state)) {
            try {
                return Crypto.aesGcmDecrypt(key, payload, aad)
            } catch (throwable: Exception) {
                lastError = throwable
            }
        }
        throw YumeBootstrapException("Failed to decrypt the Yume inner payload", lastError)
    }

    private fun deriveHeavyKey(shared: ByteArray, salt: ByteArray, preferredKdf: YumeInnerKdf): DerivedInnerKey {
        return when (preferredKdf) {
            YumeInnerKdf.PBKDF2 -> {
                DerivedInnerKey(
                    key = Crypto.pbkdf2HmacSha256(shared, salt, HEAVY_PBKDF2_ITERATIONS, 32),
                    params = YumeInnerKdfParams(
                        name = "pbkdf2",
                        pbkdf2Iterations = HEAVY_PBKDF2_ITERATIONS,
                    ),
                )
            }

            YumeInnerKdf.ARGON2 -> {
                val params = selectArgon2Params()
                try {
                    DerivedInnerKey(
                        key = argon2idRaw(shared, salt, params.argon2Time, params.argon2Memory, params.argon2Parallelism, 32),
                        params = params,
                    )
                } catch (exc: Throwable) {
                    // 1.0.x: narrow the catch from bare-Throwable to
                    // OOM-only. Previously any exception silently
                    // downgraded Argon2 → PBKDF2, which hid configuration
                    // errors (BC class missing, invalid params, etc.)
                    // behind a working-but-weaker code path. Mirror the
                    // C++ inner_crypto.cpp::is_oom predicate so the
                    // fallback behavior is symmetric across runtimes.
                    if (!isOomError(exc)) {
                        throw exc
                    }
                    DerivedInnerKey(
                        key = Crypto.pbkdf2HmacSha256(shared, salt, HEAVY_PBKDF2_ITERATIONS, 32),
                        params = YumeInnerKdfParams(
                            name = "pbkdf2",
                            pbkdf2Iterations = HEAVY_PBKDF2_ITERATIONS,
                        ),
                    )
                }
            }

            YumeInnerKdf.HKDF -> {
                DerivedInnerKey(
                    key = Crypto.hkdfSha256(shared, HKDF_INFO, 32),
                    params = YumeInnerKdfParams(name = "hkdf"),
                )
            }
        }
    }

    private fun selectArgon2Params(): YumeInnerKdfParams {
        val scaled = floor(Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 0.84).toInt()
        val parallelism = scaled.coerceAtLeast(1)
        return YumeInnerKdfParams(
            name = "argon2",
            argon2Time = HEAVY_ARGON2_TIME_COST,
            argon2Memory = HEAVY_ARGON2_MEMORY_COST.coerceAtLeast(parallelism * 8),
            argon2Parallelism = parallelism.coerceAtMost(HEAVY_ARGON2_PARALLELISM.coerceAtLeast(1)),
            pbkdf2Iterations = HEAVY_PBKDF2_ITERATIONS,
        )
    }

    private fun currentEncryptKey(state: YumeInnerSessionState): ByteArray {
        if (!state.hopEnabled || state.hopIntervalMs <= 0) {
            return state.baseKey
        }
        // Same caching reasoning as the decrypt path: every send used
        // to re-derive the current hop key from scratch (one HKDF per
        // outbound frame). Cache by hopId so each unique hop only
        // costs one HKDF for the entire interval (~500 ms ≈ thousands
        // of frames per derivation).
        return cachedHopKey(state, currentHopId(state))
    }

    private fun decryptKeyCandidates(state: YumeInnerSessionState): Sequence<ByteArray> {
        if (!state.hopEnabled || state.hopIntervalMs <= 0) {
            return sequenceOf(state.baseKey)
        }
        val current = currentHopId(state)
        // Yield hopIds in priority order (current first, then ±1, ±2, ...).
        // Derivation happens lazily inside the map step — the caller's
        // for-loop short-circuits on the first successful decrypt, so
        // the common case (frame on current hop) derives ONE key, not
        // 49. Cache lookups for already-seen hopIds skip derivation
        // entirely. See YumeInnerSessionState.hopKeyCache for the
        // rationale + sizing.
        val ids = sequence {
            yield(current)
            for (delta in 1..HOP_DECRYPT_WINDOW) {
                val previous = current - delta
                if (previous >= 0L) yield(previous)
                yield(current + delta)
            }
        }
        return ids.map { hopId -> cachedHopKey(state, hopId) }
    }

    private fun cachedHopKey(state: YumeInnerSessionState, hopId: Long): ByteArray {
        // ConcurrentHashMap.computeIfAbsent — atomic "derive once,
        // return forever" for this (session, hopId). The derivation
        // function is pure (HKDF on baseKey + hopId) so duplicate
        // races just compute the same bytes; ConcurrentHashMap
        // serializes the store either way.
        return state.hopKeyCache.computeIfAbsent(hopId) {
            deriveHopKey(state.baseKey, it)
        }
    }

    private fun currentHopId(state: YumeInnerSessionState): Long {
        if (!state.hopEnabled || state.hopIntervalMs <= 0) {
            return 0
        }
        // Use monotonic elapsedRealtime() so a mid-session NTP wall-clock step
        // (which Android performs as a step, not a slew) cannot drift our
        // hop_id outside the server's tolerance window. hopOffsetMs is anchored
        // once to the server's wall clock at AUTH time and tracks server time
        // assuming both clocks advance at the same rate.
        val adjusted = (SystemClock.elapsedRealtime() + state.hopOffsetMs).coerceAtLeast(0)
        return adjusted / state.hopIntervalMs.toLong()
    }

    private fun deriveHopKey(baseKey: ByteArray, hopId: Long): ByteArray {
        val info = (HOP_INFO_PREFIX + hopId.toString()).toByteArray(StandardCharsets.US_ASCII)
        return Crypto.hkdfSha256(baseKey, info, 32)
    }

    private fun buildAad(frameType: YumeFrameType, streamId: Int): ByteArray {
        return byteArrayOf(
            'Y'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'E'.code.toByte(),
            (frameType.wireValue and 0xFF).toByte(),
            (streamId and 0xFF).toByte(),
        )
    }

    private fun kemEncrypt(publicKeyBytes: ByteArray): KemResult {
        val publicKey = KyberPublicKeyParameters(KyberParameters.kyber768, publicKeyBytes)
        val generator = KyberKEMGenerator(RNG)
        val encapsulated: SecretWithEncapsulation = generator.generateEncapsulated(publicKey)
        return KemResult(
            ciphertext = encapsulated.encapsulation,
            shared = encapsulated.secret,
        )
    }

    private fun randomBytes(length: Int): ByteArray = Crypto.randomBytes(length)

    // Mirror of yume's C++ inner_crypto.cpp::is_oom predicate. Returns
    // true only for failures that look like Argon2 ran out of memory —
    // the only case where downgrading to PBKDF2 is acceptable. Anything
    // else (BC class missing, invalid params, etc.) should propagate so
    // the caller sees the real error instead of a silently-weaker key.
    private fun isOomError(exc: Throwable): Boolean {
        // Walk the cause chain because BouncyCastle wraps allocation
        // failures inside IllegalStateException / IllegalArgumentException.
        var cur: Throwable? = exc
        while (cur != null) {
            if (cur is OutOfMemoryError) return true
            val msg = cur.message?.lowercase() ?: ""
            if ("out of memory" in msg
                || "insufficient memory" in msg
                || "memory allocation" in msg
                || "memory cost is too large" in msg
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    // 1.0.x: delegate to the synced Crypto.argon2idHashRaw, the project's
    // documented single dispatch point per crypto-conventions Rule 6.
    // Crypto handles the JNI-libargon2-then-BouncyCastle-fallback chain
    // internally; calling Argon2BytesGenerator directly here bypassed
    // the JNI-first path and meant the Android code took the slow route
    // even when libargon2 was loadable.
    private fun argon2idRaw(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray = Crypto.argon2idHashRaw(password, salt, iterations, memoryKiB, parallelism, length)

    private data class KemResult(
        val ciphertext: ByteArray,
        val shared: ByteArray,
    )

    private data class DerivedInnerKey(
        val key: ByteArray,
        val params: YumeInnerKdfParams,
    )
}
