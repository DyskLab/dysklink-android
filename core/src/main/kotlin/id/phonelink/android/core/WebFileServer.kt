package id.phonelink.android.core

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A small web server for phone files, for a laptop on the same Wi-Fi. A browser has to enter the PIN shown on the
 * phone first. It only answers private network addresses. Files can be downloaded, a folder as a ZIP, and uploaded
 * into a folder; an upload never replaces an existing file.
 */
class WebFileServer(
    root: File,
    private val pin: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val browser = FsBrowser(root)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var server: ServerSocket? = null
    private var failures = 0
    private var lockedUntil = 0L
    private val lastUse = AtomicLong(clock())

    /** Called with the browser's address each time a file is downloaded. */
    var onDownload: ((String) -> Unit)? = null
    /** Uploads are refused unless turned on, so by default nothing on the phone can be changed. */
    @Volatile var allowsUploads = false
    /** Called with the browser's address and the file's name after an upload. */
    var onUpload: ((String, String) -> Unit)? = null

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = server?.isClosed == false
    val lastUsed: Long get() = lastUse.get()

    fun start(port: Int, bind: InetAddress? = null) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind, port))
        server = socket
        thread(name = "web-files", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        sessions.clear()
        pool.shutdownNow()
    }

    // Requests

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        /** The rest of the connection, for an upload's body. */
        val input: InputStream,
    )

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            try {
                if (!isLocal(socket.inetAddress)) return respond(socket.getOutputStream(), 403, "text/plain", "Only devices on the same network can connect.")
                val input = BufferedInputStream(socket.getInputStream())
                val request = read(input) ?: return
                lastUse.set(clock())
                route(request, socket.getOutputStream(), socket.inetAddress.hostAddress ?: "")
            } catch (_: SocketException) {
                // The browser went away, for example after cancelling a download.
            } catch (_: IOException) {
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            if (headers.size > 100) return null
        }
        // An upload streams its body to the file, so it isn't read here.
        val length = if (parts[0] == "PUT") 0 else headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        val target = parts[1]
        val rawPath = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))
        val path = runCatching { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return Request(parts[0], path, query, headers, String(body, 0, read), input)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > MAX_LINE) return null
        }
        return bytes.toString("UTF-8")
    }

    private fun route(request: Request, out: OutputStream, from: String) {
        val signedIn = session(request) != null
        when {
            request.method == "POST" && request.path == "/login" -> login(request, out)
            request.path == "/logout" -> {
                session(request)?.let(sessions::remove)
                redirect(out, "/", "dl_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
            }
            !signedIn -> respond(out, if (request.path == "/") 200 else 401, "text/html", loginPage(null))
            request.path == "/" -> redirect(out, "/files/")
            request.path.startsWith("/files/") -> files(request, out, request.path.removePrefix("/files/"), from)
            request.method == "PUT" && request.path.startsWith("/upload/") -> upload(request, out, request.path.removePrefix("/upload/"), from)
            request.path.startsWith("/zip/") -> zip(out, request.path.removePrefix("/zip/").trimEnd('/'), from)
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun session(request: Request): String? {
        val token = request.headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("dl_session=") }?.removePrefix("dl_session=") ?: return null
        val started = sessions[token] ?: return null
        if (clock() - started > SESSION_MS) {
            sessions.remove(token)
            return null
        }
        return token
    }

    @Synchronized
    private fun login(request: Request, out: OutputStream) {
        val now = clock()
        if (now < lockedUntil) {
            return respond(out, 429, "text/html", loginPage("Too many wrong PINs. Wait a minute and try again."))
        }
        val typed = parseForm(request.body)["pin"].orEmpty().filter(Char::isDigit)
        if (!MessageDigest.isEqual(typed.toByteArray(), pin.toByteArray())) {
            failures++
            if (failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCK_MS
            }
            return respond(out, 403, "text/html", loginPage("That PIN isn't right. It's shown in DyskLink on the phone."))
        }
        failures = 0
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        sessions[token] = now
        redirect(out, "/files/", "dl_session=$token; Path=/; HttpOnly; SameSite=Strict")
    }

    private fun files(request: Request, out: OutputStream, path: String, from: String) {
        val clean = path.trimEnd('/')
        val entries = runCatching { browser.list(clean) }.getOrNull()
        if (entries != null) {
            if (!path.endsWith("/") && path.isNotEmpty()) return redirect(out, "/files/" + encodePath(clean) + "/")
            return respond(out, 200, "text/html", listing(clean, entries))
        }
        val file = runCatching { browser.file(clean) }.getOrNull() ?: return respond(out, 404, "text/plain", "Not found")
        onDownload?.invoke(from)
        send(out, file, download = request.query["download"] == "1", range = request.headers["range"])
    }

    /** Saves the body as a new file. The page sends one file per request, with its name at the end of the path. */
    private fun upload(request: Request, out: OutputStream, path: String, from: String) {
        // Only this site's own page may upload; other sites can't send the cookie anyway (SameSite=Strict).
        if (!allowsUploads) return respond(out, 403, "text/plain", "Uploads are off. Turn them on in DyskLink on the phone.")
        val origin = request.headers["origin"]
        if (origin != null && origin.substringAfter("://") != request.headers["host"]) return respond(out, 403, "text/plain", "Not allowed")
        val length = request.headers["content-length"]?.toLongOrNull() ?: return respond(out, 411, "text/plain", "Length required")
        val folderPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val target = runCatching { browser.newFile(folderPath, name) }.getOrNull()
            ?: return respond(out, 400, "text/plain", "Can't save there")
        if (length < 0 || target.parentFile!!.usableSpace - length < MIN_FREE) return respond(out, 507, "text/plain", "Not enough space on the phone")
        val part = File(target.path + ".part")
        val ok = runCatching {
            part.outputStream().use { file ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val count = request.input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) throw IOException("Upload cut short")
                    file.write(buffer, 0, count)
                    left -= count
                }
            }
            part.renameTo(target)
        }.getOrDefault(false)
        if (!ok) {
            part.delete()
            return respond(out, 500, "text/plain", "Upload failed")
        }
        onUpload?.invoke(from, target.name)
        respond(out, 201, "text/plain", target.name)
    }

    /** A folder and everything in it as one ZIP, made while it downloads. Hidden files are left out. */
    private fun zip(out: OutputStream, path: String, from: String) {
        if (path.isEmpty()) return respond(out, 400, "text/plain", "Open a folder first")
        val folder = runCatching { browser.folder(path) }.getOrNull() ?: return respond(out, 404, "text/plain", "Not found")
        onDownload?.invoke(from)
        writeHead(
            out, 200,
            listOf(
                "Content-Type" to "application/zip",
                "Content-Disposition" to "attachment; filename*=UTF-8''" + encode(folder.name + ".zip"),
                "Cache-Control" to "no-store",
            ),
        )
        val zip = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 64 * 1024))
        fun add(relative: String, inZip: String) {
            val entries = runCatching { browser.list(relative) }.getOrNull() ?: return
            for (entry in entries) {
                val child = "$relative/${entry.name}"
                val name = if (inZip.isEmpty()) entry.name else "$inZip/${entry.name}"
                if (entry.isDirectory) {
                    add(child, name)
                    continue
                }
                val file = runCatching { browser.file(child) }.getOrNull() ?: continue
                // Photos and videos are already compressed, so they're stored as they are, which is much faster.
                zip.setLevel(if (zipStored(entry.name)) java.util.zip.Deflater.NO_COMPRESSION else java.util.zip.Deflater.BEST_SPEED)
                zip.putNextEntry(java.util.zip.ZipEntry(name).apply { time = entry.modified })
                runCatching { file.inputStream().use { it.copyTo(zip, 64 * 1024) } }
                zip.closeEntry()
            }
        }
        add(path, folder.name)
        zip.finish()
        zip.flush()
    }

    // Responses

    private fun send(out: OutputStream, file: File, download: Boolean, range: String?) {
        val size = file.length()
        val type = mimeType(file.name)
        var start = 0L
        var end = size - 1
        var status = 200
        // One byte range, which is what video players use to seek.
        Regex("""bytes=(\d*)-(\d*)""").matchEntire(range.orEmpty())?.let { match ->
            val (from, to) = match.destructured
            when {
                from.isNotEmpty() -> {
                    start = from.toLong()
                    end = to.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                }
                to.isNotEmpty() -> start = (size - to.toLong()).coerceAtLeast(0)
            }
            if (start > end || start >= size) {
                return respond(out, 416, "text/plain", "Range not available", listOf("Content-Range" to "bytes */$size"))
            }
            status = 206
        }
        val length = if (size == 0L) 0 else end - start + 1
        val disposition = (if (download) "attachment" else "inline") + "; filename*=UTF-8''" + encode(file.name)
        val headers = mutableListOf(
            "Content-Type" to type,
            "Content-Length" to length.toString(),
            "Content-Disposition" to disposition,
            "Accept-Ranges" to "bytes",
            "X-Content-Type-Options" to "nosniff",
            // A phone file opened in the browser must not be able to run scripts on this page.
            "Content-Security-Policy" to "sandbox",
        )
        if (status == 206) headers += "Content-Range" to "bytes $start-$end/$size"
        writeHead(out, status, headers)
        file.inputStream().use { input ->
            input.skip(start)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (count < 0) break
                out.write(buffer, 0, count)
                left -= count
            }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: String, extra: List<Pair<String, String>> = emptyList()) {
        val bytes = body.toByteArray()
        writeHead(
            out, status,
            listOf(
                "Content-Type" to "$type; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-store",
                "X-Frame-Options" to "DENY",
                "Referrer-Policy" to "no-referrer",
            ) + extra,
        )
        out.write(bytes)
        out.flush()
    }

    private fun redirect(out: OutputStream, location: String, cookie: String? = null) {
        val headers = mutableListOf("Location" to location, "Content-Length" to "0", "Cache-Control" to "no-store")
        cookie?.let { headers += "Set-Cookie" to it }
        writeHead(out, 303, headers)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val text = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        headers.forEach { (name, value) -> text.append(name).append(": ").append(value).append("\r\n") }
        text.append("Connection: close\r\n\r\n")
        out.write(text.toString().toByteArray())
    }

    // Pages

    private fun page(title: String, body: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${html(title)}</title><style>
:root{color-scheme:dark;--bg:#090d13;--panel:#121822;--line:#222b38;--text:#f2f5f9;--dim:#98a3b3;--mint:#5ef2c2}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 -apple-system,system-ui,sans-serif}
main{max-width:860px;margin:0 auto;padding:28px 16px}a{color:var(--text);text-decoration:none}
h1{font-size:20px;margin:0 0 4px}.dim{color:var(--dim);font-size:13px}.crumbs a{color:var(--mint)}
table{width:100%;border-collapse:collapse;margin-top:16px;background:var(--panel);border-radius:14px;overflow:hidden}
td{padding:11px 14px;border-top:1px solid var(--line);vertical-align:middle}tr:first-child td{border-top:0}
td.size,td.date{color:var(--dim);font-size:13px;white-space:nowrap;text-align:right}
a.dl{color:var(--mint);font-size:13px;font-weight:600}tr:hover td{background:#18202c}
.box{max-width:340px;margin:12vh auto;background:var(--panel);padding:28px;border-radius:18px;border:1px solid var(--line)}
input{width:100%;box-sizing:border-box;font-size:28px;letter-spacing:10px;text-align:center;padding:12px;border-radius:12px;
border:1px solid var(--line);background:var(--bg);color:var(--text)}button{width:100%;margin-top:14px;padding:12px;border:0;
border-radius:12px;background:var(--mint);color:#090d13;font-weight:700;font-size:15px}.err{color:#ff8a80;font-size:13px}
.bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:14px}
.btn{display:inline-block;padding:8px 14px;border-radius:999px;background:var(--mint);color:#090d13;font-weight:700;font-size:13px;cursor:pointer}
.btn.ghost{background:var(--panel);color:var(--mint);border:1px solid var(--line)}
svg.i{width:18px;height:18px;vertical-align:-3px;margin-right:10px}@media(max-width:560px){td.date{display:none}}</style></head><body><main>$body</main></body></html>"""

    private fun loginPage(error: String?) = page(
        "DyskLink files",
        """<div class="box"><h1>Phone files</h1><p class="dim">Enter the PIN shown in DyskLink on the phone.</p>
<form method="post" action="/login"><input name="pin" inputmode="numeric" autocomplete="one-time-code" maxlength="6" autofocus>
${error?.let { "<p class=\"err\">${html(it)}</p>" } ?: ""}<button>Open</button></form></div>""",
    )

    private fun listing(path: String, entries: List<FsEntry>): String {
        val crumbs = StringBuilder("<a href=\"/files/\">Phone</a>")
        var walked = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { part ->
            walked += "$part/"
            crumbs.append(" / <a href=\"/files/${encodePath(walked)}\">${html(part)}</a>")
        }
        val dates = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        val rows = entries.joinToString("") { entry ->
            val link = "/files/" + encodePath(if (path.isEmpty()) entry.name else "$path/${entry.name}")
            if (entry.isDirectory) {
                val zip = "/zip/" + encodePath(if (path.isEmpty()) entry.name else "$path/${entry.name}")
                "<tr><td><a href=\"$link/\">$FOLDER_ICON${html(entry.name)}</a></td><td class=\"size\"></td><td class=\"date\">${dates.format(Date(entry.modified))}</td><td><a class=\"dl\" href=\"$zip\">ZIP</a></td></tr>"
            } else {
                "<tr><td><a href=\"$link\" target=\"_blank\" rel=\"noopener\">$FILE_ICON${html(entry.name)}</a></td><td class=\"size\">${size(entry.size)}</td>" +
                    "<td class=\"date\">${dates.format(Date(entry.modified))}</td><td><a class=\"dl\" href=\"$link?download=1\">Download</a></td></tr>"
            }
        }
        val empty = if (entries.isEmpty()) "<p class=\"dim\">This folder is empty.</p>" else "<table>$rows</table>"
        return page(
            path.substringAfterLast('/').ifEmpty { "Phone" },
            """<h1 class="crumbs">$crumbs</h1><p class="dim">${entries.size} items · <a href="/logout">Sign out</a></p>
<div class="bar">${if (allowsUploads) "<label class=\"btn\">Upload files<input type=\"file\" id=\"pick\" multiple hidden></label>" else ""}${
                if (path.isEmpty()) "" else "<a class=\"btn ghost\" href=\"/zip/${encodePath(path)}\">Download folder as ZIP</a>"
            }<span id="st" class="dim"></span></div>$empty${if (allowsUploads) UPLOAD_SCRIPT else ""}""",
        )
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val FOLDER_ICON = "<svg class=\"i\" viewBox=\"0 0 24 24\"><path fill=\"#5ef2c2\" d=\"M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z\"/></svg>"
        private const val FILE_ICON = "<svg class=\"i\" viewBox=\"0 0 24 24\"><path fill=\"#98a3b3\" d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z\"/></svg>"
        private const val UPLOAD_SCRIPT = """<script>
const pick=document.getElementById('pick'),st=document.getElementById('st');
pick.onchange=async()=>{let failed=false;
for(const f of pick.files){await new Promise(done=>{const x=new XMLHttpRequest();
x.open('PUT',location.pathname.replace(/^\/files\//,'/upload/')+encodeURIComponent(f.name));
x.upload.onprogress=e=>{st.textContent='Uploading '+f.name+' '+Math.round(e.loaded*100/Math.max(e.total,1))+'%'};
x.onload=()=>{if(x.status>=300){failed=true;st.textContent=f.name+': '+x.responseText}done()};
x.onerror=()=>{failed=true;st.textContent=f.name+': connection lost';done()};x.send(f)})}
if(!failed)location.reload()};
</script>"""
        private const val MIN_FREE = 50_000_000L
        private const val MAX_CLIENTS = 8
        private const val MAX_BODY = 4_096
        private const val MAX_LINE = 8_192
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 60_000L
        private const val SESSION_MS = 12 * 60 * 60 * 1000L

        fun newPin(random: SecureRandom = SecureRandom()): String = "%06d".format(random.nextInt(1_000_000))

        /** 10.x, 172.16-31.x, 192.168.x, link-local, and loopback for tests. Never the internet. */
        fun isLocal(address: InetAddress): Boolean = when (address) {
            is Inet4Address, is Inet6Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
            else -> false
        }

        fun html(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

        fun parseForm(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate {
            val (key, value) = it.split('=', limit = 2)
            (runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)) to (runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value))
        }

        fun size(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
            else -> "$bytes B"
        }

        fun zipStored(name: String) = name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a", "aac", "ogg", "opus", "zip", "apk", "pdf")

        fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "pdf" -> "application/pdf"
            // Shown as text, never as a page, so a phone file can't act like part of this site.
            "txt", "md", "csv", "log", "json", "xml", "html", "htm", "svg" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            201 -> "Created"
            206 -> "Partial Content"
            303 -> "See Other"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            416 -> "Range Not Satisfiable"
            507 -> "Insufficient Storage"
            429 -> "Too Many Requests"
            else -> "Error"
        }
    }
}
