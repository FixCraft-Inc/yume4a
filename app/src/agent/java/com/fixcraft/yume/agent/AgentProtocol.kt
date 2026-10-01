package com.fixcraft.yume.agent

internal object AgentProtocol {
    const val ACTION_SNAPSHOT = "com.fixcraft.yume.agent.SNAPSHOT"
    const val ACTION_KITS = "com.fixcraft.yume.agent.KITS"
    const val ACTION_CONNECT = "com.fixcraft.yume.agent.CONNECT"
    const val ACTION_DISCONNECT = "com.fixcraft.yume.agent.DISCONNECT"
    const val ACTION_IMPORT_KIT = "com.fixcraft.yume.agent.IMPORT_KIT"
    const val ACTION_DELETE_KIT = "com.fixcraft.yume.agent.DELETE_KIT"
    const val ACTION_NETWORK_BENCH = "com.fixcraft.yume.agent.NETWORK_BENCH"

    const val EXTRA_COMMAND_ID = "command_id"
    const val EXTRA_KIT_ID = "kit_id"
    const val EXTRA_URL = "url"
    const val EXTRA_MAX_BYTES = "max_bytes"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"

    const val RELATIVE_DIR = "adb-agent"
    const val SNAPSHOT_FILE = "snapshot.json"
    const val CONSENT_FILE = "consent.json"
    // One-shot inputs of an agent build for transport v2. They are removed
    // on start so no old secret stays behind.
    val LEGACY_ONE_SHOT_FILES = arrayOf(
        "import.yss",
        "import-password",
        "auth-password",
    )

    fun resultFile(commandId: String): String = "result-$commandId.json"
    fun importKitFile(commandId: String): String = "import-$commandId.kit"
    fun importCodeFile(commandId: String): String = "import-code-$commandId"
}
