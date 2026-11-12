package id.phonelink.android.core

import id.phonelink.android.core.BarcodeContent.Contact
import id.phonelink.android.core.BarcodeContent.Email
import id.phonelink.android.core.BarcodeContent.Geo
import id.phonelink.android.core.BarcodeContent.Phone
import id.phonelink.android.core.BarcodeContent.Product
import id.phonelink.android.core.BarcodeContent.Sms
import id.phonelink.android.core.BarcodeContent.Text
import id.phonelink.android.core.BarcodeContent.Url
import id.phonelink.android.core.BarcodeContent.Wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BarcodeContentTest {
    private fun classify(text: String, format: String? = "QR") = BarcodeContent.classify(text, format)

    @Test
    fun urls() {
        assertEquals(Url("https://example.com/a?b=1"), classify("https://example.com/a?b=1"))
        assertEquals(Url("HTTP://EXAMPLE.COM"), classify("HTTP://EXAMPLE.COM"))
        assertEquals(Url("https://www.example.com"), classify("www.example.com"))
        // Only web links are links; other schemes and text with spaces stay text.
        assertIs<Text>(classify("javascript:alert(1)"))
        assertIs<Text>(classify("file:///sdcard/a"))
        assertIs<Text>(classify("https://example.com and more"))
    }

    @Test
    fun wifi() {
        assertEquals(Wifi("Home", "secret", "WPA", false), classify("WIFI:T:WPA;S:Home;P:secret;;"))
        assertEquals(Wifi("Cafe", null, "nopass", false), classify("WIFI:T:nopass;S:Cafe;;"))
        assertEquals(Wifi("Hidden", "x", "WPA", true), classify("WIFI:S:Hidden;P:x;H:true;;"))
        // Order doesn't matter, and \ ; , : " are escaped with a backslash.
        assertEquals(
            Wifi("My;Net,1", "pa:ss\\word\"", "WEP", false),
            classify("""WIFI:P:pa\:ss\\word\";S:My\;Net\,1;T:WEP;;"""),
        )
        assertEquals("Quoted", (classify("WIFI:T:WPA;S:\"Quoted\";P:\"pw\";;") as Wifi).ssid)
        assertTrue((classify("WIFI:S:Open;;") as Wifi).isOpen)
        assertIs<Text>(classify("WIFI:T:WPA;P:nossid;;"))
    }

    @Test
    fun mecard() {
        val card = classify("MECARD:N:Doe,John;TEL:+62 812 3456;TEL:021 555;EMAIL:john@example.com;ORG:Acme\\, Inc;ADR:Jl. Merdeka 1;;") as Contact
        assertEquals("John Doe", card.name)
        assertEquals(listOf("+62 812 3456", "021 555"), card.phones)
        assertEquals(listOf("john@example.com"), card.emails)
        assertEquals("Acme, Inc", card.organization)
        assertEquals("Jl. Merdeka 1", card.address)
    }

    @Test
    fun vcard() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;Dr.;\r\nFN:Jane Doe\r\nORG:Acme\r\n" +
            "TEL;TYPE=CELL:+1 555 0100\r\nitem1.EMAIL;TYPE=INTERNET:jane@\r\n example.com\r\n" +
            "ADR;TYPE=WORK:;;1 Main St;Springfield;;12345;USA\r\nNOTE:Line one\\nLine two\r\nEND:VCARD"
        val card = classify(text) as Contact
        assertEquals("Jane Doe", card.name)
        assertEquals(listOf("+1 555 0100"), card.phones)
        assertEquals(listOf("jane@example.com"), card.emails)
        assertEquals("Acme", card.organization)
        assertEquals("1 Main St, Springfield, 12345, USA", card.address)
        assertEquals("Line one\nLine two", card.note)
        assertEquals("Dr. Jane Doe", (classify("BEGIN:VCARD\nN:Doe;Jane;;Dr.;\nEND:VCARD") as Contact).name)
    }

    @Test
    fun phoneSmsEmailGeo() {
        assertEquals(Phone("+62 812-3456"), classify("tel:+62 812-3456"))
        assertIs<Text>(classify("tel:hello"))
        assertEquals(Sms("+628123", "Hi there"), classify("SMSTO:+628123:Hi there"))
        assertEquals(Sms("+628123", "Hi there"), classify("sms:+628123?body=Hi%20there"))
        assertEquals(Sms("08123", null), classify("sms:08123"))
        assertEquals(Email("a@b.com", "Hello", "Body text"), classify("mailto:a@b.com?subject=Hello&body=Body%20text"))
        assertEquals(Email("a@b.com", null, null), classify("MAILTO:a@b.com"))
        assertEquals(Email("a@b.com", "S", "B"), classify("MATMSG:TO:a@b.com;SUB:S;BODY:B;;"))
        assertEquals(Geo(-6.2, 106.8166, null), classify("geo:-6.2,106.8166"))
        assertEquals(Geo(1.5, 2.5, "Cafe"), classify("geo:1.5,2.5?q=Cafe"))
        assertIs<Text>(classify("geo:200,1"))
    }

    @Test
    fun productsAndText() {
        assertEquals(Product("4006381333931"), classify("4006381333931", "EAN-13"))
        assertEquals(Product("96385074"), classify("96385074", "EAN-8"))
        // A number in a QR code is a product only when its check digit fits and the format is unknown.
        assertEquals(Product("4006381333931"), classify("4006381333931", null))
        assertIs<Text>(classify("4006381333932", null))
        assertIs<Text>(classify("4006381333931", "QR"))
        assertEquals(Text("Hello world"), classify("  Hello world "))
        assertTrue(BarcodeContent.validGtin("036000291452"))
        assertFalse(BarcodeContent.validGtin("12345"))
    }
}
