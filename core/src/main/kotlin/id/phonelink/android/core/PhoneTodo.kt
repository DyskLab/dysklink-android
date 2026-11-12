package id.phonelink.android.core

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Times are epoch milliseconds. A task is done once [doneAt] is set. */
@Serializable
data class TodoItem(val id: String, val title: String, val createdAt: Long, val doneAt: Long? = null) {
    val isDone: Boolean get() = doneAt != null
}

/**
 * The phone's own task list, kept on the phone only. Same rules as the Mac notch's list: one-line titles, and tasks
 * finished on an earlier day are dropped so each day starts with what is still open. Unlike the Mac, open tasks
 * keep the order the user gives them.
 */
@Serializable
data class TodoList(val items: List<TodoItem> = emptyList()) {
    val open: List<TodoItem> get() = items.filter { !it.isDone }
    val left: Int get() = items.count { !it.isDone }

    /** Null when the text is empty or the list is full. */
    fun add(text: String, now: Long, id: String = UUID.randomUUID().toString()): TodoList? {
        val title = clean(text)
        if (title.isEmpty() || items.size >= MAX_ITEMS) return null
        return copy(items = items + TodoItem(id, title, now))
    }

    /** An empty title leaves the task as it was. */
    fun rename(id: String, text: String): TodoList {
        val title = clean(text)
        if (title.isEmpty()) return this
        return copy(items = items.map { if (it.id == id) it.copy(title = title) else it })
    }

    fun toggle(id: String, now: Long): TodoList =
        copy(items = items.map { if (it.id == id) it.copy(doneAt = if (it.isDone) null else now) else it })

    fun delete(id: String): TodoList = copy(items = items.filterNot { it.id == id })

    fun clearDone(): TodoList = copy(items = items.filterNot { it.isDone })

    /** Moves an open task from one place in [open] to another. Done tasks keep their places. */
    fun moveOpen(from: Int, to: Int): TodoList {
        val open = open.toMutableList()
        if (from !in open.indices || to !in open.indices || from == to) return this
        open.add(to, open.removeAt(from))
        val reordered = open.iterator()
        return copy(items = items.map { if (it.isDone) it else reordered.next() })
    }

    /** Drops tasks finished before today began. */
    fun dropOld(now: Long, zone: ZoneId): TodoList {
        val start = startOfDay(now, zone)
        val kept = items.filter { (it.doneAt ?: Long.MAX_VALUE) >= start }
        return if (kept.size == items.size) this else copy(items = kept)
    }

    /** Open tasks in the user's order, then what was finished today, newest first. */
    fun visible(now: Long, zone: ZoneId): List<TodoItem> {
        val start = startOfDay(now, zone)
        return open + items.filter { it.isDone && it.doneAt!! >= start }.sortedByDescending { it.doneAt }
    }

    fun doneToday(now: Long, zone: ZoneId): Int {
        val start = startOfDay(now, zone)
        return items.count { (it.doneAt ?: -1) >= start }
    }

    /** "3 left", "All done", or "No tasks", for the home card. */
    fun summary(now: Long, zone: ZoneId): String = when {
        left > 0 -> "$left left" + doneToday(now, zone).let { if (it > 0) " · $it done today" else "" }
        doneToday(now, zone) > 0 -> "All done · ${doneToday(now, zone)} today"
        else -> "No tasks. Tap to add one."
    }

    fun encode(): String = PhoneToolsJson.json.encodeToString(serializer(), this)

    companion object {
        const val MAX_TITLE = 120
        const val MAX_ITEMS = 100

        /** One line, trimmed, and no longer than the limit. */
        fun clean(text: String): String =
            text.trim().lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE)

        fun decode(text: String?): TodoList =
            text?.let { runCatching { PhoneToolsJson.json.decodeFromString(serializer(), it) }.getOrNull() } ?: TodoList()
    }
}

internal object PhoneToolsJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

internal fun startOfDay(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
