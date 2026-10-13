package com.fixcraft.yume.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The packaged libyume loads, is the ABI this adapter was written for, and fails closed. */
@RunWith(AndroidJUnit4::class)
class YumeLibraryInstrumentedTest {
    @Test
    fun theLibraryDescribesItself() {
        val build = YumeLibrary.build()
        assertNotNull(build)
        assertEquals(1, build!!.abiVersion)
        assertEquals(1, build.configSchema)
        assertEquals("YTP/1", build.protocolName)
        assertTrue(build.productVersion.isNotBlank())
        assertEquals("ok", YumeLibrary.statusName(YumeStatusCode.OK).lowercase())
    }

    @Test
    fun aDocumentTheStrictParserRefusesIsRefusedWithItsPlace() {
        val refused = assertThrows(YumeException::class.java) {
            YumeLibrary.checkConfig("""{"schema": 1, "role": "client", "unknown": true}""".toByteArray())
        }
        assertTrue(refused.status != YumeStatusCode.OK)
        assertTrue(refused.detail.isNotBlank())
        assertThrows(YumeException::class.java) { YumeLibrary.checkConfig("not json".toByteArray()) }
    }

    @Test
    fun aFileThatIsNoKitAndACodeThatIsNoCodeAreRefused() {
        val notAKit = ByteArray(4096) { it.toByte() }
        val wellFormed = "7777777777777777777777777".toByteArray()
        val wrong = assertThrows(YumeException::class.java) { YumeLibrary.openKit(notAKit, wellFormed) }
        assertEquals(YumeStatusCode.PERMISSION_DENIED, wrong.status)
        val short = assertThrows(YumeException::class.java) { YumeLibrary.openKit(notAKit, "7777".toByteArray()) }
        assertEquals(YumeStatusCode.INVALID_ARGUMENT, short.status)
    }

    @Test
    fun anEndpointWithoutSocketProtectionIsRefused() {
        // Without protection the transport would route into the VPN itself.
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val status = IntArray(1)
        val result = arrayOfNulls<String>(2)
        val handle = YumeNative.nativeCreate(directory.absolutePath, "{}".toByteArray(), null, status, result)
        assertEquals(0L, handle)
        assertEquals(YumeStatusCode.INVALID_ARGUMENT, status[0])
        assertTrue(result[1].orEmpty().contains("protector"))
    }

    @Test
    fun aStatusSnapshotNeedsTheWholeLayout() {
        assertEquals(18, YumeNative.STATUS_NUMBERS)
        assertEquals(YumeStatusCode.INVALID_ARGUMENT, YumeNative.nativeStatus(0L, LongArray(YumeNative.STATUS_NUMBERS), arrayOfNulls(2)))
    }

    @Test
    fun aClientWithoutItsCredentialFilesIsNotCreated() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        assertThrows(YumeException::class.java) {
            YumeClient.create(directory, "{}".toByteArray()) { false }
        }
    }
}
