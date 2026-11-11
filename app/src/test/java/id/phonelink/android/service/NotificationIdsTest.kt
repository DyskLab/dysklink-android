package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationIdsTest {
    @Test
    fun everyNotificationHasItsOwnId() {
        assertEquals(NotificationIds.all.size, NotificationIds.all.toSet().size)
    }
}
