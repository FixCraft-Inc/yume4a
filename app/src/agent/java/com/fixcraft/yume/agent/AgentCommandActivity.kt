package com.fixcraft.yume.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.fixcraft.yume.BuildConfig

class AgentCommandActivity : Activity() {
    private var dispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.ENABLE_ADB_TEST_CONTROL) { "ADB test control is disabled" }
    }

    override fun onResume() {
        super.onResume()
        if (dispatched) return
        dispatched = true
        val commandId = intent.getStringExtra(AgentProtocol.EXTRA_COMMAND_ID)
            ?.takeIf { COMMAND_ID.matches(it) }
        if (intent.action !in ALLOWED_ACTIONS || commandId == null) {
            finish()
            return
        }
        val serviceIntent = Intent(this, AgentCommandService::class.java).apply {
            action = intent.action
            putExtra(AgentProtocol.EXTRA_COMMAND_ID, commandId)
            copyBoundedString(intent, this, AgentProtocol.EXTRA_PROFILE_ID, 128)
            copyBoundedString(intent, this, AgentProtocol.EXTRA_MODE, 16)
            copyBoundedString(intent, this, AgentProtocol.EXTRA_URL, 2_048)
            putExtra(
                AgentProtocol.EXTRA_MAX_BYTES,
                intent.getLongExtra(AgentProtocol.EXTRA_MAX_BYTES, DEFAULT_MAX_BYTES)
                    .coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES),
            )
            putExtra(
                AgentProtocol.EXTRA_TIMEOUT_MS,
                intent.getLongExtra(AgentProtocol.EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
                    .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
            )
        }
        runCatching { startForegroundService(serviceIntent) }
            .onFailure { throwable ->
                AgentTelemetry.start(this)
                AgentTelemetry.complete(
                    this,
                    commandId,
                    intent.action.orEmpty(),
                    false,
                    throwable.message ?: throwable.javaClass.simpleName,
                )
            }
        finish()
    }

    private fun copyBoundedString(source: Intent, target: Intent, key: String, maxLength: Int) {
        source.getStringExtra(key)?.take(maxLength)?.let { target.putExtra(key, it) }
    }

    private companion object {
        val ALLOWED_ACTIONS = setOf(
            AgentProtocol.ACTION_SNAPSHOT,
            AgentProtocol.ACTION_PROFILES,
            AgentProtocol.ACTION_CONNECT,
            AgentProtocol.ACTION_DISCONNECT,
            AgentProtocol.ACTION_IMPORT_BUNDLE,
            AgentProtocol.ACTION_SELF_TEST,
            AgentProtocol.ACTION_NETWORK_BENCH,
        )
        val COMMAND_ID = Regex("^[A-Za-z0-9._-]{1,96}$")
        const val MIN_MAX_BYTES = 1_024L
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
        const val MAX_MAX_BYTES = 1024L * 1024L * 1024L
        const val MIN_TIMEOUT_MS = 1_000L
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 180_000L
    }
}
