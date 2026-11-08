package com.fixcraft.yume.consumer

/** Consumer storage and transient native-session ownership. No settings or UI. */
interface ServiceAdapter<S : AutoCloseable> {
    fun ensureReady(): Boolean
    fun isUnlocked(): Boolean
    /** Missing is null. Unreadable or locked state must throw, never become manual. */
    fun readSelection(): ByteArray?
    /** Atomic, encrypted replacement. The input remains caller-owned. */
    fun writeSelection(bytes: ByteArray)
    /** Returns an owned session. The caller closes it after native borrowers return. */
    fun openSession(bundle: ByteArray): S
}
