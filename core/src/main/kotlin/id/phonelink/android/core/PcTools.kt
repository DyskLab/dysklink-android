package id.phonelink.android.core

import java.io.File
import java.text.Normalizer
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

// The Mac notch's tools on a Windows or Linux PC: a Todo list, water with reminders, a Pomodoro, and the quick note,
// with the Mac's data model and rules (NotchSync, TodoList, WaterLog, Pomodoro). The PC keeps the data, a phone shows
// it through `notch.state` and asks for changes with `notch.action`, as with a Mac.

/** Everything the Tools page shows. */
data class PcToolsState(
    val todos: TodoList = TodoList(),
    val water: WaterLog = WaterLog(),
    val pomodoro: PomodoroSaved = PomodoroSaved(),
    val note: String? = null,
    val noteTime: Long? = null,
)

/** What a [PcNotch.tick] found: a Pomodoro step that ended, a water reminder that is due. */
data class PcToolsTick(val finished: PomodoroPhase? = null, val next: PomodoroState? = null, val remindWater: Boolean = false, val changed: Boolean = false)

@Serializable
private data class SavedNote(val text: String = "", val time: Long? = null)

/**
 * The PC's Todo, water, Pomodoro, and note, saved in [directory]. Every change is saved at once; [tools] is the state
 * to show. [clock] and [zone] are there for tests.
 */
class PcNotch(
    directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val todoFile = File(directory, "notch-todos.json")
    private val waterFile = File(directory, "notch-water.json")
    private val pomodoroFile = File(directory, "notch-pomodoro.json")
    private val noteFile = File(directory, "notch-note.json")
    private val lock = Any()

    private val _tools = MutableStateFlow(load())
    val tools: StateFlow<PcToolsState> = _tools.asStateFlow()

    private fun load(): PcToolsState {
        val now = clock()
        val note = runCatching { PhoneToolsJson.json.decodeFromString(SavedNote.serializer(), noteFile.readText()) }.getOrNull()
        return PcToolsState(
            todos = TodoList.decode(read(todoFile)).dropOld(now, zone()),
            water = WaterLog.decode(read(waterFile)).dropOld(now, zone()),
            pomodoro = PomodoroSaved.decode(read(pomodoroFile)).let { it.copy(state = it.state.catchUp(now, it.settings).first) },
            note = note?.text?.takeIf { it.isNotEmpty() },
            noteTime = note?.time,
        )
    }

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    /** Changes the state, saves what changed, and returns whether anything did. */
    private fun change(block: (PcToolsState) -> PcToolsState): Boolean = synchronized(lock) {
        val before = _tools.value
        val after = block(before)
        if (after == before) return false
        _tools.value = after
        if (after.todos != before.todos) write(todoFile, after.todos.encode())
        if (after.water != before.water) write(waterFile, after.water.encode())
        if (after.pomodoro != before.pomodoro) write(pomodoroFile, after.pomodoro.encode())
        if (after.note != before.note) write(noteFile, PhoneToolsJson.json.encodeToString(SavedNote.serializer(), SavedNote(after.note.orEmpty(), after.noteTime)))
        true
    }

    // Todo, with the Mac's limits: one line of 80 characters, 50 tasks.

    /** False when the title is empty or the list is full. */
    fun addTodo(text: String): Boolean {
        val title = cleanTitle(text)
        if (title.isEmpty()) return false
        var added = false
        change { tools ->
            val todos = tools.todos.dropOld(clock(), zone())
            val grown = if (todos.items.size >= MAX_TODOS) null else todos.add(title, clock())
            added = grown != null
            tools.copy(todos = grown ?: todos)
        }
        return added
    }

    fun toggleTodo(id: String) = change { it.copy(todos = it.todos.toggle(id, clock())) }
    fun deleteTodo(id: String) = change { it.copy(todos = it.todos.delete(id)) }
    fun renameTodo(id: String, text: String) = change { it.copy(todos = it.todos.rename(id, cleanTitle(text))) }
    fun clearDone() = change { it.copy(todos = it.todos.clearDone()) }
    val todosFull: Boolean get() = _tools.value.todos.items.size >= MAX_TODOS

    // Water

    fun drink() = change { it.copy(water = it.water.add(it.water.settings.glassMl, clock()).dropOld(clock(), zone())) }
    fun undoDrink() = change { it.copy(water = it.water.undo(clock(), zone())) }
    fun setWater(settings: WaterSettings) = change { it.copy(water = it.water.copy(settings = settings.checked())) }

    // Pomodoro

    /** Starts or pauses, as the notch's play button. */
    fun togglePomodoro() = change { tools ->
        val now = clock()
        val settings = tools.pomodoro.settings
        val state = tools.pomodoro.state
        tools.copy(pomodoro = tools.pomodoro.copy(state = if (state.isRunning) state.pause(now, settings) else state.start(now, settings)))
    }

    fun resetPomodoro() = change { it.copy(pomodoro = it.pomodoro.copy(state = PomodoroState())) }
    fun skipPomodoro() = change { it.copy(pomodoro = it.pomodoro.copy(state = it.pomodoro.state.skip(clock(), it.pomodoro.settings))) }

    /** New lengths; a timer that hasn't started yet takes them at once, a running one with its next step. */
    fun setPomodoro(settings: PomodoroSettings) = change { tools ->
        val checked = settings.checked()
        tools.copy(pomodoro = tools.pomodoro.copy(settings = checked, state = if (tools.pomodoro.state.isStarted) tools.pomodoro.state else PomodoroState()))
    }

    // Note

    fun setNote(text: String) = change { it.copy(note = text.take(MAX_NOTE).ifEmpty { null }, noteTime = clock()) }

    /**
     * Moves the Pomodoro on when a step ended (catching up after sleep), drops tasks done on an earlier day, and says
     * whether a water reminder is due, which counts as given. Called about once a second.
     */
    fun tick(): PcToolsTick {
        val now = clock()
        var finished: PomodoroPhase? = null
        var remind = false
        val changed = change { tools ->
            val (state, ended) = tools.pomodoro.state.catchUp(now, tools.pomodoro.settings)
            finished = ended
            var water = tools.water.dropOld(now, zone())
            if (water.isReminderDue(now, zone())) {
                remind = true
                water = water.copy(lastReminder = now)
            }
            tools.copy(todos = tools.todos.dropOld(now, zone()), water = water, pomodoro = tools.pomodoro.copy(state = state))
        }
        return PcToolsTick(finished, _tools.value.pomodoro.state.takeIf { finished != null }, remind, changed)
    }

    /** What phones get in `notch.state`, as the Mac's NotchSync makes it. */
    fun notchState(): NotchState {
        val now = clock()
        val tools = _tools.value
        val settings = tools.pomodoro.settings
        val state = tools.pomodoro.state
        return NotchState(
            todos = tools.todos.visible(now, zone()).map { NotchTask(it.id, it.title, it.isDone) },
            water = NotchWater(tools.water.totalToday(now, zone()), tools.water.settings.goalMl, tools.water.settings.glassMl),
            focus = NotchFocus(
                phase = phaseName(state.phase), session = state.session, sessions = settings.sessions, running = state.isRunning,
                remainingMs = state.remaining(now, settings), lengthMs = settings.lengthMs(state.phase),
            ),
            note = tools.note,
            noteTime = tools.noteTime,
        )
    }

    /** A `notch.action` from a phone, as the Mac's NotchSync.apply. Returns whether anything changed. */
    fun apply(action: NotchAction): Boolean {
        val id = action.id?.takeIf { it.isNotEmpty() && it.length <= 64 }
        val text = action.text?.take(MAX_ACTION_TEXT)
        return when (action.kind) {
            "todo.add" -> text != null && addTodo(text)
            "todo.toggle" -> id != null && toggleTodo(id)
            "todo.delete" -> id != null && deleteTodo(id)
            "todo.rename" -> id != null && text != null && renameTodo(id, text)
            "note.set" -> text != null && setNote(text)
            "water.drink" -> drink()
            "water.undo" -> undoDrink()
            "focus.toggle" -> togglePomodoro()
            "focus.reset" -> resetPomodoro()
            else -> false
        }
    }

    companion object {
        /** As the Mac's TodoList. */
        const val MAX_TITLE = 80
        const val MAX_TODOS = 50
        const val MAX_NOTE = 4_000
        private const val MAX_ACTION_TEXT = 8_000

        fun cleanTitle(text: String): String = TodoList.clean(text).take(MAX_TITLE).trim()

        fun phaseName(phase: PomodoroPhase): String = when (phase) {
            PomodoroPhase.Focus -> "focus"
            PomodoroPhase.ShortBreak -> "shortBreak"
            PomodoroPhase.LongBreak -> "longBreak"
        }

        /** The notification when a step ends, as the Mac's: what ended and what comes now. */
        fun finishedText(finished: PomodoroPhase, next: PomodoroState?, settings: PomodoroSettings): Pair<String, String> = when (finished) {
            PomodoroPhase.Focus -> "Focus done" to if (next?.phase == PomodoroPhase.LongBreak) "Time for a long break." else "Time for a short break."
            PomodoroPhase.ShortBreak -> "Break over" to "Focus ${next?.session ?: 1} of ${settings.sessions} starts now."
            PomodoroPhase.LongBreak -> "Pomodoro cycle done" to "Start again when you're ready."
        }
    }
}

/**
 * Searching the phone's contacts as the Mac does (PhoneSearch): case and accents don't count, a name matches at the
 * start of each word, and 0812…, 62812… and +62 812-… are the same number.
 */
object ContactSearch {
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun digits(text: String): String = text.filter { it in '0'..'9' }

    /** The number without 0 or 62 in front. */
    fun national(number: String): String {
        val all = digits(number)
        return when {
            all.startsWith("62") -> all.drop(2)
            all.startsWith("0") -> all.drop(1)
            else -> all
        }
    }

    fun isNumber(query: String): Boolean {
        val trimmed = query.trim()
        return trimmed.isNotEmpty() && trimmed.all { it.isDigit() || it in " +-()./" } && digits(trimmed).isNotEmpty()
    }

    fun numberMatches(query: String, number: String): Boolean {
        val typed = digits(query)
        if (typed.isEmpty()) return false
        if (digits(number).contains(typed)) return true
        val short = national(query)
        return short.isNotEmpty() && national(number).contains(short)
    }

    private fun words(text: String): List<String> = fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Lower is better; null is no match; 0 for an empty query. */
    fun score(name: String, numbers: List<String>, query: String): Int? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return 0
        if (isNumber(trimmed)) return if (numbers.any { numberMatches(trimmed, it) }) 3 else null
        val wanted = words(trimmed)
        if (wanted.isEmpty()) return null
        val folded = fold(name)
        val parts = words(name)
        if (folded.startsWith(fold(trimmed))) return 0
        if (wanted.all { word -> parts.any { it.startsWith(word) } }) return 1
        if (trimmed.length > 2 && wanted.all { folded.contains(it) }) return 2
        return null
    }

    /** Contacts that match, best first, then by name. An empty query keeps them all, by name. */
    fun search(contacts: List<ContactItem>, query: String): List<ContactItem> =
        contacts.mapNotNull { contact -> score(contact.name, contact.phones, query)?.let { contact to it } }
            .sortedWith(compareBy<Pair<ContactItem, Int>> { it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name })
            .map { it.first }

    /** The letter a contact is listed under, or `#`. */
    fun section(name: String): String {
        val first = fold(name).firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return if (first in 'a'..'z') first.uppercase() else "#"
    }

    /** Up to two letters for an avatar: "Budi Santoso" is "BS", "+62812" is "#". */
    fun initials(name: String): String {
        val letters = name.split(' ').mapNotNull { part -> part.firstOrNull { it.isLetter() } }.take(2)
        return if (letters.isEmpty()) "#" else letters.joinToString("").uppercase()
    }
}
