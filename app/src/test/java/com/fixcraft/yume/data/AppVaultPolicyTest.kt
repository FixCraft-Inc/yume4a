package com.fixcraft.yume.data

import com.fixcraft.basefwx.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVaultPolicyTest {
    @Test
    fun passwordPolicyIsBaseFwxsMinimumInUtf8Bytes() {
        // The app's check and the BaseFWX copy it ships must not drift apart.
        assertEquals(Constants.MINIMUM_PASSWORD_LENGTH, AppVaultManager.MIN_PASSWORD_UTF8_BYTES)
        assertFalse(AppVaultManager.passwordMeetsPolicy("123456789"))
        assertTrue(AppVaultManager.passwordMeetsPolicy("1234567890"))
        // Five two-byte characters meet the ten-byte policy.
        assertTrue(AppVaultManager.passwordMeetsPolicy("ééééé"))
        assertFalse(AppVaultManager.passwordMeetsPolicy("éééé"))
    }

    @Test
    fun kitCodesMustBeAscii() {
        assertArrayEquals("AB-12".toByteArray(), KitStore.asciiCode("AB-12".toCharArray()))
        // U+0141 would narrow to 0x41 ('A') without the check.
        assertNull(KitStore.asciiCode("ŁB-12".toCharArray()))
        assertNull(KitStore.asciiCode("AB-1²".toCharArray()))
    }
}
