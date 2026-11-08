package com.fixcraft.yume.provider

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The library test APK's launcher; all callback material in this fixture is synthetic. */
class ProviderCallbackLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContentView(TextView(this).apply { text = "Synthetic callback task" })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        returnedIntent = intent
        returned?.countDown()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile var current: ProviderCallbackLauncherActivity? = null
        @Volatile var returned: CountDownLatch? = null
        @Volatile var returnedIntent: Intent? = null
    }
}

/** Exercises actual Activity delivery and launcher return, with no browser or account. */
@RunWith(AndroidJUnit4::class)
class ProviderCallbackActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var receiver: AutoCloseable? = null

    @Before fun launch() {
        ProviderCallbackLauncherActivity.returned = null
        ProviderCallbackLauncherActivity.returnedIntent = null
        instrumentation.startActivitySync(Intent(context, ProviderCallbackLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertNotNull(ProviderCallbackLauncherActivity.current)
    }

    @After fun close() {
        receiver?.close()
        instrumentation.runOnMainSync { ProviderCallbackLauncherActivity.current?.finish() }
        instrumentation.waitForIdleSync()
        ProviderCallbackLauncherActivity.returned = null
        ProviderCallbackLauncherActivity.returnedIntent = null
    }

    private fun deliver(uri: String) {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, ProviderCallbackActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
                .putExtra("synthetic-extra", "must-not-return")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun liveCallbackReturnsToTheLauncherWithoutCallbackData() {
        val received = mutableListOf<String>()
        receiver = ProviderCallbacks.attach { synchronized(received) { received += it } }
        val returned = CountDownLatch(1)
        ProviderCallbackLauncherActivity.returned = returned
        val callback = "https://client.example/oauth/callback?state=synthetic-state&code=synthetic-code"
        deliver(callback)
        assertTrue("Accepted callback did not return to the launcher", returned.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(callback), synchronized(received) { received.toList() })
        val intent = ProviderCallbackLauncherActivity.returnedIntent!!
        assertNull(intent.data)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun coldDeliveryDoesNotRelaunchTheApplication() {
        val returned = CountDownLatch(1)
        ProviderCallbackLauncherActivity.returned = returned
        deliver("https://client.example/oauth/callback?state=synthetic-cold&code=synthetic-code")
        assertFalse("Cold callback relaunched the application", returned.await(1, TimeUnit.SECONDS))
        assertNull(ProviderCallbackLauncherActivity.returnedIntent)
    }
}
