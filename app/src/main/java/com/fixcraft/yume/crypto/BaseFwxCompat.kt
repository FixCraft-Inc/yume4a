package com.fixcraft.yume.crypto

import com.fixcraft.basefwx.Base64Codec
import com.fixcraft.basefwx.Codec
import com.fixcraft.basefwx.Constants
import com.fixcraft.basefwx.Crypto
import com.fixcraft.basefwx.Format
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.math.max

internal object BaseFwxCompat {
    data class DecodedFile(
        val data: ByteArray,
        val extension: String,
    )

    private data class MaskKeyPackage(
        val maskKey: ByteArray,
        val userBlob: ByteArray,
    )

    fun fwxAesEncryptRaw(plaintext: ByteArray, password: String): ByteArray {
        val passwordBytes = passwordBytes(password, required = true)
        val iv = Crypto.randomBytes(Constants.FWXAES_IV_LEN)
        val salt = Crypto.randomBytes(Constants.FWXAES_SALT_LEN)
        val iterations = fwxAesIterations(passwordBytes)
        val key = Crypto.pbkdf2HmacSha256(passwordBytes, salt, iterations, Constants.FWXAES_KEY_LEN)
        val ciphertextLength = plaintext.size + Constants.AEAD_TAG_LEN
        val output = ByteArray(16 + salt.size + iv.size + ciphertextLength)
        System.arraycopy(Constants.FWXAES_MAGIC, 0, output, 0, Constants.FWXAES_MAGIC.size)
        output[4] = Constants.FWXAES_ALGO.toByte()
        output[5] = Constants.FWXAES_KDF_PBKDF2.toByte()
        output[6] = Constants.FWXAES_SALT_LEN.toByte()
        output[7] = Constants.FWXAES_IV_LEN.toByte()
        writeU32(output, 8, iterations)
        writeU32(output, 12, ciphertextLength)
        var offset = 16
        System.arraycopy(salt, 0, output, offset, salt.size)
        offset += salt.size
        System.arraycopy(iv, 0, output, offset, iv.size)
        offset += iv.size
        val written = Crypto.aesGcmEncryptWithIvInto(
            key,
            iv,
            plaintext,
            0,
            plaintext.size,
            output,
            offset,
            Constants.FWXAES_AAD,
        )
        check(written == ciphertextLength) { "fwxAES encrypt length mismatch" }
        return output
    }

    fun looksLikeRawFwxAes(blob: ByteArray): Boolean {
        return blob.size >= Constants.FWXAES_MAGIC.size &&
            blob.copyOfRange(0, Constants.FWXAES_MAGIC.size).contentEquals(Constants.FWXAES_MAGIC)
    }

    fun fwxAesDecryptRaw(blob: ByteArray, password: String): ByteArray {
        val passwordBytes = passwordBytes(password, required = true)
        require(blob.size >= 16) { "fwxAES blob too short" }
        require(looksLikeRawFwxAes(blob)) {
            "fwxAES bad magic"
        }
        val algorithm = blob[4].toInt() and 0xFF
        val kdf = blob[5].toInt() and 0xFF
        val saltLength = blob[6].toInt() and 0xFF
        val ivLength = blob[7].toInt() and 0xFF
        require(algorithm == Constants.FWXAES_ALGO && kdf == Constants.FWXAES_KDF_PBKDF2) {
            "fwxAES unsupported algo"
        }
        val iterations = readU32(blob, 8)
        val ciphertextLength = readU32(blob, 12)
        val expectedLength = 16 + saltLength + ivLength + ciphertextLength
        require(blob.size >= expectedLength) { "fwxAES blob truncated" }
        var offset = 16
        val salt = blob.copyOfRange(offset, offset + saltLength)
        offset += saltLength
        val iv = blob.copyOfRange(offset, offset + ivLength)
        offset += ivLength
        val key = Crypto.pbkdf2HmacSha256(
            passwordBytes,
            salt,
            hardenPbkdf2Iterations(passwordBytes, iterations),
            Constants.FWXAES_KEY_LEN,
        )
        val plaintext = ByteArray(max(0, ciphertextLength - Constants.AEAD_TAG_LEN))
        val written = Crypto.aesGcmDecryptWithIvInto(
            key,
            iv,
            blob,
            offset,
            ciphertextLength,
            plaintext,
            0,
            Constants.FWXAES_AAD,
        )
        return if (written == plaintext.size) plaintext else plaintext.copyOf(max(0, written))
    }

    fun fwxAesEncryptText(input: String, password: String): String {
        return encodePayloadString(fwxAesEncryptRaw(input.toByteArray(StandardCharsets.UTF_8), password))
    }

    fun fwxAesDecryptText(input: String, password: String): String {
        val blob = decodePayloadString(input)
        return String(fwxAesDecryptRaw(blob, password), StandardCharsets.UTF_8)
    }

    fun fwxAesHeavyEncryptText(input: String, password: String): String {
        val plaintext = input.toByteArray(StandardCharsets.UTF_8)
        val fastObf = false
        val metadata = buildHeavyMetadataBlob(
            method = "fwxAES-heavy",
            obfuscationMode = if (fastObf) "fast" else "yes",
            kdfIterations = Constants.HEAVY_PBKDF2_ITERATIONS,
        )
        return encodePayloadString(
            encryptAesPayloadBytes(
                payloadBytes = plaintext,
                password = password,
                metadataBlob = metadata,
                kdfIterations = Constants.HEAVY_PBKDF2_ITERATIONS,
                obfuscate = true,
                fastObf = fastObf,
            )
        )
    }

    fun fwxAesHeavyDecryptText(input: String, password: String): String {
        val blob = decodePayloadString(input)
        return String(decryptAesPayloadBytes(blob, password), StandardCharsets.UTF_8)
    }

    fun b512Encode(input: String, password: String): String {
        val packageData = prepareMaskKeyPackage(
            passwordBytes = passwordBytes(password, required = true),
            aad = Constants.MASK_AAD_B512,
            defaultIterations = Constants.USER_KDF_ITERATIONS,
        )
        val blob = encodeMaskedPayloadBytes(
            maskKey = packageData.maskKey,
            userBlob = packageData.userBlob,
            plain = input.toByteArray(StandardCharsets.UTF_8),
            streamInfo = Constants.B512_STREAM_INFO,
        )
        return encodePayloadString(blob)
    }

    fun b512Decode(input: String, password: String): String {
        val plaintext = decodeMaskedPayloadBytesFromString(
            input = input,
            passwordBytes = passwordBytes(password, required = false),
            maskInfo = Constants.B512_MASK_INFO,
            aad = Constants.MASK_AAD_B512,
            streamInfo = Constants.B512_STREAM_INFO,
        )
        return String(plaintext, StandardCharsets.UTF_8)
    }

    fun pb512Encode(input: String, password: String): String {
        val packageData = prepareMaskKeyPackage(
            passwordBytes = passwordBytes(password, required = true),
            aad = Constants.MASK_AAD_PB512,
            defaultIterations = Constants.USER_KDF_ITERATIONS,
        )
        val blob = encodeMaskedPayloadBytes(
            maskKey = packageData.maskKey,
            userBlob = packageData.userBlob,
            plain = input.toByteArray(StandardCharsets.UTF_8),
            streamInfo = Constants.PB512_STREAM_INFO,
        )
        return encodePayloadString(blob)
    }

    fun pb512Decode(input: String, password: String): String {
        val plaintext = decodeMaskedPayloadBytesFromString(
            input = input,
            passwordBytes = passwordBytes(password, required = true),
            maskInfo = Constants.PB512_MASK_INFO,
            aad = Constants.MASK_AAD_PB512,
            streamInfo = Constants.PB512_STREAM_INFO,
        )
        return String(plaintext, StandardCharsets.UTF_8)
    }

    fun b512FileDecodeBytes(blob: ByteArray, password: String): DecodedFile {
        val passwordBytes = passwordBytes(password, required = false)
        val content = try {
            val parts = Format.unpackLengthPrefixed(blob, 3)
            val maskKey = recoverMaskKey(
                userBlob = parts[0],
                passwordBytes = passwordBytes,
                aad = Constants.B512_AEAD_INFO,
                defaultIterations = Constants.USER_KDF_ITERATIONS,
            )
            val aeadKey = Crypto.hkdfSha256(maskKey, Constants.B512_AEAD_INFO, 32)
            val payloadBytes = Crypto.aesGcmDecrypt(aeadKey, parts[2], Constants.B512_AEAD_INFO)
            String(payloadBytes, StandardCharsets.UTF_8)
        } catch (_: RuntimeException) {
            String(blob, StandardCharsets.UTF_8)
        }
        val (metadataBlob, body) = splitMetadata(content)
        val useMasterHint = metaValue(metadataBlob, "ENC-MASTER")
        require(!useMasterHint.equals("yes", ignoreCase = true)) { "Master-wrapped payloads are not supported on Android" }
        val (extToken, dataToken) = splitWithDelims(
            body,
            Constants.FWX_DELIM,
            Constants.LEGACY_FWX_DELIM,
            "FWX container",
        )
        val extension = b512Decode(extToken, password)
        val data = Base64Codec.decode(b512Decode(dataToken, password))
        return DecodedFile(data = data, extension = extension)
    }

    fun pb512FileDecodeBytes(blob: ByteArray, password: String): DecodedFile {
        val plaintext = decryptAesPayloadBytes(blob, password)
        val payload = String(plaintext, StandardCharsets.UTF_8)
        val (metadataBlob, body) = splitMetadata(payload)
        val useMasterHint = metaValue(metadataBlob, "ENC-MASTER")
        require(!useMasterHint.equals("yes", ignoreCase = true)) { "Master-wrapped payloads are not supported on Android" }
        val (extToken, dataToken) = splitWithDelims(
            body,
            Constants.FWX_HEAVY_DELIM,
            Constants.LEGACY_FWX_HEAVY_DELIM,
            "FWX heavy",
        )
        val extension = pb512Decode(extToken, password)
        val data = Base64Codec.decode(pb512Decode(dataToken, password))
        return DecodedFile(data = data, extension = extension)
    }

    private fun decryptAesPayloadBytes(blob: ByteArray, password: String): ByteArray {
        val passwordBytes = passwordBytes(password, required = true)
        val parts = Format.unpackLengthPrefixed(blob, 3)
        val userBlob = parts[0]
        val masterBlob = parts[1]
        val payloadBlob = parts[2]
        require(masterBlob.isEmpty()) { "Master-wrapped payloads are not supported on Android" }
        require(payloadBlob.size >= 4) { "Ciphertext payload truncated" }
        val metadataLength = readU32(payloadBlob, 0)
        val metadataEnd = 4 + metadataLength
        require(metadataEnd <= payloadBlob.size) { "Malformed payload metadata header" }
        val metadataBytes = payloadBlob.copyOfRange(4, metadataEnd)
        val metadataBlob = if (metadataBytes.isEmpty()) "" else String(metadataBytes, StandardCharsets.UTF_8)
        val obfuscationMode = metaValue(metadataBlob, "ENC-OBF")
        val kdfIterations = parseMetadataInt(
            metaValue(metadataBlob, "ENC-KDF-ITER"),
            Constants.HEAVY_PBKDF2_ITERATIONS,
        )
        val ephemeralKey = recoverWrappedUserKey(
            userBlob = userBlob,
            passwordBytes = passwordBytes,
            aad = metadataBytes,
            defaultIterations = kdfIterations,
        )
        val ciphertext = payloadBlob.copyOfRange(metadataEnd, payloadBlob.size)
        var plaintext = Crypto.aesGcmDecrypt(ephemeralKey, ciphertext, metadataBytes)
        if (!obfuscationMode.equals("no", ignoreCase = true)) {
            plaintext = deobfuscateBytes(
                data = plaintext,
                key = ephemeralKey,
                fast = obfuscationMode.equals("fast", ignoreCase = true),
            )
        }
        return plaintext
    }

    private fun prepareMaskKeyPackage(
        passwordBytes: ByteArray,
        aad: ByteArray,
        defaultIterations: Int,
    ): MaskKeyPackage {
        require(passwordBytes.isNotEmpty()) { "Password required" }
        val maskKey = Crypto.randomBytes(32)
        val labelBytes = "pbkdf2".toByteArray(StandardCharsets.US_ASCII)
        val salt = Crypto.randomBytes(Constants.USER_KDF_SALT_SIZE)
        val userKey = Crypto.pbkdf2HmacSha256(
            passwordBytes,
            salt,
            hardenPbkdf2Iterations(passwordBytes, defaultIterations),
            32,
        )
        val wrapped = Crypto.aesGcmEncrypt(userKey, maskKey, aad)
        val userBlob = ByteArray(1 + labelBytes.size + salt.size + wrapped.size)
        userBlob[0] = labelBytes.size.toByte()
        System.arraycopy(labelBytes, 0, userBlob, 1, labelBytes.size)
        System.arraycopy(salt, 0, userBlob, 1 + labelBytes.size, salt.size)
        System.arraycopy(wrapped, 0, userBlob, 1 + labelBytes.size + salt.size, wrapped.size)
        return MaskKeyPackage(maskKey = maskKey, userBlob = userBlob)
    }

    private fun encodeMaskedPayloadBytes(
        maskKey: ByteArray,
        userBlob: ByteArray,
        plain: ByteArray,
        streamInfo: ByteArray,
    ): ByteArray {
        val masked = maskPayload(maskKey, plain, 0, plain.size, streamInfo)
        val payload = ByteArray(1 + 4 + masked.size)
        payload[0] = 0x02
        writeU32(payload, 1, plain.size)
        System.arraycopy(masked, 0, payload, 5, masked.size)
        return Format.packLengthPrefixed(listOf(userBlob, ByteArray(0), payload))
    }

    private fun encryptAesPayloadBytes(
        payloadBytes: ByteArray,
        password: String,
        metadataBlob: String,
        kdfIterations: Int,
        obfuscate: Boolean,
        fastObf: Boolean,
    ): ByteArray {
        val passwordBytes = passwordBytes(password, required = true)
        val metadataBytes = metadataBlob.toByteArray(StandardCharsets.UTF_8)
        val aad = metadataBytes
        val ephemeralKey = Crypto.randomBytes(32)
        val salt = Crypto.randomBytes(Constants.USER_KDF_SALT_SIZE)
        val userKey = Crypto.pbkdf2HmacSha256(
            passwordBytes,
            salt,
            hardenPbkdf2Iterations(passwordBytes, kdfIterations),
            32,
        )
        val wrapped = Crypto.aesGcmEncrypt(userKey, ephemeralKey, aad)
        val userBlob = ByteArray(salt.size + wrapped.size)
        System.arraycopy(salt, 0, userBlob, 0, salt.size)
        System.arraycopy(wrapped, 0, userBlob, salt.size, wrapped.size)
        val protectedPayload = if (obfuscate) {
            obfuscateBytes(payloadBytes, ephemeralKey, fastObf)
        } else {
            payloadBytes
        }
        val ciphertext = Crypto.aesGcmEncrypt(ephemeralKey, protectedPayload, aad)
        val payload = ByteArray(4 + metadataBytes.size + ciphertext.size)
        writeU32(payload, 0, metadataBytes.size)
        System.arraycopy(metadataBytes, 0, payload, 4, metadataBytes.size)
        System.arraycopy(ciphertext, 0, payload, 4 + metadataBytes.size, ciphertext.size)
        return Format.packLengthPrefixed(listOf(userBlob, ByteArray(0), payload))
    }

    private fun buildHeavyMetadataBlob(
        method: String,
        obfuscationMode: String,
        kdfIterations: Int,
    ): String {
        val metadata = JSONObject()
            .put("ENC-TIME", Instant.now().toString())
            .put("ENC-VERSION", Constants.ENGINE_VERSION)
            .put("ENC-METHOD", method)
            .put("ENC-MASTER", "no")
            .put("ENC-KEM", "none")
            .put("ENC-AEAD", "AES/GCM")
            .put("ENC-KDF", "pbkdf2")
            .put("ENC-OBF", obfuscationMode)
            .put("ENC-KDF-ITER", kdfIterations.toString())
        return Base64Codec.encode(metadata.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeMaskedPayloadBytesFromString(
        input: String,
        passwordBytes: ByteArray,
        maskInfo: ByteArray,
        aad: ByteArray,
        streamInfo: ByteArray,
    ): ByteArray {
        val primary = if (Base64Codec.looksLikeBase64(input)) input else Codec.decode(input)
        val firstAttempt = runCatching { decodeMaskedPayloadBytes(Base64Codec.decode(primary), passwordBytes, maskInfo, aad, streamInfo) }
        if (firstAttempt.isSuccess) {
            return firstAttempt.getOrThrow()
        }
        val secondary = if (primary == input) Codec.decode(input) else input
        if (secondary != primary) {
            val secondAttempt = runCatching {
                decodeMaskedPayloadBytes(Base64Codec.decode(secondary), passwordBytes, maskInfo, aad, streamInfo)
            }
            if (secondAttempt.isSuccess) {
                return secondAttempt.getOrThrow()
            }
        }
        throw IllegalArgumentException("Invalid payload encoding", firstAttempt.exceptionOrNull())
    }

    private fun encodePayloadString(blob: ByteArray): String {
        return Codec.code(Base64Codec.encode(blob))
    }

    private fun decodePayloadString(input: String): ByteArray {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Payload expected" }
        runCatching { Base64Codec.decode(trimmed) }.getOrNull()?.let { return it }
        val deobfuscated = runCatching { Codec.decode(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid payload encoding")
        return Base64Codec.decode(deobfuscated)
    }

    private fun decodeMaskedPayloadBytes(
        blob: ByteArray,
        passwordBytes: ByteArray,
        maskInfo: ByteArray,
        aad: ByteArray,
        streamInfo: ByteArray,
    ): ByteArray {
        val parts = Format.unpackLengthPrefixed(blob, 3)
        val masterBlob = parts[1]
        require(masterBlob.isEmpty()) { "Master-wrapped payloads are not supported on Android" }
        val maskKey = recoverMaskKey(
            userBlob = parts[0],
            passwordBytes = passwordBytes,
            aad = aad,
            defaultIterations = Constants.USER_KDF_ITERATIONS,
        )
        val payload = parts[2]
        require(payload.size >= 5 && payload[0] == 0x02.toByte()) { "Unsupported payload format" }
        val expectedLength = readU32(payload, 1)
        require(expectedLength == payload.size - 5) { "Payload length mismatch" }
        return maskPayload(maskKey, payload, 5, expectedLength, streamInfo)
    }

    private fun recoverMaskKey(
        userBlob: ByteArray,
        passwordBytes: ByteArray,
        aad: ByteArray,
        defaultIterations: Int,
    ): ByteArray {
        require(userBlob.isNotEmpty()) { "Ciphertext missing key transport data" }
        require(passwordBytes.isNotEmpty()) { "Password required to decode this payload" }
        val kdfLabelLength = userBlob[0].toInt() and 0xFF
        val headerLength = 1 + kdfLabelLength + Constants.USER_KDF_SALT_SIZE
        require(userBlob.size >= headerLength) { "Corrupted user key blob" }
        val label = if (kdfLabelLength > 0) {
            String(userBlob.copyOfRange(1, 1 + kdfLabelLength), StandardCharsets.US_ASCII)
        } else {
            "pbkdf2"
        }
        require(label.equals("pbkdf2", ignoreCase = true)) { "Unsupported KDF label: $label" }
        val salt = userBlob.copyOfRange(1 + kdfLabelLength, headerLength)
        val wrapped = userBlob.copyOfRange(headerLength, userBlob.size)
        val userKey = Crypto.pbkdf2HmacSha256(
            passwordBytes,
            salt,
            hardenPbkdf2Iterations(passwordBytes, defaultIterations),
            32,
        )
        return Crypto.aesGcmDecrypt(userKey, wrapped, aad)
    }

    private fun recoverWrappedUserKey(
        userBlob: ByteArray,
        passwordBytes: ByteArray,
        aad: ByteArray,
        defaultIterations: Int,
    ): ByteArray {
        require(userBlob.isNotEmpty()) { "Ciphertext missing key transport data" }
        require(passwordBytes.isNotEmpty()) { "User password required to decrypt this payload" }
        require(userBlob.size >= Constants.USER_KDF_SALT_SIZE + Constants.AEAD_NONCE_LEN + Constants.AEAD_TAG_LEN) {
            "Corrupted user key blob"
        }
        val salt = userBlob.copyOfRange(0, Constants.USER_KDF_SALT_SIZE)
        val wrapped = userBlob.copyOfRange(Constants.USER_KDF_SALT_SIZE, userBlob.size)
        val userKey = Crypto.pbkdf2HmacSha256(
            passwordBytes,
            salt,
            hardenPbkdf2Iterations(passwordBytes, defaultIterations),
            32,
        )
        return Crypto.aesGcmDecrypt(userKey, wrapped, aad)
    }

    private fun maskPayload(
        maskKey: ByteArray,
        payload: ByteArray,
        offset: Int,
        length: Int,
        info: ByteArray,
    ): ByteArray {
        if (length <= 0) {
            return ByteArray(0)
        }
        val output = ByteArray(length)
        if (length <= Constants.HKDF_MAX_LEN) {
            val stream = Crypto.hkdfSha256(maskKey, info, length)
            for (index in 0 until length) {
                output[index] = (payload[offset + index].toInt() xor stream[index].toInt()).toByte()
            }
            return output
        }
        val prk = Crypto.hmacSha256(ByteArray(32), maskKey)
        Crypto.xorHmacStream(prk, info, payload, offset, output, 0, length, 1)
        return output
    }

    private fun obfuscateBytes(data: ByteArray, key: ByteArray, fast: Boolean): ByteArray {
        if (data.isEmpty()) {
            return data
        }
        val output = data.copyOf()
        xorKeystreamInPlace(output, key, Constants.OBF_INFO_MASK)
        if (!fast) {
            val seed = seed64FromBytes(Crypto.hkdfSha256(key, buildInfoWithLength(Constants.OBF_INFO_PERM, data.size), 16))
            reverseInPlace(output)
            permuteInPlace(output, seed)
        }
        return output
    }

    private fun deobfuscateBytes(data: ByteArray, key: ByteArray, fast: Boolean): ByteArray {
        if (data.isEmpty()) {
            return data
        }
        val output = data.copyOf()
        if (!fast) {
            val seed = seed64FromBytes(Crypto.hkdfSha256(key, buildInfoWithLength(Constants.OBF_INFO_PERM, data.size), 16))
            unpermuteInPlace(output, seed)
            reverseInPlace(output)
        }
        xorKeystreamInPlace(output, key, Constants.OBF_INFO_MASK)
        return output
    }

    private fun buildInfoWithLength(prefix: ByteArray, length: Int): ByteArray {
        val output = ByteArray(prefix.size + 8)
        System.arraycopy(prefix, 0, output, 0, prefix.size)
        var remaining = length.toLong()
        for (index in 7 downTo 0) {
            output[prefix.size + index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        return output
    }

    private fun xorKeystreamInPlace(buffer: ByteArray, key: ByteArray, info: ByteArray) {
        if (buffer.isEmpty()) {
            return
        }
        val blockKey = Crypto.hkdfSha256(key, info, 32)
        val lengthBytes = ByteArray(8)
        var remainingLength = buffer.size.toLong()
        for (index in 7 downTo 0) {
            lengthBytes[index] = (remainingLength and 0xFF).toByte()
            remainingLength = remainingLength ushr 8
        }
        var counter = 0L
        var offset = 0
        while (offset < buffer.size) {
            val counterBytes = ByteArray(8)
            var remainingCounter = counter
            for (index in 7 downTo 0) {
                counterBytes[index] = (remainingCounter and 0xFF).toByte()
                remainingCounter = remainingCounter ushr 8
            }
            val data = ByteArray(info.size + lengthBytes.size + counterBytes.size)
            System.arraycopy(info, 0, data, 0, info.size)
            System.arraycopy(lengthBytes, 0, data, info.size, lengthBytes.size)
            System.arraycopy(counterBytes, 0, data, info.size + lengthBytes.size, counterBytes.size)
            val block = Crypto.hmacSha256(blockKey, data)
            val take = minOf(block.size, buffer.size - offset)
            for (index in 0 until take) {
                buffer[offset + index] = (buffer[offset + index].toInt() xor block[index].toInt()).toByte()
            }
            offset += take
            counter += 1
        }
    }

    private fun reverseInPlace(data: ByteArray) {
        var left = 0
        var right = data.lastIndex
        while (left < right) {
            val value = data[left]
            data[left] = data[right]
            data[right] = value
            left += 1
            right -= 1
        }
    }

    private fun unpermuteInPlace(data: ByteArray, seed: Long) {
        val length = data.size
        if (length < 2) {
            return
        }
        val swapI = IntArray(length - 1)
        val swapJ = IntArray(length - 1)
        val state = longArrayOf(seed)
        var index = 0
        for (cursor in length - 1 downTo 1) {
            val random = splitMix64Next(state)
            val j = java.lang.Long.remainderUnsigned(random, cursor.toLong() + 1L).toInt()
            swapI[index] = cursor
            swapJ[index] = j
            index += 1
        }
        for (cursor in index - 1 downTo 0) {
            val i = swapI[cursor]
            val j = swapJ[cursor]
            if (i != j) {
                val value = data[i]
                data[i] = data[j]
                data[j] = value
            }
        }
    }

    private fun permuteInPlace(data: ByteArray, seed: Long) {
        val length = data.size
        if (length < 2) {
            return
        }
        val state = longArrayOf(seed)
        for (cursor in length - 1 downTo 1) {
            val random = splitMix64Next(state)
            val j = java.lang.Long.remainderUnsigned(random, cursor.toLong() + 1L).toInt()
            if (j != cursor) {
                val value = data[cursor]
                data[cursor] = data[j]
                data[j] = value
            }
        }
    }

    private fun splitMix64Next(state: LongArray): Long {
        val z = state[0] + (-7046029254386353131L)
        state[0] = z
        var x = z
        x = (x xor (x ushr 30)) * (-4658895280553007687L)
        x = (x xor (x ushr 27)) * (-7723592293110705685L)
        return x xor (x ushr 31)
    }

    private fun seed64FromBytes(seedBytes: ByteArray): Long {
        if (seedBytes.size < 8) {
            return 0L
        }
        var output = 0L
        val start = seedBytes.size - 8
        for (index in 0 until 8) {
            output = (output shl 8) or (seedBytes[start + index].toLong() and 0xFF)
        }
        return output
    }

    private fun splitMetadata(payload: String): Pair<String, String> {
        val index = payload.indexOf(Constants.META_DELIM)
        return if (index >= 0) {
            payload.substring(0, index) to payload.substring(index + Constants.META_DELIM.length)
        } else {
            "" to payload
        }
    }

    private fun metaValue(metadataBlob: String, key: String): String {
        if (metadataBlob.isBlank()) {
            return ""
        }
        return runCatching {
            JSONObject(String(Base64Codec.decode(metadataBlob), StandardCharsets.UTF_8)).optString(key).trim()
        }.getOrDefault("")
    }

    private fun splitWithDelims(payload: String, delimiter: String, legacyDelimiter: String, label: String): Pair<String, String> {
        val primaryIndex = payload.indexOf(delimiter)
        if (primaryIndex >= 0) {
            return payload.substring(0, primaryIndex) to payload.substring(primaryIndex + delimiter.length)
        }
        val legacyIndex = payload.indexOf(legacyDelimiter)
        if (legacyIndex >= 0) {
            return payload.substring(0, legacyIndex) to payload.substring(legacyIndex + legacyDelimiter.length)
        }
        throw IllegalArgumentException("$label delimiter missing")
    }

    private fun parseMetadataInt(raw: String, fallback: Int): Int {
        return raw.toIntOrNull() ?: fallback
    }

    private fun passwordBytes(password: String, required: Boolean): ByteArray {
        val normalized = password.trim()
        require(!required || normalized.isNotEmpty()) { "Password required" }
        return normalized.toByteArray(StandardCharsets.UTF_8)
    }

    private fun fwxAesIterations(passwordBytes: ByteArray): Int {
        if (Constants.TEST_KDF_OVERRIDE) {
            return Constants.FWXAES_PBKDF2_ITERS
        }
        return if (passwordBytes.isNotEmpty() && passwordBytes.size < Constants.SHORT_PASSWORD_MIN) {
            max(Constants.FWXAES_PBKDF2_ITERS, Constants.SHORT_PBKDF2_ITERS)
        } else {
            Constants.FWXAES_PBKDF2_ITERS
        }
    }

    private fun hardenPbkdf2Iterations(passwordBytes: ByteArray, iterations: Int): Int {
        if (Constants.TEST_KDF_OVERRIDE) {
            return iterations
        }
        return if (passwordBytes.isNotEmpty() && passwordBytes.size < Constants.SHORT_PASSWORD_MIN) {
            max(iterations, Constants.SHORT_PBKDF2_ITERS)
        } else {
            iterations
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
