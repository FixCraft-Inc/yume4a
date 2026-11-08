package com.fixcraft.yume.provider

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Process-local callback delivery. A consumer owns registration and job scope. */
object ProviderCallbacks {
    private val lock = Any()
    private var receiver: ((String) -> Unit)? = null

    fun attach(callback: (String) -> Unit): AutoCloseable {
        synchronized(lock) {
            check(receiver == null) { "Provider callback already owned" }
            receiver = callback
        }
        return AutoCloseable { synchronized(lock) { if (receiver === callback) receiver = null } }
    }

    /** True when a live process owner received the callback. */
    fun deliver(uri: String): Boolean {
        if (uri.length > 16384) return false
        val callback = synchronized(lock) { receiver } ?: return false
        callback(uri)
        return true
    }
}

/** Contains no verifier, token or saved browser state. Cold delivery is ignored. */
class ProviderCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        deliver(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliver(intent)
    }

    private fun deliver(incoming: Intent) {
        val callback = if (incoming.action == Intent.ACTION_VIEW) incoming.dataString else null
        incoming.data = null
        incoming.replaceExtras(null as Bundle?)
        setIntent(Intent())
        var delivered = false
        try { delivered = callback?.let(ProviderCallbacks::deliver) == true }
        finally {
            if (delivered) returnToApplication()
            finish()
        }
    }

    /**
     * The sign-in Custom Tab stays above the application's own activity in its
     * task. Clearing back to the launcher activity closes it once the process
     * owner holds the result. The relaunch carries no callback data.
     */
    private fun returnToApplication() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // The callback is consumed at entry and never restored after process death.
    }
}
