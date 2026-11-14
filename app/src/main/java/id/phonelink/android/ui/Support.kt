package id.phonelink.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * "Report a problem": opens WhatsApp to the developer with a short message the user can edit.
 * The message holds only the app version, Android version, phone maker and model, and how many computers are connected.
 * It never holds device names, account names, addresses, pairing IDs, or paths.
 */
object Support {
    /** The developer's WhatsApp number, in wa.me form (country code, no plus or spaces). */
    const val WHATSAPP_NUMBER = "6285117241230"
    /** The same number as people read it. */
    const val WHATSAPP_NUMBER_DISPLAY = "+62 851-1724-1230"

    const val REPORT_TITLE = "Report a problem"
    const val REPORT_DETAIL = "Send a message on WhatsApp. It includes the app version and system so the problem is easier to find; you can edit it before sending."
    const val FAILURE_MESSAGE = "Couldn't open WhatsApp. Message $WHATSAPP_NUMBER_DISPLAY instead."

    data class Facts(
        val appVersion: String,
        val versionCode: Long,
        val androidVersion: String,
        val sdk: Int,
        val manufacturer: String,
        val model: String,
        val connectedComputers: Int,
        /** Names that must never appear in the message, such as the phone's name. */
        val privateNames: List<String> = emptyList(),
    )

    fun message(facts: Facts): String {
        val computers = if (facts.connectedComputers == 1) "1 computer" else "${facts.connectedComputers} computers"
        // Many models already start with the maker's name ("Pixel" doesn't, "SM-..." doesn't, "vivo 1902" does).
        val device = if (facts.model.startsWith(facts.manufacturer, ignoreCase = true)) facts.model
        else "${facts.manufacturer.replaceFirstChar { it.uppercase() }} ${facts.model}".trim()
        val text = "Hi, I found a problem in DyskLink.\n\n" +
            "What happened:\n\n" +
            "What I expected:\n\n" +
            "---\n" +
            "DyskLink for Android ${facts.appVersion} (${facts.versionCode})\n" +
            "Android ${facts.androidVersion} (API ${facts.sdk}), $device\n" +
            "Connected: $computers"
        return scrub(text, facts.privateNames)
    }

    /** Takes out any private name that slipped into a field, such as a model the user renamed after themselves. */
    fun scrub(text: String, names: List<String>): String =
        names.map { it.trim() }.filter { it.length >= 2 }
            .fold(text) { acc, name -> acc.replace(name, "[hidden]", ignoreCase = true) }

    /** Encodes everything but letters, digits, and `-._~`, so newlines, `&`, `#`, and `+` survive in the query. */
    fun url(message: String): String {
        val encoded = buildString {
            message.toByteArray(Charsets.UTF_8).forEach { byte ->
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c)
                else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return "https://wa.me/$WHATSAPP_NUMBER?text=$encoded"
    }

    fun currentFacts(context: Context, connectedComputers: Int): Facts {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        val phoneName = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return Facts(
            appVersion = info?.versionName ?: "?",
            versionCode = code,
            androidVersion = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            connectedComputers = connectedComputers,
            privateNames = listOfNotNull(phoneName),
        )
    }

    /** Opens the link; WhatsApp takes wa.me links, and a browser does without it. False when nothing could open it. */
    fun report(context: Context, connectedComputers: Int): Boolean {
        val link = url(message(currentFacts(context, connectedComputers)))
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun copyNumber(context: Context) {
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("WhatsApp number", WHATSAPP_NUMBER_DISPLAY))
        }
    }
}
