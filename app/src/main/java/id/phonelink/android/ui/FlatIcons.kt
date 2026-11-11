package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Flat, one-color icons used across the app instead of emoji. */
object FlatIcons {
    /** Icons a Stream Deck button can use, saved by name. */
    val deck: List<Pair<String, ImageVector>> = listOf(
        "copy" to Icons.Rounded.ContentCopy,
        "paste" to Icons.Rounded.ContentPaste,
        "cut" to Icons.Rounded.ContentCut,
        "undo" to Icons.AutoMirrored.Rounded.Undo,
        "redo" to Icons.AutoMirrored.Rounded.Redo,
        "search" to Icons.Rounded.Search,
        "windows" to Icons.Rounded.Dashboard,
        "screenshot" to Icons.Rounded.Screenshot,
        "play" to Icons.Rounded.PlayArrow,
        "pause" to Icons.Rounded.Pause,
        "previous" to Icons.Rounded.SkipPrevious,
        "next" to Icons.Rounded.SkipNext,
        "volume_down" to Icons.AutoMirrored.Rounded.VolumeDown,
        "volume_up" to Icons.AutoMirrored.Rounded.VolumeUp,
        "mute" to Icons.AutoMirrored.Rounded.VolumeOff,
        "lock" to Icons.Rounded.Lock,
        "live" to Icons.Rounded.Sensors,
        "record" to Icons.Rounded.FiberManualRecord,
        "camera" to Icons.Rounded.Videocam,
        "photo" to Icons.Rounded.PhotoCamera,
        "mic" to Icons.Rounded.Mic,
        "mic_off" to Icons.Rounded.MicOff,
        "scene" to Icons.Rounded.Movie,
        "show" to Icons.Rounded.Visibility,
        "transition" to Icons.Rounded.SwapHoriz,
        "replay" to Icons.Rounded.Replay,
        "save" to Icons.Rounded.Save,
        "folder" to Icons.Rounded.Folder,
        "web" to Icons.Rounded.Language,
        "mail" to Icons.Rounded.Email,
        "chat" to Icons.AutoMirrored.Rounded.Chat,
        "keyboard" to Icons.Rounded.Keyboard,
        "terminal" to Icons.Rounded.Terminal,
        "code" to Icons.Rounded.Code,
        "settings" to Icons.Rounded.Settings,
        "music" to Icons.Rounded.MusicNote,
        "palette" to Icons.Rounded.Palette,
        "light" to Icons.Rounded.Lightbulb,
        "bolt" to Icons.Rounded.Bolt,
        "timer" to Icons.Rounded.Timer,
        "repeat" to Icons.Rounded.Repeat,
        "clean" to Icons.Rounded.CleaningServices,
        "desktop" to Icons.Rounded.DesktopMac,
        "rocket" to Icons.Rounded.RocketLaunch,
        "done" to Icons.Rounded.CheckCircle,
        "star" to Icons.Rounded.Star,
        "heart" to Icons.Rounded.Favorite,
        "fire" to Icons.Rounded.LocalFireDepartment,
    )

    private val byName = deck.toMap()

    fun named(name: String): ImageVector? = byName[name]

    val touchpad = Icons.Rounded.Mouse
    val media = Icons.Rounded.MusicNote
    val presentation = Icons.Rounded.Slideshow
    val commands = Icons.Rounded.Terminal
    val notch = Icons.Rounded.Checklist
    val deckGrid = Icons.Rounded.GridView
    val scan = Icons.Rounded.QrCodeScanner
    val mic = Icons.Rounded.Mic
    val stop = Icons.Rounded.Stop
    val phone = Icons.Rounded.PhoneAndroid
    val sendFile = Icons.Rounded.UploadFile
    val shareScreen = Icons.Rounded.ScreenShare
    val stopShare = Icons.Rounded.StopScreenShare
    val ring = Icons.Rounded.NotificationsActive
    val ringOff = Icons.Rounded.NotificationsOff
    val rain = Icons.Rounded.WaterDrop

    fun file(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp" -> Icons.Rounded.Image
        "mp4", "mov", "mkv", "avi", "webm", "3gp" -> Icons.Rounded.Movie
        "mp3", "m4a", "wav", "aac", "flac", "ogg", "opus" -> Icons.Rounded.AudioFile
        "pdf" -> Icons.Rounded.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
        "apk" -> Icons.Rounded.Android
        "doc", "docx", "txt", "rtf", "pages", "md" -> Icons.Rounded.Description
        "xls", "xlsx", "csv", "numbers" -> Icons.Rounded.TableChart
        "ppt", "pptx", "key" -> Icons.Rounded.Slideshow
        else -> Icons.Rounded.InsertDriveFile
    }

    /** By the guessed device kind from the network scan. */
    fun device(kind: String): ImageVector = when (kind) {
        "This phone" -> Icons.Rounded.PhoneAndroid
        "Router" -> Icons.Rounded.Router
        "Mac with DyskLink" -> Icons.Rounded.Laptop
        "iPhone or iPad" -> Icons.Rounded.PhoneIphone
        "Chromecast or TV" -> Icons.Rounded.Tv
        "Printer" -> Icons.Rounded.Print
        "Camera" -> Icons.Rounded.Videocam
        "AirPlay device" -> Icons.Rounded.Speaker
        "Computer" -> Icons.Rounded.Computer
        "Server" -> Icons.Rounded.Dns
        "NAS or server" -> Icons.Rounded.Storage
        "Device with a web page" -> Icons.Rounded.Language
        else -> Icons.Rounded.Devices
    }

    /** WMO weather codes, as Open-Meteo sends them. */
    fun weather(code: Int, isDay: Boolean = true): ImageVector = when (code) {
        0 -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime
        1, 2 -> if (isDay) Icons.Rounded.FilterDrama else Icons.Rounded.Cloud
        3, 45, 48 -> Icons.Rounded.Cloud
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.WaterDrop
        71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
        95, 96, 99 -> Icons.Rounded.Thunderstorm
        else -> Icons.Rounded.Thermostat
    }
}

/** An icon on a soft square of its own color, the app's one icon style. */
@Composable
fun FlatIcon(icon: ImageVector, tint: Color, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}
