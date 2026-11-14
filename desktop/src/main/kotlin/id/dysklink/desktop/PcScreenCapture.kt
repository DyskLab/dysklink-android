package id.dysklink.desktop

import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.PcScreenSource
import id.phonelink.android.core.ScreenGrab
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * This PC's screen for a phone, through java.awt.Robot: GDI on Windows, XGetImage on X11. Portable and needs nothing
 * installed, at about 10 to 20 pictures a second for a 1080p display, which the pacer stays under. Wayland doesn't let
 * apps capture the screen this way; [PortalScreenSource] does it there through the ScreenCast portal.
 */
class RobotScreenSource(private val wayland: Boolean) : PcScreenSource {
    private val robot: Robot? by lazy {
        if (GraphicsEnvironment.isHeadless()) null else runCatching { Robot() }.getOrNull()
    }

    override fun refusal(): String? = when {
        wayland -> PcScreen.WAYLAND_REFUSAL
        robot == null -> "This PC has no screen DyskLink can capture."
        else -> null
    }

    override fun displays(): List<PcDisplay> = runCatching {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = environment.defaultScreenDevice
        environment.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            PcDisplay(
                id = ScreenPictures.displayId(device.iDstring),
                name = "Display ${index + 1} (${bounds.width}×${bounds.height})",
                main = device == main,
                x = bounds.x, y = bounds.y, width = bounds.width, height = bounds.height,
            )
        }
    }.getOrDefault(emptyList())

    override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? {
        val robot = robot ?: return null
        val shot = robot.createScreenCapture(Rectangle(display.x, display.y, display.width, display.height))
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        return ScreenPictures.grab(shot, display, pointer, maxLong)
    }
}

/** Scaling, the pointer, and JPEG, apart from Robot so they can be tested without a screen. */
object ScreenPictures {
    /** A display's ID from the system's name for it, so it stays the same when displays are added or removed. */
    fun displayId(name: String): Long = name.hashCode().toLong() and 0xFFFFFFFFL

    /** [shot] of [display] scaled down to [maxLong], with the pointer drawn where [pointer] is on the desktop. */
    fun grab(shot: BufferedImage, display: PcDisplay, pointer: Point?, maxLong: Int = PcScreen.MAX_LONG_SIDE): ScreenGrab {
        val image = scaled(shot, maxLong)
        if (pointer != null && display.width > 0 && display.height > 0) {
            val x = (pointer.x - display.x) * image.width / display.width.toDouble()
            val y = (pointer.y - display.y) * image.height / display.height.toDouble()
            if (x >= 0 && y >= 0 && x < image.width && y < image.height) drawPointer(image, x, y)
        }
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        return ScreenGrab(image.width, image.height, pixels) { quality -> jpeg(image, quality) }
    }

    /** RGB, the long side at most [maxLong] (kept even), smoothly scaled. */
    fun scaled(shot: BufferedImage, maxLong: Int): BufferedImage {
        val (width, height) = JpegFrames.fit(shot.width, shot.height, maxLong)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            g.drawImage(shot, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** An arrow with its tip at [x], [y]: Robot's picture has no pointer in it. White with a dark edge, seen on anything. */
    fun drawPointer(image: BufferedImage, x: Double, y: Double) {
        val size = (image.height / 45.0).coerceIn(12.0, 26.0)
        val shape = ARROW.let { points ->
            Polygon(
                points.map { (x + it.first * size / 16).toInt() }.toIntArray(),
                points.map { (y + it.second * size / 16).toInt() }.toIntArray(),
                points.size,
            )
        }
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillPolygon(shape)
            g.color = Color(20, 20, 20)
            g.stroke = BasicStroke((size / 14).toFloat().coerceAtLeast(1f))
            g.drawPolygon(shape)
        } finally {
            g.dispose()
        }
    }

    /** The usual pointer arrow, 16 units tall, tip at 0, 0. */
    private val ARROW = listOf(0.0 to 0.0, 0.0 to 15.0, 4.0 to 11.5, 6.5 to 17.0, 9.0 to 16.0, 6.6 to 10.6, 11.5 to 10.6)

    /**
     * One JPEG writer and its settings per thread (each screen sender has its own), made once rather than per frame.
     * A writer that threw is thrown away, since its state is unknown after that.
     */
    private val writers = ThreadLocal<Pair<ImageWriter, ImageWriteParam>?>()

    private fun writer(): Pair<ImageWriter, ImageWriteParam>? = writers.get() ?: run {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT }
        (writer to param).also { writers.set(it) }
    }

    /**
     * [image] as a JPEG at [quality] (1 to 100). Written through a memory-cached stream: ImageIO's default stream
     * would cache each frame in a temp file on disk.
     */
    fun jpeg(image: BufferedImage, quality: Int): ByteArray? {
        val (writer, param) = writer() ?: return null
        val out = ByteArrayOutputStream(64 * 1024)
        return try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                param.compressionQuality = quality.coerceIn(1, 100) / 100f
                writer.write(null, IIOImage(image, null, null), param)
                writer.output = null
            }
            out.toByteArray()
        } catch (_: Exception) {
            writers.remove()
            runCatching { writer.dispose() }
            null
        }
    }
}
