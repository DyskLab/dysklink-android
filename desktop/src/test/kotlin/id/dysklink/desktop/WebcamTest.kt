package id.dysklink.desktop

import id.phonelink.android.core.PhoneVideo
import id.phonelink.android.core.VideoKind
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class WebcamTest {
    // linux/videodev2.h, x86_64 and arm64

    @Test
    fun ioctlNumbersMatchVideodev2() {
        assertEquals(0x80685600L, V4l2.VIDIOC_QUERYCAP)
        assertEquals(0xc0d05605L, V4l2.vidiocSFmt(pointerSize = 8))
        // 32-bit: the union starts 4 bytes in, so struct v4l2_format is 204 bytes.
        assertEquals(0xc0cc5605L, V4l2.vidiocSFmt(pointerSize = 4))
        assertEquals(208, V4l2.formatSize(8))
        assertEquals(0x56595559, V4l2.PIX_FMT_YUYV)
        assertEquals(0x32315559, V4l2.PIX_FMT_YUV420)
        assertEquals(2, V4l2.BUF_TYPE_VIDEO_OUTPUT)
        assertEquals(1, V4l2.FIELD_NONE)
    }

    @Test
    fun v4l2FormatLayout() {
        val bytes = V4l2.outputFormat(1280, 720, V4l2.PIX_FMT_YUYV, pointerSize = 8)
        assertEquals(208, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        assertEquals(2, b.getInt(0))
        assertEquals(0, b.getInt(4))
        assertEquals(1280, b.getInt(8))
        assertEquals(720, b.getInt(12))
        assertEquals(V4l2.PIX_FMT_YUYV, b.getInt(16))
        assertEquals(V4l2.FIELD_NONE, b.getInt(20))
        assertEquals(2560, b.getInt(24))
        assertEquals(1280 * 720 * 2, b.getInt(28))
        assertEquals(V4l2.COLORSPACE_SRGB, b.getInt(32))
        val i420 = ByteBuffer.wrap(V4l2.outputFormat(640, 480, V4l2.PIX_FMT_YUV420, 8)).order(ByteOrder.nativeOrder())
        assertEquals(640, i420.getInt(24))
        assertEquals(640 * 480 * 3 / 2, i420.getInt(28))
    }

    @Test
    fun capabilityNames() {
        val cap = ByteArray(V4l2.CAPABILITY_SIZE)
        "v4l2 loopback".toByteArray().copyInto(cap, 0)
        "DyskLink Camera".toByteArray().copyInto(cap, 16)
        assertEquals("v4l2 loopback", V4l2.driverOf(cap))
        assertEquals("DyskLink Camera", V4l2.cardOf(cap))
    }

    // Conversions

    private val red = 0xff0000
    private val blue = 0x0000ff
    private fun u(b: Byte) = b.toInt() and 0xff

    @Test
    fun yuyvOfKnownColours() {
        val out = ByteArray(8)
        WebcamFrames.toYuyv(intArrayOf(0xffffff, 0x000000, red, red), 4, 1, out)
        // White Y 235, black Y 16, grey chroma 128; red BT.601: Y 82, U 90, V 240.
        assertEquals(listOf(235, 128, 16, 128), out.take(4).map(::u))
        assertEquals(listOf(82, 90, 82, 240), out.drop(4).map(::u))
    }

    @Test
    fun yuyvAveragesThePairsColour() {
        val out = ByteArray(4)
        WebcamFrames.toYuyv(intArrayOf(red, blue), 2, 1, out)
        val alone = ByteArray(4).also { WebcamFrames.toYuyv(intArrayOf(0x800080, 0x800080), 2, 1, it) }
        assertEquals(82, u(out[0]))
        assertEquals(41, u(out[2]))
        assertEquals(u(alone[1]), u(out[1]))
        assertEquals(u(alone[3]), u(out[3]))
    }

    @Test
    fun i420Planes() {
        // 4 × 2: left 2 × 2 block red, right one blue.
        val px = intArrayOf(red, red, blue, blue, red, red, blue, blue)
        val out = ByteArray(4 * 2 * 3 / 2)
        WebcamFrames.toI420(px, 4, 2, out)
        assertEquals(listOf(82, 82, 41, 41, 82, 82, 41, 41), out.take(8).map(::u))
        assertEquals(listOf(90, 240), listOf(u(out[8]), u(out[10])))
        assertEquals(240, u(out[9]))
        assertEquals(110, u(out[11]))
    }

    @Test
    fun bgrBottomRowFirst() {
        // Top row: red, green. Bottom row: blue, white.
        val px = intArrayOf(red, 0x00ff00, blue, 0xffffff)
        val out = ByteArray(2 * 2 * 3)
        WebcamFrames.toBgrBottomUp(px, 2, 2, out)
        assertEquals(listOf(255, 0, 0, 255, 255, 255, 0, 0, 255, 0, 255, 0), out.map(::u))
    }

    // Fitting into the fixed size

    /** 40 × 20: the left half red, the right half blue. */
    private fun halves(w: Int = 40, h: Int = 20) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
        for (y in 0 until h) for (x in 0 until w) it.setRGB(x, y, if (x < w / 2) red else blue)
    }

    private fun WebcamFrames.Canvas.at(x: Int, y: Int) = pixels[y * width + x] and 0xffffff

    @Test
    fun letterboxedIntoTheFixedSize() {
        val canvas = WebcamFrames.Canvas(80, 80)
        WebcamFrames.draw(canvas, halves(), 0, mirror = false)
        // 80 × 40 in the middle: black bars above and below.
        assertEquals(0, canvas.at(40, 5))
        assertEquals(0, canvas.at(40, 75))
        assertEquals(red, canvas.at(10, 40))
        assertEquals(blue, canvas.at(70, 40))
    }

    @Test
    fun mirroredAndTurned() {
        val canvas = WebcamFrames.Canvas(80, 80)
        WebcamFrames.draw(canvas, halves(), 0, mirror = true)
        assertEquals(blue, canvas.at(10, 40))
        assertEquals(red, canvas.at(70, 40))
        // A quarter turn clockwise: 40 wide, 80 tall, bars left and right; the left (red) goes to the top.
        WebcamFrames.draw(canvas, halves(), 90, mirror = false)
        assertEquals(0, canvas.at(5, 40))
        assertEquals(0, canvas.at(75, 40))
        assertEquals(red, canvas.at(40, 10))
        assertEquals(blue, canvas.at(40, 70))
        WebcamFrames.draw(canvas, halves(), 270, mirror = false)
        assertEquals(blue, canvas.at(40, 10))
        assertEquals(red, canvas.at(40, 70))
    }

    @Test
    fun placeholderIsDarkWithText() {
        val canvas = WebcamFrames.Canvas()
        WebcamFrames.placeholder(canvas)
        assertEquals(0x121418, canvas.at(5, 5))
        assertEquals(0x121418, canvas.at(canvas.width - 5, canvas.height - 5))
        val bright = canvas.pixels.count { ((it shr 16) and 0xff) > 180 }
        assertTrue(bright > 500, "text drawn: $bright bright pixels")
    }

    // Pacing

    @Test
    fun pacerSendsNewFramesAtMost30AndRepeatsAt15() {
        val pacer = FramePacer(maxFps = 30, keepAliveMs = 66)
        assertTrue(pacer.canSendNew(0))
        pacer.sent(0)
        assertFalse(pacer.canSendNew(20))
        assertTrue(pacer.canSendNew(33))
        assertFalse(pacer.keepAliveDue(50))
        assertTrue(pacer.keepAliveDue(66))
        assertEquals(13, pacer.waitMs(20))
        assertEquals(1, pacer.waitMs(500))
    }

    private class FakeSink(override val width: Int = WebcamFrames.WIDTH, override val height: Int = WebcamFrames.HEIGHT, val busy: Boolean = false) : WebcamSink {
        override val name = "/dev/video10"
        val frames = mutableListOf<IntArray>()
        var opened = 0
        var closed = 0
        override fun open() { opened++; if (busy) throw WebcamOpenException("busy", busy = true) }
        override fun send(pixels: IntArray) { assertEquals(width * height, pixels.size); frames += pixels.copyOf() }
        override fun close() { closed++ }
    }

    private class FakeBackend(var plan: WebcamPlan, val sink: FakeSink) : WebcamBackend {
        override fun plan() = plan
        override fun sink(device: String) = sink
        override fun busyHint(device: String, problem: String) = HostChecks.webcamBusy(false, device, problem)
    }

    private fun camera(frame: ByteArray?, live: Boolean = true, rotation: Int = 0) =
        PhoneVideo(VideoKind.Camera, waiting = false, live = live, rotation = rotation, frame = frame)

    /** Fake "JPEGs": the first byte is the colour, the second the width in tens. */
    private val decode: (ByteArray) -> BufferedImage? = { bytes ->
        val w = bytes[1] * 10
        BufferedImage(w, 20, BufferedImage.TYPE_INT_RGB).also { img -> for (y in 0 until 20) for (x in 0 until w) img.setRGB(x, y, if (bytes[0].toInt() == 1) red else blue) }
    }

    @Test
    fun keepsSendingTheLastFrameThenTheOffFrame() {
        val sink = FakeSink()
        val cameras = MutableStateFlow(mapOf("p1" to camera(byteArrayOf(1, 4))))
        val cam = VirtualCamera(cameras, FakeBackend(WebcamPlan("/dev/video10"), sink), on = false, decode = decode, runThread = false)
        cam.setOn(true)
        cam.step(1_000)
        assertEquals(WebcamStatus.Active, cam.state.value.status)
        assertTrue(cam.state.value.live)
        assertEquals(1, sink.frames.size)
        assertEquals(red, sink.frames[0][360 * 1280 + 300] and 0xffffff)
        // The phone pauses: nothing until the keep-alive, then the same picture again.
        cam.step(1_030)
        assertEquals(1, sink.frames.size)
        cam.step(1_070)
        assertEquals(2, sink.frames.size)
        assertContentEquals(sink.frames[0], sink.frames[1])
        // The other lens: a picture of another size still goes out at 1280 × 720.
        cameras.value = mapOf("p1" to camera(byteArrayOf(2, 8)))
        cam.step(1_110)
        assertEquals(3, sink.frames.size)
        assertEquals(blue, sink.frames[2][360 * 1280 + 640] and 0xffffff)
        // The camera stops: the "off" frame at once, and again later; the device stays open.
        cameras.value = emptyMap()
        cam.step(1_120)
        assertEquals(4, sink.frames.size)
        assertEquals(0x121418, sink.frames[3][5] and 0xffffff)
        assertFalse(cam.state.value.live)
        cam.step(1_200)
        assertEquals(5, sink.frames.size)
        assertEquals(0, sink.closed)
        cam.setOn(false)
        assertEquals(1, sink.closed)
        assertEquals(WebcamStatus.Off, cam.state.value.status)
    }

    @Test
    fun mirrorWhilePausedRedraws() {
        val sink = FakeSink()
        val cameras = MutableStateFlow(mapOf("p1" to camera(byteArrayOf(1, 4))))
        val cam = VirtualCamera(cameras, FakeBackend(WebcamPlan("/dev/video10"), sink), on = false, decode = decode, runThread = false)
        cam.setOn(true)
        cam.step(0)
        cam.setView(90, mirror = false)
        cam.step(5)
        assertEquals(2, sink.frames.size)
        // Turned: bars at the sides.
        assertEquals(0, sink.frames[1][360 * 1280 + 10] and 0xffffff)
    }

    @Test
    fun busyDeviceShowsItsHint() {
        val sink = FakeSink(busy = true)
        val cam = VirtualCamera(MutableStateFlow(emptyMap()), FakeBackend(WebcamPlan("/dev/video10"), sink), on = false, runThread = false)
        cam.setOn(true)
        cam.step(0)
        val state = cam.state.value
        assertEquals(WebcamStatus.Busy, state.status)
        assertTrue(state.hints.single().title.contains("busy"))
        assertTrue(state.hints.single().command!!.contains("devices=2"))
        // Tried again a while later.
        cam.step(1_000)
        assertEquals(1, sink.opened)
        cam.step(VirtualCamera.RETRY_MS + 1)
        assertEquals(2, sink.opened)
    }

    @Test
    fun needsSetupUntilTheDeviceIsThere() {
        val sink = FakeSink()
        val backend = FakeBackend(HostChecks.linuxWebcam(LoopbackProbe(), Distro.Debian), sink)
        val cam = VirtualCamera(MutableStateFlow(emptyMap()), backend, on = false, runThread = false)
        cam.setOn(true)
        cam.step(0)
        assertEquals(WebcamStatus.NeedsSetup, cam.state.value.status)
        assertTrue(cam.state.value.hints.isNotEmpty())
        assertEquals(0, sink.opened)
        // modprobe done; the window gets the focus back.
        backend.plan = HostChecks.linuxWebcam(LoopbackProbe(loaded = true, devices = listOf(LoopbackDevice("/dev/video10", "DyskLink Camera", true)), persistent = true), Distro.Debian)
        cam.recheck()
        cam.step(10)
        assertEquals(WebcamStatus.Active, cam.state.value.status)
        assertEquals("DyskLink Camera", cam.state.value.label)
        assertTrue(cam.state.value.hints.isEmpty())
        assertEquals(1, sink.frames.size)
    }

    // Detection

    private fun fakeSys(): File {
        val sys = Files.createTempDirectory("sys").toFile()
        File(sys, "module/v4l2loopback").mkdirs()
        File(sys, "devices/virtual/video4linux/video10").mkdirs()
        File(sys, "devices/virtual/video4linux/video10/name").writeText("DyskLink Camera\n")
        File(sys, "devices/virtual/video4linux/video2").mkdirs()
        File(sys, "devices/virtual/video4linux/video2/name").writeText("OBS Virtual Camera\n")
        return sys
    }

    @Test
    fun scansLoopbackDevicesInSysfs() {
        val sys = fakeSys()
        val probe = Loopback.scan(sys) { it != "/dev/video2" }
        assertTrue(probe.loaded)
        assertEquals(listOf("/dev/video2", "/dev/video10"), probe.devices.map { it.node })
        assertEquals("DyskLink Camera", probe.devices[1].name)
        assertEquals("/dev/video10", Loopback.pick(probe.devices)?.node)
        // Only another app's device: it's used when it can be written.
        assertEquals("/dev/video2", Loopback.pick(listOf(LoopbackDevice("/dev/video2", "OBS Virtual Camera", true)))?.node)
        assertNull(Loopback.pick(listOf(LoopbackDevice("/dev/video2", "x", false))))
        val empty = Loopback.scan(Files.createTempDirectory("sys").toFile())
        assertFalse(empty.loaded)
        assertTrue(empty.devices.isEmpty())
        sys.deleteRecursively()
    }

    @Test
    fun secureBootAndModulesLoad() {
        assertEquals(true, Loopback.secureBoot(byteArrayOf(6, 0, 0, 0, 1)))
        assertEquals(false, Loopback.secureBoot(byteArrayOf(6, 0, 0, 0, 0)))
        assertNull(Loopback.secureBoot(null))
        val dir = Files.createTempDirectory("modules-load").toFile()
        File(dir, "other.conf").writeText("# nothing\nsnd-aloop\n")
        assertFalse(Loopback.loadsAtBoot(listOf(dir, File(dir, "missing"))))
        File(dir, "dysklink.conf").writeText("v4l2loopback # the webcam\n")
        assertTrue(Loopback.loadsAtBoot(listOf(dir)))
        dir.deleteRecursively()
    }

    @Test
    fun registryOutput() {
        val out = "\r\nHKEY_CLASSES_ROOT\\CLSID\\${Softcam.CLSID}\\InprocServer32\r\n    (Standard)    REG_SZ    C:\\Users\\fanny\\AppData\\Local\\DyskLink\\app\\resources\\softcam.dll\r\n\r\n"
        assertEquals("C:\\Users\\fanny\\AppData\\Local\\DyskLink\\app\\resources\\softcam.dll", Softcam.registeredPath(out, 0))
        assertNull(Softcam.registeredPath("ERROR: The system was unable to find the specified registry key or value.", 1))
        assertEquals("C:\\x\\softcam.dll", Softcam.registeredPath("    (Default)    REG_EXPAND_SZ    C:\\x\\softcam.dll", 0))
        assertTrue(Softcam.samePath("c:\\users\\fanny\\softcam.dll", "C:\\Users\\Fanny\\softcam.dll"))
        assertFalse(Softcam.samePath("C:\\old\\softcam.dll", "C:\\new\\softcam.dll"))
        assertFalse(Softcam.samePath(null, "C:\\new\\softcam.dll"))
    }

    @Test
    fun elevatedRegsvr32Line() {
        val install = Softcam.elevatedRegsvr32("C:\\Program Files\\DyskLink\\app\\resources\\softcam.dll", remove = false)
        assertEquals(
            "\$p = Start-Process regsvr32 -ArgumentList '/s','\"C:\\Program Files\\DyskLink\\app\\resources\\softcam.dll\"' -Verb RunAs -Wait -PassThru; exit \$p.ExitCode",
            install,
        )
        assertTrue(Softcam.elevatedRegsvr32("C:\\it's\\softcam.dll", remove = true).contains("'/s','/u','\"C:\\it''s\\softcam.dll\"'"))
    }

    // Hints

    @Test
    fun linuxHintsByDistro() {
        val debian = HostChecks.linuxWebcam(LoopbackProbe(), Distro.Debian)
        assertNull(debian.device)
        assertEquals(listOf("Install v4l2loopback", "Load the DyskLink Camera device", "Keep it after a restart"), debian.hints.map { it.title })
        assertEquals("sudo apt install v4l2loopback-dkms", debian.hints[0].command)
        assertEquals(Loopback.MODPROBE, debian.hints[1].command)
        assertTrue(debian.hints[1].command!!.contains("card_label=\"DyskLink Camera\" exclusive_caps=1"))
        assertTrue(debian.hints[2].command!!.contains("/etc/modules-load.d/dysklink.conf"))
        assertTrue(debian.hints[2].command!!.contains("/etc/modprobe.d/dysklink.conf"))
        val fedora = HostChecks.linuxWebcam(LoopbackProbe(), Distro.Fedora).hints[0]
        assertEquals("sudo dnf install v4l2loopback", fedora.command)
        assertTrue(fedora.text.contains("RPM Fusion"))
        assertEquals("sudo pacman -S v4l2loopback-dkms", HostChecks.linuxWebcam(LoopbackProbe(), Distro.Arch).hints[0].command)
        val other = HostChecks.linuxWebcam(LoopbackProbe(), Distro.Other).hints[0]
        assertNull(other.command)
        assertTrue(other.text.contains("sudo apt install v4l2loopback-dkms") && other.text.contains("sudo dnf install v4l2loopback"))
    }

    @Test
    fun linuxHintsByWhatsThere() {
        // Installed, Secure Boot on: no install step, the MOK step instead.
        val signed = HostChecks.linuxWebcam(LoopbackProbe(installed = true, secureBoot = true), Distro.Fedora)
        assertEquals(listOf("Load the DyskLink Camera device", "Secure Boot is on", "Keep it after a restart"), signed.hints.map { it.title })
        assertTrue(signed.hints[1].command!!.startsWith("sudo mokutil --import /etc/pki/akmods"))
        assertTrue(HostChecks.linuxWebcam(LoopbackProbe(installed = true, secureBoot = true), Distro.Debian).hints[1].command!!.contains("/var/lib/dkms/mok.pub"))
        assertFalse(HostChecks.linuxWebcam(LoopbackProbe(installed = true, secureBoot = false), Distro.Debian).hints.any { it.title.contains("Secure Boot") })
        // A device it can't write.
        val locked = HostChecks.linuxWebcam(LoopbackProbe(loaded = true, devices = listOf(LoopbackDevice("/dev/video10", "DyskLink Camera", false))), Distro.Debian)
        assertEquals("sudo usermod -aG video \$USER", locked.hints.single().command)
        // Loaded without a device.
        assertTrue(HostChecks.linuxWebcam(LoopbackProbe(loaded = true), Distro.Debian).hints[0].command!!.startsWith("sudo modprobe -r v4l2loopback && "))
        // Ready: only the restart step while it isn't kept.
        val ready = LoopbackProbe(loaded = true, devices = listOf(LoopbackDevice("/dev/video10", "DyskLink Camera", true)), persistent = false)
        val plan = HostChecks.linuxWebcam(ready, Distro.Debian)
        assertEquals("/dev/video10", plan.device)
        assertEquals(listOf("Keep it after a restart"), plan.hints.map { it.title })
        assertTrue(HostChecks.linuxWebcam(ready.copy(persistent = true), Distro.Debian).hints.isEmpty())
    }

    @Test
    fun windowsHints() {
        val dll = "C:\\Users\\fanny\\AppData\\Local\\DyskLink\\app\\resources\\softcam.dll"
        val missing = HostChecks.windowsWebcam(null, null)
        assertNull(missing.device)
        assertNotNull(missing.hints.single().link)
        val fresh = HostChecks.windowsWebcam(dll, null)
        assertNull(fresh.device)
        assertEquals(HostChecks.ACTION_WEBCAM_INSTALL, fresh.hints.single().action?.id)
        assertTrue(fresh.settings.isEmpty())
        val moved = HostChecks.windowsWebcam(dll, "D:\\old\\softcam.dll")
        assertNull(moved.device)
        assertEquals("Install again", moved.hints.single().action?.label)
        assertEquals(HostChecks.ACTION_WEBCAM_REMOVE, moved.settings.single().action?.id)
        val ready = HostChecks.windowsWebcam(dll, dll.uppercase())
        assertEquals(dll, ready.device)
        assertEquals("DyskLink Camera", ready.label)
        assertTrue(ready.hints.isEmpty())
        assertEquals(HostChecks.ACTION_WEBCAM_REMOVE, ready.settings.single().action?.id)
    }

    @Test
    fun obsHintTellsAboutTheWebcam() {
        assertTrue(HostChecks.obsHint(ObsCapture.Windows, "Pixel camera - DyskLink").text.contains("'DyskLink Camera' as a Video Capture Device"))
        assertTrue(HostChecks.obsHint(ObsCapture.PipeWire, "x").text.contains("Video Capture Device (V4L2)"))
        assertEquals(null, Softcam.dll(null))
    }
}
