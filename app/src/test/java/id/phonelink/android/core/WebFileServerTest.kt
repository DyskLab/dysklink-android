package id.phonelink.android.core

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebFileServerTest {
    private val root: File = Files.createTempDirectory("web-files").toFile().apply {
        File(this, "DCIM").mkdirs()
        File(this, "DCIM/photo.jpg").writeBytes(ByteArray(1000) { it.toByte() })
        File(this, "<img src=x onerror=alert(1)>.txt").writeText("hi")
        File(this, "page.html").writeText("<script>alert(1)</script>")
    }
    private val outside: File = File(root.parentFile, "secret-${root.name}.txt").apply { writeText("secret") }
    private var now = 1_000_000L
    private val server = WebFileServer(root, "123456") { now }.apply { allowsUploads = true; start(0, InetAddress.getLoopbackAddress()) }
    private val base get() = "http://127.0.0.1:${server.port}"

    @AfterTest
    fun stop() {
        server.stop()
        root.deleteRecursively()
        outside.delete()
    }

    private class Reply(val code: Int, val body: String, val headers: Map<String, String>)

    private fun get(path: String, cookie: String? = null, range: String? = null): Reply {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.readBytes()?.let { String(it, Charsets.ISO_8859_1) }.orEmpty()
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
        return Reply(code, body, headers)
    }

    private fun login(pin: String): Reply {
        val connection = URL("$base/login").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write("pin=$pin".toByteArray()) }
        val code = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
        return Reply(code, "", headers)
    }

    private fun signIn(): String = login("123456").headers.getValue("set-cookie").substringBefore(';')

    private fun put(path: String, body: ByteArray, cookie: String?): Reply {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream)?.readBytes()?.let { String(it) }.orEmpty()
        return Reply(code, text, emptyMap())
    }

    @Test
    fun uploadsWithoutReplacing() {
        val cookie = signIn()
        assertEquals(401, put("/upload/DCIM/new.txt", "x".toByteArray(), null).code)
        assertEquals(201, put("/upload/DCIM/new.txt", "first".toByteArray(), cookie).code)
        val second = put("/upload/DCIM/new.txt", "second".toByteArray(), cookie)
        assertEquals(201, second.code)
        assertEquals("new (2).txt", second.body)
        assertEquals("first", File(root, "DCIM/new.txt").readText())
        assertEquals("second", File(root, "DCIM/new (2).txt").readText())
        assertEquals(400, put("/upload/DCIM/..%2F..%2Fescape.txt", "x".toByteArray(), cookie).code)
        assertFalse(File(root.parentFile, "escape.txt").exists())
        assertEquals(400, put("/upload/../outside/a.txt", "x".toByteArray(), cookie).code)
        server.allowsUploads = false
        assertEquals(403, put("/upload/DCIM/off.txt", "x".toByteArray(), cookie).code)
        assertFalse(File(root, "DCIM/off.txt").exists())
    }

    @Test
    fun zipsAFolder() {
        val cookie = signIn()
        File(root, "DCIM/.hidden").writeText("no")
        val reply = get("/zip/DCIM", cookie)
        assertEquals(200, reply.code)
        val names = java.util.zip.ZipInputStream(reply.body.toByteArray(Charsets.ISO_8859_1).inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue("DCIM/photo.jpg" in names)
        assertFalse(names.any { it.contains(".hidden") })
        assertEquals(400, get("/zip/", cookie).code)
        assertEquals(401, get("/zip/DCIM").code)
    }

    @Test
    fun needsThePin() {
        assertEquals(401, get("/files/").code)
        assertTrue(get("/").body.contains("Enter the PIN"))
        assertEquals(403, login("000000").code)
        val ok = login("123456")
        assertEquals(303, ok.code)
        assertTrue(ok.headers.getValue("set-cookie").contains("HttpOnly"))
        assertEquals(401, get("/files/", cookie = "dl_session=made-up").code)
    }

    @Test
    fun locksAfterFiveWrongPins() {
        repeat(5) { assertEquals(403, login("111111").code) }
        assertEquals(429, login("123456").code)
        now += 61_000
        assertEquals(303, login("123456").code)
    }

    @Test
    fun listsAndSendsFiles() {
        val cookie = signIn()
        val home = get("/files/", cookie)
        assertEquals(200, home.code)
        assertTrue(home.body.contains("DCIM"))
        // Names are escaped, so a file name can't add HTML to the page.
        assertTrue(home.body.contains("&lt;img src=x onerror=alert(1)&gt;.txt"))
        assertFalse(home.body.contains("<img src=x"))
        assertEquals(303, get("/files/DCIM", cookie).code)
        assertTrue(get("/files/DCIM/", cookie).body.contains("photo.jpg"))

        val photo = get("/files/DCIM/photo.jpg?download=1", cookie)
        assertEquals(200, photo.code)
        assertEquals(1000, photo.body.length)
        assertTrue(photo.headers.getValue("content-disposition").startsWith("attachment"))
        assertEquals("image/jpeg", photo.headers["content-type"])

        val part = get("/files/DCIM/photo.jpg", cookie, range = "bytes=10-19")
        assertEquals(206, part.code)
        assertEquals("bytes 10-19/1000", part.headers["content-range"])
        assertEquals(10, part.body.length)
        assertEquals(10.toChar(), part.body[0])
        assertEquals(416, get("/files/DCIM/photo.jpg", cookie, range = "bytes=5000-").code)
    }

    @Test
    fun webPagesOnThePhoneAreShownAsText() {
        val page = get("/files/page.html", signIn())
        assertTrue(page.headers.getValue("content-type").startsWith("text/plain"))
        assertEquals("sandbox", page.headers["content-security-policy"])
        assertEquals("nosniff", page.headers["x-content-type-options"])
    }

    @Test
    fun staysInsidePhoneStorage() {
        val cookie = signIn()
        listOf(
            "/files/../secret-${root.name}.txt",
            "/files/%2e%2e/secret-${root.name}.txt",
            "/files/DCIM/../../secret-${root.name}.txt",
            "/files/%00",
        ).forEach { path ->
            val reply = get(path, cookie)
            assertFalse(reply.body.contains("secret"), path)
        }
        // A raw request, since URL() would tidy the dots away before sending.
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write("GET /files/../secret-${root.name}.txt HTTP/1.1\r\nCookie: $cookie\r\n\r\n".toByteArray())
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(text.startsWith("HTTP/1.1 404"))
            assertFalse(text.contains("\r\n\r\nsecret"))
        }
    }

    @Test
    fun onlyLocalAddresses() {
        assertTrue(WebFileServer.isLocal(InetAddress.getByName("192.168.1.20")))
        assertTrue(WebFileServer.isLocal(InetAddress.getByName("10.0.0.5")))
        assertTrue(WebFileServer.isLocal(InetAddress.getByName("172.20.1.1")))
        assertFalse(WebFileServer.isLocal(InetAddress.getByName("8.8.8.8")))
        assertFalse(WebFileServer.isLocal(InetAddress.getByName("172.32.0.1")))
        assertEquals(6, WebFileServer.newPin().length)
    }
}
