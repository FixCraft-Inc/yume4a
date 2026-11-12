package com.fixcraft.yume.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback rule's decision (docs/PROVIDER_CLIENT.md, "Browser authentication").
 * Whether Android resolves and verifies a real link is an emulator and device check.
 */
class ProviderAppLinksTest {
    private val app = "com.fixcraft.yume"

    @Test
    fun android12AndLaterNeedVerificationAndResolutionToThisApp() {
        assertTrue(ProviderAppLinks.reachesOnlyThis(verified = true, resolvedPackage = app, packageName = app))
        assertFalse(ProviderAppLinks.reachesOnlyThis(verified = false, resolvedPackage = app, packageName = app))
        assertFalse(ProviderAppLinks.reachesOnlyThis(verified = true, resolvedPackage = "android", packageName = app))
    }

    @Test
    fun android9To11ResolveTheCallbackToThisAppAlone() {
        assertTrue(ProviderAppLinks.reachesOnlyThis(verified = null, resolvedPackage = app, packageName = app))
        // The chooser, a browser, another app or nothing at all is refused.
        for (other in listOf("android", "com.android.chrome", "com.example.other", null)) {
            assertFalse(ProviderAppLinks.reachesOnlyThis(verified = null, resolvedPackage = other, packageName = app))
        }
    }
}
