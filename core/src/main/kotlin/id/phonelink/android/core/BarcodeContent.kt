package id.phonelink.android.core

import java.net.URLDecoder

/** What a scanned code holds, so the phone can offer the right actions. Nothing here opens or dials anything. */
sealed class BarcodeContent {
    abstract val label: String

    data class Url(val url: String) : BarcodeContent() { override val label = "Link" }

    data class Wifi(val ssid: String, val password: String?, val security: String, val hidden: Boolean) : BarcodeContent() {
        override val label = "Wi-Fi"
        /** No password, or "nopass". */
        val isOpen: Boolean get() = security == "nopass" || password.isNullOrEmpty()
    }

    data class Contact(
        val name: String?,
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val organization: String? = null,
        val address: String? = null,
        val url: String? = null,
        val note: String? = null,
    ) : BarcodeContent() { override val label = "Contact" }

    data class Phone(val number: String) : BarcodeContent() { override val label = "Phone number" }

    data class Sms(val number: String, val body: String?) : BarcodeContent() { override val label = "SMS" }

    data class Email(val address: String, val subject: String?, val body: String?) : BarcodeContent() { override val label = "Email" }

    data class Geo(val latitude: Double, val longitude: Double, val query: String?) : BarcodeContent() { override val label = "Location" }

    data class Product(val code: String) : BarcodeContent() { override val label = "Product code" }

    data class Text(val text: String) : BarcodeContent() { override val label = "Text" }

    companion object {
        /** Barcode formats that carry product numbers, as named by the scan screen. */
        val PRODUCT_FORMATS = setOf("EAN-13", "EAN-8", "UPC-A", "UPC-E")

        fun classify(raw: String, format: String? = null): BarcodeContent {
            val text = raw.trim()
            val upper = text.uppercase()
            return when {
                format in PRODUCT_FORMATS && text.all(Char::isDigit) -> Product(text)
                upper.startsWith("WIFI:") -> wifi(text) ?: Text(text)
                upper.startsWith("MECARD:") -> mecard(text) ?: Text(text)
                upper.startsWith("BEGIN:VCARD") -> vcard(text) ?: Text(text)
                upper.startsWith("TEL:") -> text.substring(4).trim().takeIf(::looksLikeNumber)?.let(::Phone) ?: Text(text)
                upper.startsWith("SMSTO:") || upper.startsWith("SMS:") || upper.startsWith("MMSTO:") -> sms(text) ?: Text(text)
                upper.startsWith("MAILTO:") -> mailto(text) ?: Text(text)
                upper.startsWith("MATMSG:") -> matmsg(text) ?: Text(text)
                upper.startsWith("GEO:") -> geo(text) ?: Text(text)
                upper.startsWith("HTTP://") || upper.startsWith("HTTPS://") -> if (text.any(Char::isWhitespace)) Text(text) else Url(text)
                upper.startsWith("WWW.") && text.none(Char::isWhitespace) && '.' in text.substring(4) -> Url("https://$text")
                format == null && text.length in setOf(8, 12, 13) && text.all(Char::isDigit) && validGtin(text) -> Product(text)
                else -> Text(text)
            }
        }

        /** The last digit of an EAN or UPC is a check digit over the others. */
        fun validGtin(code: String): Boolean {
            if (code.length < 8 || code.any { !it.isDigit() }) return false
            val digits = code.map { it - '0' }
            val sum = digits.dropLast(1).reversed().mapIndexed { index, d -> if (index % 2 == 0) d * 3 else d }.sum()
            return (10 - sum % 10) % 10 == digits.last()
        }

        private fun looksLikeNumber(text: String) = text.isNotEmpty() && text.any(Char::isDigit) &&
            text.all { it.isDigit() || it in "+-() .*#" }

        /** Splits "K:v;K:v;;" on unescaped [separator], dropping the backslashes that escape \ ; , : and ". */
        internal fun splitEscaped(text: String, separator: Char = ';'): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            for (char in text) {
                when {
                    escaped -> { current.append(char); escaped = false }
                    char == '\\' -> escaped = true
                    char == separator -> { parts += current.toString(); current.clear() }
                    else -> current.append(char)
                }
            }
            if (escaped) current.append('\\')
            if (current.isNotEmpty()) parts += current.toString()
            return parts
        }

        /** Fields of a WIFI: or MECARD: code. Keys are upper-case; a key may repeat. Escapes are only resolved after the key. */
        private fun fields(body: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            // Split on unescaped ';' but keep escapes in values until the key is cut off, so "S:a\:b" keeps its colon.
            val raw = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            for (char in body) {
                when {
                    escaped -> { current.append('\\').append(char); escaped = false }
                    char == '\\' -> escaped = true
                    char == ';' -> { raw += current.toString(); current.clear() }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) raw += current.toString()
            for (part in raw) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                out += part.substring(0, colon).trim().uppercase() to unescape(part.substring(colon + 1))
            }
            return out
        }

        private fun unescape(value: String): String {
            val out = StringBuilder()
            var escaped = false
            for (char in value) {
                if (escaped) { out.append(char); escaped = false } else if (char == '\\') escaped = true else out.append(char)
            }
            return out.toString()
        }

        private fun unquote(value: String): String =
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value

        private fun wifi(text: String): Wifi? {
            val map = fields(text.substring(5)).toMap()
            val ssid = map["S"]?.let(::unquote)?.takeIf { it.isNotEmpty() } ?: return null
            val type = map["T"]?.trim().orEmpty()
            val password = map["P"]?.let(::unquote)?.takeIf { it.isNotEmpty() }
            val security = when {
                type.equals("nopass", true) || (type.isEmpty() && password == null) -> "nopass"
                type.isEmpty() -> "WPA"
                else -> type.uppercase()
            }
            return Wifi(ssid, if (security == "nopass") null else password, security, map["H"]?.trim().equals("true", true))
        }

        private fun mecard(text: String): Contact? {
            val all = fields(text.substring(7))
            fun first(key: String) = all.firstOrNull { it.first == key }?.second?.trim()?.takeIf { it.isNotEmpty() }
            fun every(key: String) = all.filter { it.first == key }.map { it.second.trim() }.filter { it.isNotEmpty() }
            val name = first("N")?.let { n ->
                val parts = n.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size == 2) "${parts[1]} ${parts[0]}" else parts.joinToString(" ")
            }
            val contact = Contact(name, every("TEL"), every("EMAIL"), first("ORG"), first("ADR"), first("URL"), first("NOTE"))
            return contact.takeIf { it.name != null || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        }

        private fun vcardValue(value: String): String = buildString {
            var escaped = false
            for (char in value) {
                if (escaped) {
                    append(if (char == 'n' || char == 'N') '\n' else char)
                    escaped = false
                } else if (char == '\\') escaped = true else append(char)
            }
        }

        private fun vcard(text: String): Contact? {
            // Lines starting with a space or tab continue the line before.
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n[ \t]"), "").split('\n')
            var fn: String? = null
            var n: String? = null
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            var org: String? = null
            var adr: String? = null
            var url: String? = null
            var note: String? = null
            for (line in lines) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).substringBefore(';').substringAfterLast('.').trim().uppercase()
                val value = line.substring(colon + 1)
                when (key) {
                    "FN" -> fn = vcardValue(value).trim()
                    "N" -> n = splitEscaped(value).map { vcardValue(it).trim() }.let { parts ->
                        listOfNotNull(parts.getOrNull(3), parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(0), parts.getOrNull(4))
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    }
                    "TEL" -> vcardValue(value).trim().removePrefix("tel:").takeIf { it.isNotEmpty() }?.let(phones::add)
                    "EMAIL" -> vcardValue(value).trim().takeIf { it.isNotEmpty() }?.let(emails::add)
                    "ORG" -> org = splitEscaped(value).joinToString(", ") { vcardValue(it).trim() }.trim(',', ' ')
                    "ADR" -> adr = splitEscaped(value).map { vcardValue(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    "URL" -> url = vcardValue(value).trim()
                    "NOTE" -> note = vcardValue(value).trim()
                }
            }
            val name = fn?.takeIf { it.isNotEmpty() } ?: n?.takeIf { it.isNotEmpty() }
            val contact = Contact(name, phones, emails, org?.ifEmpty { null }, adr?.ifEmpty { null }, url?.ifEmpty { null }, note?.ifEmpty { null })
            return contact.takeIf { it.name != null || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

        private fun query(text: String): Map<String, String> = text.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).lowercase() to decode(pair.substring(eq + 1))
        }.toMap()

        private fun sms(text: String): Sms? {
            val upper = text.uppercase()
            return if (upper.startsWith("SMSTO:") || upper.startsWith("MMSTO:")) {
                // SMSTO:number:message
                val rest = text.substring(6)
                val number = rest.substringBefore(':').trim()
                val body = rest.substringAfter(':', "").takeIf { it.isNotEmpty() }
                number.takeIf(::looksLikeNumber)?.let { Sms(it, body) }
            } else {
                // sms:number?body=message
                val rest = text.substring(4)
                val number = decode(rest.substringBefore('?')).trim()
                val body = query(rest.substringAfter('?', ""))["body"]
                number.takeIf(::looksLikeNumber)?.let { Sms(it, body) }
            }
        }

        private fun mailto(text: String): Email? {
            val rest = text.substring(7)
            val address = decode(rest.substringBefore('?')).trim()
            if ('@' !in address) return null
            val params = query(rest.substringAfter('?', ""))
            return Email(address, params["subject"], params["body"])
        }

        private fun matmsg(text: String): Email? {
            val map = fields(text.substring(7)).toMap()
            val address = map["TO"]?.trim()?.takeIf { '@' in it } ?: return null
            return Email(address, map["SUB"]?.ifEmpty { null }, map["BODY"]?.ifEmpty { null })
        }

        private fun geo(text: String): Geo? {
            val rest = text.substring(4)
            val coords = rest.substringBefore('?').substringBefore(';').split(',')
            val lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val lon = coords.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Geo(lat, lon, query(rest.substringAfter('?', ""))["q"]?.ifEmpty { null })
        }
    }
}
