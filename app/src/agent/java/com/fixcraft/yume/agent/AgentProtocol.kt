package com.fixcraft.yume.agent

internal object AgentProtocol {
    const val ACTION_SNAPSHOT = "com.fixcraft.yume.agent.SNAPSHOT"
    const val ACTION_PROFILES = "com.fixcraft.yume.agent.PROFILES"
    const val ACTION_CONNECT = "com.fixcraft.yume.agent.CONNECT"
    const val ACTION_DISCONNECT = "com.fixcraft.yume.agent.DISCONNECT"
    const val ACTION_IMPORT_BUNDLE = "com.fixcraft.yume.agent.IMPORT_BUNDLE"
    const val ACTION_SELF_TEST = "com.fixcraft.yume.agent.SELF_TEST"
    const val ACTION_NETWORK_BENCH = "com.fixcraft.yume.agent.NETWORK_BENCH"

    const val EXTRA_COMMAND_ID = "command_id"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_MODE = "mode"
    const val EXTRA_URL = "url"
    const val EXTRA_MAX_BYTES = "max_bytes"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"

    const val RELATIVE_DIR = "adb-agent"
    const val SNAPSHOT_FILE = "snapshot.json"
    const val CONSENT_FILE = "consent.json"
    const val SELF_TEST_REPORT_FILE = "self-test.txt"
    val LEGACY_ONE_SHOT_FILES = arrayOf(
        "import.yss",
        "import-password",
        "auth-password",
    )

    fun resultFile(commandId: String): String = "result-$commandId.json"
    fun importBundleFile(commandId: String): String = "import-$commandId.yss"
    fun importPasswordFile(commandId: String): String = "import-password-$commandId"
    fun authPasswordFile(commandId: String): String = "auth-password-$commandId"
}
