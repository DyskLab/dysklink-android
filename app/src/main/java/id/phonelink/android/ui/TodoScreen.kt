package id.phonelink.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.TodoItem
import id.phonelink.android.core.TodoList
import id.phonelink.android.service.TodoStore
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ticks once a minute, so finished tasks leave at midnight while a screen is open. */
@Composable
private fun rememberTodoNow(): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            TodoStore.load(context)
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return now
}

@Composable
fun TodoHomeCard(onOpen: () -> Unit) {
    val now = rememberTodoNow()
    val list by TodoStore.list.collectAsStateWithLifecycle()
    ToolHomeCard(Icons.Rounded.Checklist, Palette.Mint, "Todo", list.summary(now, ZoneId.systemDefault()), onOpen)
}

@Composable
fun TodoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val now = rememberTodoNow()
    val list by TodoStore.list.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val gap = 8.dp
    val gapPx = with(density) { gap.toPx() }
    // Dragging an open task by its handle: which one, and how far from where it sits now.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val currentList by rememberUpdatedState(list)

    fun add() {
        if (TodoStore.add(context, draft)) {
            draft = ""
            problem = null
        } else if (TodoList.clean(draft).isNotEmpty()) {
            problem = "The list is full (${TodoList.MAX_ITEMS} tasks). Finish or remove some first."
        }
    }

    fun dragBy(dy: Float) {
        val id = draggingId ?: return
        val open = currentList.open
        val index = open.indexOfFirst { it.id == id }
        if (index < 0) return
        dragOffset += dy
        // Past half of the neighbour: swap with it, and keep the row under the finger.
        if (dragOffset > 0 && index < open.lastIndex) {
            val next = (heights[open[index + 1].id] ?: return) + gapPx
            if (dragOffset > next / 2) {
                TodoStore.moveOpen(context, index, index + 1)
                dragOffset -= next
            }
        } else if (dragOffset < 0 && index > 0) {
            val previous = (heights[open[index - 1].id] ?: return) + gapPx
            if (-dragOffset > previous / 2) {
                TodoStore.moveOpen(context, index, index - 1)
                dragOffset += previous
            }
        }
    }

    FeatureScaffold("Todo", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.replace("\n", "").take(TodoList.MAX_TITLE); problem = null },
                placeholder = { Text("Add a task") },
                singleLine = true,
                // Done adds the task and keeps the keyboard up for the next one.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Add") { add() }
        }
        problem?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Palette.Coral, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        val visible = list.visible(now, zone)
        val open = list.open
        val doneToday = list.doneToday(now, zone)
        Text(
            when {
                visible.isEmpty() -> "No tasks yet. Finished tasks are cleared the next day."
                else -> "${open.size} left · $doneToday done today"
            },
            color = Palette.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            visible.forEach { item ->
                key(item.id) {
                    val openIndex = open.indexOfFirst { it.id == item.id }
                    val dragging = draggingId == item.id
                    TodoRow(
                        item = item,
                        renaming = renaming == item.id,
                        canMoveUp = openIndex > 0,
                        canMoveDown = openIndex >= 0 && openIndex < open.lastIndex,
                        modifier = Modifier
                            .onSizeChanged { heights[item.id] = it.height }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else 0f
                                shadowElevation = if (dragging) 12f else 0f
                            },
                        handle = if (openIndex < 0) Modifier else Modifier.pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = { draggingId = item.id; dragOffset = 0f },
                                onDragEnd = { draggingId = null; dragOffset = 0f },
                                onDragCancel = { draggingId = null; dragOffset = 0f },
                                onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                            )
                        },
                        onToggle = { TodoStore.toggle(context, item.id) },
                        onStartRename = { renaming = item.id },
                        onRename = { title -> TodoStore.rename(context, item.id, title); renaming = null },
                        onMove = { by -> if (openIndex >= 0) TodoStore.moveOpen(context, openIndex, openIndex + by) },
                        onDelete = { TodoStore.delete(context, item.id); if (renaming == item.id) renaming = null },
                    )
                }
            }
            if (doneToday > 0) {
                ToolLink("Clear finished", Palette.TextSecondary) { TodoStore.clearDone(context) }
            }
            if (visible.isNotEmpty()) {
                Text(
                    "Tap a task to rename it. Drag the handle to reorder, swipe sideways to delete.",
                    color = Palette.TextFaint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    renaming: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier,
    handle: Modifier,
    onToggle: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        // Shows behind the row while it is swiped away.
        Box(
            Modifier.matchParentSize().clip(shape).background(Palette.Coral.copy(alpha = 0.25f * (abs(swipe.value) / width * 3).coerceAtMost(1f))),
            contentAlignment = if (swipe.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Text("Delete", color = Palette.Coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
        }
        Row(
            Modifier
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(shape)
                .background(if (item.isDone) Palette.Panel else Palette.PanelRaised)
                .border(1.dp, Palette.Stroke, shape)
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(swipe.value) > width * 0.35f) {
                                    swipe.animateTo(if (swipe.value > 0) width else -width)
                                    onDelete()
                                } else {
                                    swipe.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch { swipe.snapTo(swipe.value + amount) }
                        },
                    )
                }
                .padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(
                    if (item.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (item.isDone) "Mark as not done" else "Mark as done",
                    tint = if (item.isDone) Palette.Mint else Palette.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                if (renaming) {
                    RenameField(item.title, onRename)
                } else {
                    Text(
                        item.title,
                        color = if (item.isDone) Palette.TextFaint else Palette.TextPrimary,
                        fontSize = 15.sp,
                        textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onStartRename).padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
            if (!item.isDone) {
                Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DragHandle, contentDescription = "Drag to reorder", tint = Palette.TextFaint, modifier = Modifier.size(22.dp))
                }
            }
            Box {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onStartRename() })
                    if (canMoveUp) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                    if (canMoveDown) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Delete", color = Palette.Coral) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Edits a title in place. Done or leaving the field saves it; an empty title keeps the old one. */
@Composable
private fun RenameField(title: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(title) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { text = it.replace("\n", "").take(TodoList.MAX_TITLE) },
        singleLine = true,
        textStyle = TextStyle(color = Palette.TextPrimary, fontSize = 15.sp),
        cursorBrush = SolidColor(Palette.Mint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) onDone(text)
                focused = it.isFocused
            }
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Ink.copy(alpha = 0.4f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
    )
}
