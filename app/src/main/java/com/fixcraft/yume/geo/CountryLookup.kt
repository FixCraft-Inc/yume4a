package com.fixcraft.yume.geo

import android.content.Context
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.i18n.AppLocaleManager
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

data class CountryMatch(
    val isoCode: String,
    val displayName: String,
    val flagEmoji: String,
)

private data class CountryNames(
    val english: String,
    val russian: String,
    val japanese: String,
)

private data class CountryDb(
    val starts: IntArray,
    val ends: IntArray,
    val isoPacked: IntArray,
    val names: Map<String, CountryNames>,
)

object CountryLookup {
    private const val RANGES_ASSET = "geoip_country_ipv4.db"
    private const val NAMES_ASSET = "geoip_country_names.tsv"
    private val magic = byteArrayOf('Y'.code.toByte(), 'G'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    @Volatile
    private var cachedDb: CountryDb? = null

    fun lookup(context: Context, ipAddress: String?, language: AppLanguage): CountryMatch? {
        val normalized = ipAddress?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() as? Inet4Address ?: return null
        val ip = ipv4ToInt(address)
        val db = cachedDb ?: synchronized(this) {
            cachedDb ?: runCatching { loadDb(context.applicationContext) }.getOrNull()?.also { cachedDb = it }
        } ?: return null
        val index = binarySearch(db, ip)
        if (index < 0) {
            return null
        }
        val iso = unpackIso(db.isoPacked[index])
        val names = db.names[iso]
        val displayName = when (AppLocaleManager.resolveLanguage(context, language)) {
            ResolvedAppLanguage.English -> names?.english
            ResolvedAppLanguage.Russian -> names?.russian ?: names?.english
            ResolvedAppLanguage.Japanese -> names?.japanese ?: names?.english
            is ResolvedAppLanguage.AutoTranslated -> names?.english
        } ?: iso
        return CountryMatch(
            isoCode = iso,
            displayName = displayName,
            flagEmoji = isoToFlagEmoji(iso),
        )
    }

    private fun loadDb(context: Context): CountryDb {
        val names = linkedMapOf<String, CountryNames>()
        context.assets.open(NAMES_ASSET).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split('\t')
                if (parts.size < 4) return@forEach
                names[parts[0]] = CountryNames(
                    english = unescape(parts[1]),
                    russian = unescape(parts[2]),
                    japanese = unescape(parts[3]),
                )
            }
        }

        context.assets.open(RANGES_ASSET).use { input ->
            DataInputStream(input).use { data ->
                val header = ByteArray(4)
                data.readFully(header)
                require(header.contentEquals(magic)) { "Invalid country DB header" }
                val count = data.readInt()
                val starts = IntArray(count)
                val ends = IntArray(count)
                val isoPacked = IntArray(count)
                repeat(count) { index ->
                    starts[index] = data.readInt()
                    ends[index] = data.readInt()
                    val first = data.readUnsignedByte()
                    val second = data.readUnsignedByte()
                    isoPacked[index] = (first shl 8) or second
                }
                return CountryDb(starts = starts, ends = ends, isoPacked = isoPacked, names = names)
            }
        }
    }

    private fun binarySearch(db: CountryDb, ip: Int): Int {
        var low = 0
        var high = db.starts.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val start = db.starts[mid].toLong() and 0xFFFF_FFFFL
            val end = db.ends[mid].toLong() and 0xFFFF_FFFFL
            val target = ip.toLong() and 0xFFFF_FFFFL
            when {
                target < start -> high = mid - 1
                target > end -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun unpackIso(packed: Int): String {
        val first = ((packed ushr 8) and 0xFF).toChar()
        val second = (packed and 0xFF).toChar()
        return "$first$second"
    }

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun isoToFlagEmoji(isoCode: String): String {
        val normalized = isoCode.uppercase(Locale.ROOT)
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) {
            return ""
        }
        val first = Character.codePointAt(normalized, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(normalized, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun unescape(value: String): String {
        return value
            .replace("\\t", "\t")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }
}
