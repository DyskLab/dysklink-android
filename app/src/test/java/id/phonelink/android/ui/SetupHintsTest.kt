package id.phonelink.android.ui

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupHintsTest {
    @Test
    fun makersThatKillBackgroundAppsGetSteps() {
        assertTrue(makerBackgroundSteps("vivo")!!.contains("Autostart"))
        assertNotNull(makerBackgroundSteps(" Xiaomi "))
        assertTrue(makerBackgroundSteps("OPPO")!!.startsWith("Oppo"))
        assertNotNull(makerBackgroundSteps("samsung"))
    }

    @Test
    fun otherMakersGetNone() {
        assertNull(makerBackgroundSteps("Google"))
        assertNull(makerBackgroundSteps(""))
    }
}
