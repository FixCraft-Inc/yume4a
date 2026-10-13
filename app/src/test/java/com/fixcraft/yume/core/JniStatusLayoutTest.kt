package com.fixcraft.yume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The status snapshot crosses JNI as a positional long array. The adapter
 * fills it by named indexes and YumeClient reads it by YumeNative's indexes.
 * This ties the two sources together, so a reordered, missing or renamed
 * field fails here instead of showing one counter as another.
 */
class JniStatusLayoutTest {
    // YumeStatus property -> yume_endpoint_status member (include/yume/yume.h).
    private val contract = mapOf(
        "state" to "state",
        "session" to "session",
        "lastFailure" to "last_failure",
        "epochBytes" to "epoch_bytes",
        "connectedMs" to "connected_ms",
        "retryMs" to "retry_ms",
        "sessions" to "sessions",
        "failedAttempts" to "failed_attempts",
        "idleEpochRotation" to "idle_epoch_rotation",
        "payloadBytesSent" to "payload_bytes_sent",
        "payloadBytesReceived" to "payload_bytes_received",
        "recordBytesSent" to "record_bytes_sent",
        "recordBytesReceived" to "record_bytes_received",
        "maxQueuedBytes" to "max_queued_bytes",
        "maxEpochBytes" to "max_epoch_bytes",
        "creditReturnsPerWindow" to "credit_returns_per_window",
        "tcpConnections" to "device_tcp_connections",
        "udpDestinations" to "device_udp_destinations",
    )

    private fun source(path: String) = File(path).readText()

    @Test
    fun theAdapterAndTheKotlinReaderAgreeOnEverySlot() {
        val cpp = source("src/main/cpp/yume_android_jni.cpp")
        val native = source("src/main/java/com/fixcraft/yume/core/YumeNative.kt")
        val client = source("src/main/java/com/fixcraft/yume/core/YumeClient.kt")

        // C++: the enum's indexes and the member written at each index.
        val cppIndex = Regex("""(kStatus\w+) = (\d+),""").findAll(cpp)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val count = cppIndex.getValue("kStatusCount")
        val cppField = Regex("""values\[(kStatus\w+)] = static_cast<jlong>\(status\.(\w+)\);""").findAll(cpp)
            .associate { cppIndex.getValue(it.groupValues[1]) to it.groupValues[2] }

        // Kotlin: YumeNative's indexes and the property read from each.
        val kotlinIndex = Regex("""const val (STATUS_\w+) = (\d+)""").findAll(native)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlinRead = Regex("""(\w+) = numbers\[YumeNative\.(STATUS_\w+)]""").findAll(client)
            .associate { it.groupValues[1] to kotlinIndex.getValue(it.groupValues[2]) }

        assertEquals(count, kotlinIndex.getValue("STATUS_NUMBERS"))
        assertEquals("every slot is written once", (0 until count).toSet(), cppField.keys)
        assertEquals(contract.keys, kotlinRead.keys)
        for ((property, member) in contract) {
            val slot = kotlinRead.getValue(property)
            assertEquals("$property reads slot $slot", member, cppField[slot])
        }
        assertTrue(kotlinRead.values.toSet().size == count)
    }

    @Test
    fun everyLibraryStatusCodeHasItsValue() {
        val header = File("../../../yume/include/yume/yume.h").takeIf { it.isFile } ?: return
        val codes = Regex("""YUME_STATUS_(\w+) = (-?\d+)""").findAll(header.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlin = YumeStatusCode::class.java.declaredFields
            // The Compose compiler adds a generated `$stable` field to every class.
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && '$' !in it.name }
            .associate { it.name to it.getInt(null) }
        assertEquals(codes, kotlin)
    }
}
