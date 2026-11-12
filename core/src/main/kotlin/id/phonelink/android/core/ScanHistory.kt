package id.phonelink.android.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One scanned code kept on the phone. [time] is epoch milliseconds. */
@Serializable
data class ScanRecord(val text: String, val format: String, val time: Long)

/** The barcode history, newest first, kept to the last [MAX] codes. */
object ScanHistory {
    const val MAX = 200

    private val json = Json { ignoreUnknownKeys = true }

    fun add(history: List<ScanRecord>, record: ScanRecord, max: Int = MAX): List<ScanRecord> = trim(listOf(record) + history, max)

    fun trim(history: List<ScanRecord>, max: Int = MAX): List<ScanRecord> = history.take(max.coerceAtLeast(0))

    fun encode(history: List<ScanRecord>): String = json.encodeToString(history)

    fun decode(text: String?): List<ScanRecord> =
        if (text.isNullOrBlank()) emptyList() else runCatching { trim(json.decodeFromString<List<ScanRecord>>(text)) }.getOrDefault(emptyList())

    private fun stamp(time: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(time))

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Oldest first, with a header row, for spreadsheets. */
    fun toCsv(history: List<ScanRecord>, zone: ZoneId = ZoneId.systemDefault()): String =
        (listOf("Time,Type,Value") + history.reversed().map { listOf(stamp(it.time, zone), it.format, it.text).joinToString(",", transform = ::csvField) })
            .joinToString("\r\n", postfix = "\r\n")

    /** Oldest first, one code per line. */
    fun toText(history: List<ScanRecord>): String = history.reversed().joinToString("\n") { it.text }
}
