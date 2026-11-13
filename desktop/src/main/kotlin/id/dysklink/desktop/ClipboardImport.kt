@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Another clipboard app whose history can be brought over, as the Mac's Import does. */
enum class ClipImportSource(val label: String) { Ditto("Ditto"), CopyQ("CopyQ") }

class ClipImportException(message: String) : Exception(message)

/**
 * Reads Ditto's database (Windows) and CopyQ's items (through its `copyq` command, Linux and Windows). Only reads,
 * never changes either. Ditto's SQLite is opened with the system's own library over JNA: winsqlite3.dll comes with
 * Windows 10 and 11, and libsqlite3 with nearly every Linux, so no SQLite driver ships with DyskLink.
 */
object ClipboardImport {
    /** Where Ditto keeps its database, installed or portable. */
    fun dittoCandidates(env: (String) -> String? = System::getenv): List<File> = listOfNotNull(
        env("APPDATA")?.let { File(it, "Ditto/Ditto.db") },
        env("LOCALAPPDATA")?.let { File(it, "Ditto/Ditto.db") },
        env("ProgramFiles")?.let { File(it, "Ditto/Ditto.db") },
        env("ProgramFiles(x86)")?.let { File(it, "Ditto/Ditto.db") },
    )

    fun findDitto(): File? = dittoCandidates().firstOrNull { it.isFile }

    fun copyqCommand(): String? = when {
        Typing.onPath(if (Platform.isWindows) "copyq.exe" else "copyq") -> "copyq"
        Platform.isWindows -> listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "CopyQ/copyq.exe") }.firstOrNull { it.isFile }?.path
        else -> null
    }

    /** Which imports this PC can offer now. */
    fun available(): List<ClipImportSource> = buildList {
        if (Platform.isWindows) add(ClipImportSource.Ditto)
        if (copyqCommand() != null) add(ClipImportSource.CopyQ)
    }

    // Ditto

    private interface Sqlite : Library {
        fun sqlite3_open_v2(filename: String, db: PointerByReference, flags: Int, vfs: String?): Int
        fun sqlite3_prepare_v2(db: Pointer, sql: String, bytes: Int, statement: PointerByReference, tail: Pointer?): Int
        fun sqlite3_step(statement: Pointer): Int
        fun sqlite3_column_int64(statement: Pointer, column: Int): Long
        fun sqlite3_column_text(statement: Pointer, column: Int): Pointer?
        fun sqlite3_column_blob(statement: Pointer, column: Int): Pointer?
        fun sqlite3_column_bytes(statement: Pointer, column: Int): Int
        fun sqlite3_finalize(statement: Pointer): Int
        fun sqlite3_close(db: Pointer): Int
    }

    private val sqlite: Sqlite? by lazy {
        val names = if (Platform.isWindows) listOf("winsqlite3", "sqlite3") else listOf("sqlite3", "libsqlite3.so.0")
        val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        names.firstNotNullOfOrNull { runCatching { Native.load(it, Sqlite::class.java, options) }.getOrNull() }
    }

    private const val SQLITE_OK = 0
    private const val SQLITE_ROW = 100
    private const val OPEN_READONLY = 0x1
    private const val OPEN_URI = 0x40

    /** A file: URI with immutable=1, so SQLite writes no journal next to Ditto's database. */
    fun sqliteUri(file: File): String {
        val path = file.absolutePath.replace('\\', '/').let { if (it.startsWith("/")) it else "/$it" }
        val encoded = path.replace("%", "%25").replace(" ", "%20").replace("#", "%23").replace("?", "%3F")
        return "file://$encoded?immutable=1"
    }

    fun readDitto(file: File): List<ImportedClip> {
        val lib = sqlite ?: throw ClipImportException("This PC has no SQLite library to read Ditto's database with.")
        if (!file.isFile) throw ClipImportException("${file.path} wasn't found.")
        val dbRef = PointerByReference()
        if (lib.sqlite3_open_v2(sqliteUri(file), dbRef, OPEN_READONLY or OPEN_URI, null) != SQLITE_OK) {
            dbRef.value?.let { lib.sqlite3_close(it) }
            throw ClipImportException("Ditto's database couldn't be opened.")
        }
        val db = dbRef.value
        try {
            val query = "SELECT m.lID, m.lDate, m.mText, d.strClipBoardFormat, d.ooData FROM Main m " +
                "LEFT JOIN Data d ON d.lParentID = m.lID WHERE IFNULL(m.bIsGroup, 0) = 0 ORDER BY m.lDate DESC, m.lID DESC"
            val stRef = PointerByReference()
            if (lib.sqlite3_prepare_v2(db, query, -1, stRef, null) != SQLITE_OK || stRef.value == null) {
                throw ClipImportException("This isn't a Ditto database.")
            }
            val st = stRef.value
            val rows = LinkedHashMap<Long, DittoRow>()
            try {
                while (lib.sqlite3_step(st) == SQLITE_ROW) {
                    val id = lib.sqlite3_column_int64(st, 0)
                    val row = rows.getOrPut(id) {
                        val seconds = lib.sqlite3_column_int64(st, 1)
                        DittoRow(if (seconds > 0) seconds * 1000 else null, lib.sqlite3_column_text(st, 2)?.getString(0, "UTF-8"))
                    }
                    val format = lib.sqlite3_column_text(st, 3)?.getString(0, "UTF-8") ?: continue
                    val size = lib.sqlite3_column_bytes(st, 4)
                    val blob = lib.sqlite3_column_blob(st, 4) ?: continue
                    row.formats[format] = blob.getByteArray(0, size)
                }
            } finally {
                lib.sqlite3_finalize(st)
            }
            return rows.values.mapNotNull(::dittoClip)
        } finally {
            lib.sqlite3_close(db)
        }
    }

    class DittoRow(val date: Long?, val fallback: String?, val formats: MutableMap<String, ByteArray> = mutableMapOf())

    fun dittoClip(row: DittoRow): ImportedClip? {
        dittoText(row.formats)?.let { return ImportedClip(text = it, date = row.date) }
        dittoImage(row.formats)?.let { return ImportedClip(png = it, date = row.date) }
        val text = row.fallback
        return if (!text.isNullOrEmpty() && row.formats.isEmpty()) ImportedClip(text = text, date = row.date) else null
    }

    /** Windows text formats end with a zero. */
    fun dittoText(formats: Map<String, ByteArray>): String? {
        formats["CF_UNICODETEXT"]?.let { return String(it, Charsets.UTF_16LE).trimEnd('\u0000') }
        formats["CF_TEXT"]?.let { return String(it, charset("windows-1252")).trimEnd('\u0000') }
        return null
    }

    fun dittoImage(formats: Map<String, ByteArray>): ByteArray? {
        formats["PNG"]?.let { png -> if (runCatching { ImageIO.read(png.inputStream()) }.getOrNull() != null) return png }
        val dib = formats["CF_DIB"] ?: formats["CF_DIBV5"] ?: return null
        val bmp = bitmapFile(dib) ?: return null
        val image = runCatching { ImageIO.read(bmp.inputStream()) }.getOrNull() ?: return null
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A DIB is a BMP file without its 14-byte header, so the header is put back. */
    fun bitmapFile(dib: ByteArray): ByteArray? {
        if (dib.size <= 40) return null
        val b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = b.getInt(0)
        val bitCount = b.getShort(14).toInt()
        val compression = b.getInt(16)
        val used = b.getInt(32)
        if (headerSize < 40 || headerSize >= dib.size) return null
        val colors = if (used > 0) used else if (bitCount <= 8) 1 shl bitCount else 0
        val masks = if (compression == 3 && headerSize == 40) 12 else 0
        val offset = 14 + headerSize + masks + colors * 4
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .put('B'.code.toByte()).put('M'.code.toByte()).putInt(14 + dib.size).putInt(0).putInt(offset)
        return header.array() + dib
    }

    // CopyQ

    /** Prints one JSON object a line: {"t": text} or {"p": base64 PNG}, every tab, newest first. */
    const val COPYQ_SCRIPT = """
var lines = [];
var tabs = tab();
for (var t = 0; t < tabs.length; ++t) {
  tab(tabs[t]);
  for (var i = 0; i < count(); ++i) {
    var text = str(read('text/plain', i));
    if (text) { lines.push(JSON.stringify({t: text})); continue; }
    var png = read('image/png', i);
    if (png.length > 0) lines.push(JSON.stringify({p: str(toBase64(png))}));
  }
}
print(lines.join('\n'));
"""

    fun readCopyQ(command: String? = copyqCommand()): List<ImportedClip> {
        val exe = command ?: throw ClipImportException("CopyQ's copyq command wasn't found.")
        val process = runCatching { ProcessBuilder(exe, "eval", "--", COPYQ_SCRIPT).redirectErrorStream(false).start() }
            .getOrElse { throw ClipImportException("CopyQ couldn't be started: ${it.message}") }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroy()
            throw ClipImportException("CopyQ took too long to answer.")
        }
        if (process.exitValue() != 0) {
            throw ClipImportException(if ("server" in err.lowercase()) "Start CopyQ first, then import again." else "CopyQ said: ${err.trim().take(200)}")
        }
        return parseCopyQ(out)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCopyQ(output: String): List<ImportedClip> = output.lineSequence().mapNotNull { line ->
        val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
        obj["t"]?.jsonPrimitive?.contentOrNull?.let { return@mapNotNull ImportedClip(text = it) }
        obj["p"]?.jsonPrimitive?.contentOrNull?.let { b64 ->
            runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.let { ImportedClip(png = it) }
        }
    }.toList()
}
