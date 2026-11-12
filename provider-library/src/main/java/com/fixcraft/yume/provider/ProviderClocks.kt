package com.fixcraft.yume.provider

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun interface ProviderClock {
    /** Null suspends authority. A device wall clock alone is not trusted time. */
    fun now(): Reading?

    /** The monotonic clock of [Reading.elapsedMillis], available without trusted time. */
    fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    data class Reading(val seconds: Long, val elapsedMillis: Long)
}

/** Where [ProviderHttp] reports each authenticated response's `Date` and round trip. */
fun interface ProviderTimeFeed {
    fun observe(date: String?, sentElapsedMillis: Long, receivedElapsedMillis: Long)
}

/**
 * The process-wide trusted clock. Android 13 and later read Android's network
 * time, which user changes to the wall clock do not affect. Earlier releases
 * anchor to provider responses through [ProviderTimeAnchor]. The source is
 * chosen once. docs/PROVIDER_CLIENT.md, "Trusted time", owns the rule.
 */
object AndroidProviderClock : ProviderClock, ProviderTimeFeed {
    private val networkClock = Build.VERSION.SDK_INT >= 33
    @Volatile private var anchor = ProviderTimeAnchor(boot = null, storage = null)
    private var attached = false

    /**
     * Keeps the anchor in [context]'s no-backup directory for the rest of this
     * boot. [ProviderClient] calls it before any provider request. The first
     * call wins, and a later one changes nothing.
     */
    fun attach(context: Context) {
        if (networkClock) return
        synchronized(this) {
            if (attached) return
            attached = true
            val application = context.applicationContext
            anchor = ProviderTimeAnchor(bootCount(application),
                AnchorFile(File(application.noBackupFilesDir, "provider-time-anchor")))
        }
    }

    override fun now(): ProviderClock.Reading? =
        if (networkClock) networkTime() else anchor.now(SystemClock.elapsedRealtime())

    override fun observe(date: String?, sentElapsedMillis: Long, receivedElapsedMillis: Long) {
        if (!networkClock) anchor.observe(date, sentElapsedMillis, receivedElapsedMillis)
    }

    private fun networkTime(): ProviderClock.Reading? {
        if (Build.VERSION.SDK_INT < 33) return null // Unreachable. Lint reads this guard, not networkClock.
        return try {
            ProviderClock.Reading(SystemClock.currentNetworkTimeClock().millis() / 1000, SystemClock.elapsedRealtime())
        } catch (_: java.time.DateTimeException) { null }
    }

    /** Constant for the life of a process, so it is read once. Unknown means not persisted. */
    private fun bootCount(context: Context): Long? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (_: Settings.SettingNotFoundException) { null }

    /** One line, `1 BOOT UTC_MILLIS ELAPSED_MILLIS`, replaced atomically. Not secret. */
    private class AnchorFile(private val file: File) : ProviderTimeAnchor.Storage {
        override fun read(): ProviderTimeAnchor.Saved? = try {
            if (!file.isFile || file.length() > 128) null else {
                val fields = file.readText(Charsets.US_ASCII).trim().split(' ')
                if (fields.size != 4 || fields[0] != "1") null
                else ProviderTimeAnchor.Saved(fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
            }
        } catch (_: IOException) { null } catch (_: NumberFormatException) { null }

        // A lost write costs only the anchor's survival across a process restart.
        override fun write(saved: ProviderTimeAnchor.Saved) {
            val staged = File(file.parentFile, file.name + ".part")
            try {
                staged.writeText("1 ${saved.boot} ${saved.utcMillis} ${saved.elapsedMillis}\n", Charsets.US_ASCII)
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                staged.delete()
            }
        }
    }
}
