package id.dysklink.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24x24 box, drawn here so the app needs no icon library.
 * They are tinted where they are used, so the black below never shows.
 */
object DyskIcons {
    private fun line(name: String, path: String, width: Float = 2f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()

    private fun solid(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val Phone = line("phone", "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M11 18h2")
    val Laptop = line("laptop", "M5 5h14a1 1 0 0 1 1 1v9H4V6a1 1 0 0 1 1-1z M2 19h20")
    val Monitor = line("monitor", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M8 21h8 M12 17v4")
    val Bell = line("bell", "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9 M13.73 21a2 2 0 0 1-3.46 0")
    val History = line("history", "M3 12a9 9 0 1 0 2.64-6.36L3 8.3 M3 3.5v4.8h4.8 M12 7v5l3.2 2")
    val Sliders = line(
        "sliders",
        "M4 6h8 M18 6h2 M4 12h2 M12 12h8 M4 18h10 M20 18h0 " +
            "M17 6a2 2 0 1 1-4 0a2 2 0 1 1 4 0 M11 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0 M19 18a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
    )
    val Upload = line("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12")
    val ArrowUp = line("arrow-up", "M12 19V5 M5 12l7-7 7 7")
    val ArrowDown = line("arrow-down", "M12 5v14 M19 12l-7 7-7-7")
    val Clipboard = line(
        "clipboard",
        "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 " +
            "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z",
    )
    val Speaker = line("speaker", "M11 5L6 9H2v6h4l5 4V5z M19.07 4.93a10 10 0 0 1 0 14.14 M15.54 8.46a5 5 0 0 1 0 7.07")
    val SpeakerOff = line("speaker-off", "M11 5L6 9H2v6h4l5 4V5z M22 9l-6 6 M16 9l6 6")
    val Grid = line("grid", "M4 4h6v6H4z M14 4h6v6h-6z M14 14h6v6h-6z M4 14h6v6H4z")
    val Folder = line("folder", "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z")
    val Mouse = line("mouse", "M12 2a6 6 0 0 1 6 6v8a6 6 0 0 1-12 0V8a6 6 0 0 1 6-6z M12 6v4")
    val Close = line("close", "M18 6L6 18 M6 6l12 12", width = 2.4f)
    val Check = line("check", "M20 6L9 17l-5-5")
    val Link = line(
        "link",
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    )
    val Info = line("info", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 16v-4 M12 8h0")
    val Power = line("power", "M18.36 6.64a9 9 0 1 1-12.73 0 M12 2v10")
    val Sun = line(
        "sun",
        "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z M12 1v2 M12 21v2 M4.22 4.22l1.42 1.42 M18.36 18.36l1.42 1.42 " +
            "M1 12h2 M21 12h2 M4.22 19.78l1.42-1.42 M18.36 5.64l1.42-1.42",
    )
    val Warning = line("warning", "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z M12 9v4 M12 17h0")
    val Tray = line("tray", "M22 12h-6l-2 3h-4l-2-3H2 M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z")
    val Waves = line("waves", "M8 9a4 4 0 0 0 0 6 M16 9a4 4 0 0 1 0 6 M5 6a8 8 0 0 0 0 12 M19 6a8 8 0 0 1 0 12")
    val Bolt = solid("bolt", "M13 2L4 14h7l-1 8 9-12h-7l1-8z")
    val Camera = line("camera", "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z")
    val Image = line("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z M21 15l-5-5L5 21")
    val Message = line("message", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val Rotate = line("rotate", "M23 4v6h-6 M20.49 15a9 9 0 1 1-2.12-9.36L23 10")
    val Refresh = line("refresh", "M23 4v6h-6 M1 20v-6h6 M3.51 9a9 9 0 0 1 14.85-3.36L23 10 M1 14l4.64 4.36A9 9 0 0 0 20.49 15")
    val Mirror = line("mirror", "M12 3v18 M8 7L3 12l5 5z M16 7l5 5-5 5z")
    val PopOut = line("pop-out", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6 M15 3h6v6 M10 14L21 3")
    val Back = line("back", "M15 18l-6-6 6-6")
    val Home = line("home", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z")
    val Recents = line("recents", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")
    val Pin = line("pin", "M12 17v5 M9 10.76V6h6v4.76l2 2.24v2H7v-2z M8 2h8")
    val Eye = line("eye", "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
    val Send = line("send", "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z")
    val Download = line("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3")
    val Plus = line("plus", "M12 5v14 M5 12h14")
    val Terminal = line("terminal", "M4 17l6-6-6-6 M12 19h8")
    val Keyboard = line("keyboard", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z M6 10h0 M10 10h0 M14 10h0 M18 10h0 M8 14h8")
    val Play = solid("play", "M8 5v14l11-7z")
    val Pause = solid("pause", "M6 5h4v14H6z M14 5h4v14h-4z")
    val Users = line("users", "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M23 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75")
    val Checklist = line("checklist", "M10 6h11 M10 12h11 M10 18h11 M3 6l1.5 1.5L7 5 M3 12l1.5 1.5L7 11 M3 18l1.5 1.5L7 17")
    val Drop = line("drop", "M12 2.7l5.66 5.66a8 8 0 1 1-11.31 0z")
    val Timer = line("timer", "M12 22a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M12 10v4l2.5 1.5 M9 2h6 M12 2v4")
    val Copy = line("copy", "M9 9h11a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
    val File = line("file", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6")
    val ChevronRight = line("chevron-right", "M9 18l6-6-6-6")
    val Pencil = line("pencil", "M17 3l4 4L7 21H3v-4z")
    val Trash = line("trash", "M3 6h18 M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2 M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6")
    val Undo = line("undo", "M9 14L4 9l5-5 M4 9h11a5 5 0 0 1 0 10h-1")
    /** A cable's plug, for USB. */
    val Usb = line("usb", "M9 2h6v5H9z M7 7h10v5a5 5 0 0 1-10 0z M12 17v5 M10.5 4v1 M13.5 4v1")
    val Search = line("search", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M21 21l-4.35-4.35")
    val Battery = line("battery", "M3 7h15a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z M22 11v2")
    val Skip = line("skip", "M5 4l10 8-10 8z M19 5v14")
    val Gamepad = line(
        "gamepad",
        "M7 7h10a5 5 0 0 1 4.9 6l-.8 4a2.5 2.5 0 0 1-4.3 1.2L14.5 16h-5l-2.3 2.2A2.5 2.5 0 0 1 2.9 17l-.8-4A5 5 0 0 1 7 7z M7 10v4 M5 12h4 M15.5 11h0 M17.5 13h0",
    )

    // PC care.
    val Sparkles = line("sparkles", "M12 3l1.8 4.7 4.7 1.8-4.7 1.8L12 16l-1.8-4.7-4.7-1.8 4.7-1.8z M19 15l.8 2.2 2.2.8-2.2.8L19 21l-.8-2.2-2.2-.8 2.2-.8z")
    val Box = line("box", "M21 8l-9-5-9 5v8l9 5 9-5z M3 8l9 5 9-5 M12 13v8")
    val Tool = line(
        "tool",
        "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94z",
    )
    val Drive = line("drive", "M22 12H2 M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z M6 16h0 M10 16h0")
    val FileText = line("file-text", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8")
    val AppWindow = line("app-window", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M2 9h20 M6 6.5h0 M9 6.5h0")
    val Gear = line(
        "gear",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        width = 1.8f,
    )

    /** The DyskLink logo, the same coordinates as ic_logo_glyph.xml and the Mac's LogoGlyph. */
    val Logo: ImageVector = ImageVector.Builder("logo", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M10.574,5.433A7.2,7.2 0,1 0,17.499 7.842"), stroke = SolidColor(Color.White), strokeLineWidth = 1.69f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes("M9.4,14.49L14.6,10.49"), stroke = SolidColor(Color.White), strokeLineWidth = 1.69f, strokeLineCap = StrokeCap.Round)
        .addPath(
            addPathNodes("M7.75,14.49a1.65,1.65 0,1 0,3.3 0a1.65,1.65 0,1 0,-3.3 0ZM12.95,10.49a1.65,1.65 0,1 0,3.3 0a1.65,1.65 0,1 0,-3.3 0Z"),
            fill = SolidColor(Color.White),
        )
        .build()
}
