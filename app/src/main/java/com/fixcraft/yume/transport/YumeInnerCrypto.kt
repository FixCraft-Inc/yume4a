package com.fixcraft.yume.transport

import android.os.SystemClock
import com.fixcraft.basefwx.Crypto
import org.bouncycastle.crypto.SecretWithEncapsulation
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Security
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

internal data class YumeInnerSessionState(
    val baseKey: ByteArray,
    val hopEnabled: Boolean,
    val hopIntervalMs: Int,
    val hopOffsetMs: Long,
)

internal object YumeInnerCrypto {
    private const val USER_KDF_SALT_SIZE = 16
    private const val HEAVY_PBKDF2_ITERATIONS = 1_000_000
    // Mobile-tuned Argon2id: enough work to be meaningful on a phone CPU
    // (Snapdragon ~1-2s) without spending 10s blocking the auth handshake.
    // The CLI defaults are time=4 / memory=256MiB which take ~5-15s on phones.
    private const val HEAVY_ARGON2_TIME_COST = 3
    private const val HEAVY_ARGON2_MEMORY_COST = 1 shl 16
    private const val HEAVY_ARGON2_PARALLELISM = 4
    // Match the server's kHopDecryptWindow (24). With hop_interval_ms = 500 (the
    // server default) this gives ±12 s of clock-drift tolerance, vs ±3 s at 6.
    // Android's System.currentTimeMillis() can jump several seconds at once
    // when the NTP service corrects the wall clock mid-session, so the wider
    // window is required for parity with the desktop client.
    private const val HOP_DECRYPT_WINDOW = 24
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

    fun decrypt(state: YumeInnerSessionState, frameType: YumeFrameType, streamId: Int, payload: ByteArray): ByteArray {
        val candidateKeys = decryptKeyCandidates(state)
        val aad = buildAad(frameType, streamId)
        var lastError: Exception? = null
        for (key in candidateKeys) {
            try {
                return Crypto.aesGcmDecrypt(key, payload, aad)
            } catch (throwable: Exception) {
                lastError = throwable as? Exception ?: Exception(throwable)
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
                } catch (_: Throwable) {
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
        return deriveHopKey(state.baseKey, currentHopId(state))
    }

    private fun decryptKeyCandidates(state: YumeInnerSessionState): List<ByteArray> {
        if (!state.hopEnabled || state.hopIntervalMs <= 0) {
            return listOf(state.baseKey)
        }
        val current = currentHopId(state)
        val ids = linkedSetOf<Long>()
        ids += current
        for (delta in 1..HOP_DECRYPT_WINDOW) {
            val previous = current - delta
            if (previous >= 0L) {
                ids += previous
            }
            ids += current + delta
        }
        return ids.map { hopId -> deriveHopKey(state.baseKey, hopId) }
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

    private fun argon2idRaw(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(parallelism)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        return ByteArray(length).also { output ->
            generator.generateBytes(password, output)
        }
    }

    private data class KemResult(
        val ciphertext: ByteArray,
        val shared: ByteArray,
    )

    private data class DerivedInnerKey(
        val key: ByteArray,
        val params: YumeInnerKdfParams,
    )
}
