package com.voicekanban.app.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.CAL_REMINDER_CHOICES
import com.voicekanban.app.data.CalendarReminder
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.REMINDER_CHOICES
import com.voicekanban.app.data.ReminderRepeat
import com.voicekanban.app.reminders.Reminders
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.theme.VK
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val zoneId: ZoneId get() = ZoneId.systemDefault()
private fun Long.localDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
private fun Long.localTime(): LocalTime = Instant.ofEpochMilli(this).atZone(zoneId).toLocalTime()

private enum class CalendarAddMode { REMINDER, TASK }

/**
 * Month calendar: shows both standalone Calendar Reminders (calendar-only, NOT on Kanban board)
 * and scheduled Kanban board tasks. Lets the user add a calendar-only reminder, add a dated board
 * task, or move an unscheduled task onto a day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSheet(
    vm: MainViewModel,
    board: Board,
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
) {
    val c = VK.colors
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val compactHeight = cfg.screenHeightDp < 540
    val live by vm.state.collectAsState()
    val b = live.workspace.boards.firstOrNull { it.id == board.id } ?: board
    val today = LocalDate.now()

    var month by remember(initialDate) { mutableStateOf(YearMonth.from(initialDate)) }
    var selected by remember(initialDate) { mutableStateOf(initialDate) }
    var addMode by remember { mutableStateOf(CalendarAddMode.REMINDER) }

    // Shared / task fields
    var text by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var taskReminder by remember { mutableStateOf<Int?>(60) }
    var calRemindBefore by remember { mutableIntStateOf(0) }
    var repeat by remember { mutableStateOf(ReminderRepeat.NONE) }
    var stageId by remember { mutableStateOf(b.stages.firstOrNull { !b.isDone(it.id) }?.id ?: b.stages.first().id) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var editingReminder by remember { mutableStateOf<CalendarReminder?>(null) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canPost(ctx)) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val cards = b.stages.flatMap { s -> s.cards.map { it to s } }
    val byDay = cards.filter { it.first.dueAt != null }.groupBy { it.first.dueAt!!.localDate() }
    val undated = cards.filter { it.first.dueAt == null && !b.isDone(it.second.id) }
    val allReminders = live.calendarReminders

    fun remindersFor(day: LocalDate): List<CalendarReminder> =
        allReminders.filter { it.occursOn(day, zoneId) }.sortedBy { it.at.localTime() }

    fun pickTime() = TimePickerDialog(
        ctx,
        { _, h, m -> time = LocalTime.of(h, m) },
        time.hour,
        time.minute,
        android.text.format.DateFormat.is24HourFormat(ctx),
    ).show()

    fun selectedAtMs() = selected.atTime(time).atZone(zoneId).toInstant().toEpochMilli()

    fun loadForEdit(r: CalendarReminder) {
        editingReminder = r
        addMode = CalendarAddMode.REMINDER
        text = r.title
        notes = r.notes
        time = r.at.localTime()
        calRemindBefore = r.remindBeforeMin
        repeat = r.repeat
    }

    fun clearForm() {
        editingReminder = null
        text = ""
        notes = ""
    }

    val reminderDotColor = c.voiceTertiary

    AppSheet("Calendar", "${b.title} · tasks & reminders", onDismiss = onDismiss) {
        // Month header
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") { month = month.minusMonths(1) }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") { month = month.plusMonths(1) }
        }
        // Weekday labels (Monday first)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = c.inkTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Day grid (responsive height so landscape / compact screens never trap the grid)
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    val cellMod = if (compactHeight) Modifier.weight(1f).height(40.dp).padding(2.dp)
                    else Modifier.weight(1f).aspectRatio(1f).heightIn(max = 50.dp).padding(2.dp)
                    Box(cellMod, contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSel = day == selected
                            val isToday = day == today
                            val dayTasks = byDay[day].orEmpty()
                            val dayRems = remindersFor(day)
                            val hasAny = dayTasks.isNotEmpty() || dayRems.isNotEmpty()
                            val overdue = dayTasks.any { (card, s) -> !b.isDone(s.id) && (card.dueAt ?: 0) < System.currentTimeMillis() }
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) c.limeTile else if (hasAny) c.raised else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isToday && !isSel) c.ink else if (hasAny && !isSel) c.border else Color.Transparent,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { selected = day },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    "${day.dayOfMonth}",
                                    fontSize = 13.sp,
                                    color = if (isSel) c.onLime else c.ink,
                                    fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (hasAny) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                                        if (dayRems.any { !it.done }) {
                                            Box(
                                                Modifier.size(5.dp).clip(CircleShape)
                                                    .background(if (isSel) c.onLime else reminderDotColor),
                                            )
                                        }
                                        dayTasks.take(if (dayRems.any { !it.done }) 2 else 3).forEach { (card, _) ->
                                            Box(
                                                Modifier.size(5.dp).clip(CircleShape)
                                                    .background(if (overdue) c.high else c.priorityFg(card.priority)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Subtle legend
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(reminderDotColor))
                Text("Calendar reminder", fontSize = 11.sp, color = c.inkTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.high))
                Text("Board task", fontSize = 11.sp, color = c.inkTertiary)
            }
        }

        // Selected day items
        Text(
            selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) + if (selected == today) " · Today" else "",
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        val dayReminders = remindersFor(selected)
        val dayTasks = byDay[selected].orEmpty().sortedBy { it.first.dueAt }

        if (dayReminders.isEmpty() && dayTasks.isEmpty()) {
            Text("Nothing scheduled for this day.", fontSize = 13.sp, color = c.inkTertiary)
        }

        if (dayReminders.isNotEmpty()) {
            Text(
                "Reminders · calendar only",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.accentText,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            dayReminders.forEach { rem ->
                DayReminder(
                    rem = rem,
                    onToggleDone = { vm.toggleCalendarReminderDone(rem.id) },
                    onEdit = { loadForEdit(rem) },
                    onDelete = {
                        if (editingReminder?.id == rem.id) clearForm()
                        vm.deleteCalendarReminder(rem.id)
                    },
                )
            }
        }

        if (dayTasks.isNotEmpty()) {
            Text(
                "Board tasks",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.inkTertiary,
                modifier = Modifier.padding(top = if (dayReminders.isNotEmpty()) 6.dp else 0.dp, bottom = 4.dp),
            )
            dayTasks.forEach { (card, stage) ->
                DayTask(card, stage.title, done = b.isDone(stage.id)) {
                    vm.scheduleCard(card.id, null, card.remindBeforeMin)
                }
            }
        }

        // Add card: switch between Calendar Reminder (not on Kanban board) and Board Task
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(c.surface).border(1.dp, c.border, RoundedCornerShape(22.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    label = "Reminder",
                    active = addMode == CalendarAddMode.REMINDER,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.NotificationsActive,
                ) { addMode = CalendarAddMode.REMINDER }
                Pill(
                    label = "Board task",
                    active = addMode == CalendarAddMode.TASK,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.GridView,
                ) {
                    editingReminder = null
                    addMode = CalendarAddMode.TASK
                }
            }

            if (addMode == CalendarAddMode.REMINDER) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editingReminder != null) "Edit reminder" else "Add reminder on ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.ink,
                        )
                        Text(
                            "Shown in Calendar only — won’t appear on your Kanban board.",
                            fontSize = 11.sp,
                            color = c.inkSecondary,
                        )
                    }
                    if (editingReminder != null) {
                        Chip("Cancel") { clearForm() }
                    }
                }
                InputField(text, { text = it }, "Remind me to… (e.g. Call dentist, Take medicine)", leading = Icons.Rounded.NotificationsActive)
                InputField(notes, { notes = it }, "Optional note or details", leading = Icons.Rounded.Notes)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("⏰ ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}", selected = true, onClick = ::pickTime)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Repeat, null, tint = c.inkTertiary, modifier = Modifier.size(14.dp))
                    Text("  Repeat", fontSize = 12.sp, color = c.inkTertiary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReminderRepeat.entries.forEach { r ->
                        Chip(r.label, selected = repeat == r, tint = c.voiceTertiary) { repeat = r }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = c.inkTertiary, modifier = Modifier.size(14.dp))
                    Text("  Alert", fontSize = 12.sp, color = c.inkTertiary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CAL_REMINDER_CHOICES.forEach { (min, label) ->
                        Chip(label, selected = calRemindBefore == min, tint = c.voiceTertiary) { calRemindBefore = min }
                    }
                }

                Button(
                    onClick = {
                        ensureNotifPermission()
                        val editing = editingReminder
                        if (editing != null) {
                            vm.saveCalendarReminder(
                                editing.copy(
                                    title = text.trim(),
                                    notes = notes.trim(),
                                    at = selectedAtMs(),
                                    remindBeforeMin = calRemindBefore,
                                    repeat = repeat,
                                    done = false,
                                ),
                            )
                        } else {
                            vm.addCalendarReminder(
                                title = text,
                                at = selectedAtMs(),
                                remindBeforeMin = calRemindBefore,
                                notes = notes,
                                repeat = repeat,
                            )
                        }
                        clearForm()
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) {
                    Text(
                        if (editingReminder != null) "Save reminder" else "Add reminder · ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    "Add a board task on ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.ink,
                )
                InputField(text, { text = it }, "What needs doing?", leading = Icons.Rounded.Add)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("⏰ ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}", selected = true, onClick = ::pickTime)
                    Priority.entries.reversed().forEach { p -> Chip(p.label, selected = priority == p, tint = c.priorityFg(p)) { priority = p } }
                }
                Text("Stage", fontSize = 12.sp, color = c.inkTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.stages.forEach { s -> Chip(s.title, selected = stageId == s.id, tint = Color(s.color)) { stageId = s.id } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = c.inkTertiary, modifier = Modifier.size(14.dp))
                    Text("  Deadline alert", fontSize = 12.sp, color = c.inkTertiary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    REMINDER_CHOICES.forEach { (min, label) -> Chip(label, selected = taskReminder == min, tint = c.voiceTertiary) { taskReminder = min } }
                }
                Button(
                    onClick = {
                        ensureNotifPermission()
                        vm.addDatedCard(stageId, text, selectedAtMs(), taskReminder, priority)
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) { Text("Add task to ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}", fontWeight = FontWeight.SemiBold) }
            }
        }

        // Schedule an existing undated task onto this day (when in Board task mode or if undated cards exist)
        if (undated.isNotEmpty() && addMode == CalendarAddMode.TASK) {
            Text("Or move an unscheduled task here", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                undated.take(12).forEach { (card, _) ->
                    Chip("+ ${card.text.take(28)}") { vm.scheduleCard(card.id, selectedAtMs(), taskReminder) }
                }
            }
            Text("Uses the time and alert chosen above.", fontSize = 11.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DayReminder(
    rem: CalendarReminder,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = VK.colors
    val timeLabel = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(rem.at))
    val alertLabel = CAL_REMINDER_CHOICES.firstOrNull { it.first == rem.remindBeforeMin }?.second?.lowercase()
    val meta = listOfNotNull(
        timeLabel,
        rem.repeat.takeIf { it != ReminderRepeat.NONE }?.label,
        alertLabel?.takeIf { rem.remindBeforeMin > 0 }?.let { "🔔 $it" },
    ).joinToString(" · ")

    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (rem.done) c.surface else c.accentSoft.copy(alpha = if (c.isDark) 0.45f else 0.5f))
            .border(1.dp, if (rem.done) c.border else c.voiceTertiary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggleDone),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (rem.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (rem.done) "Mark active" else "Mark done",
                tint = if (rem.done) c.low else c.voiceTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
            Text(
                rem.title,
                color = if (rem.done) c.inkTertiary else c.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (rem.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (rem.notes.isNotBlank()) {
                Text(rem.notes, color = c.inkSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = c.accentText, modifier = Modifier.size(12.dp))
                Text(
                    " $meta",
                    color = c.inkSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Rounded.Edit,
            "Edit reminder",
            tint = c.inkSecondary,
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onEdit).padding(9.dp),
        )
        Icon(
            Icons.Rounded.DeleteOutline,
            "Delete reminder",
            tint = c.inkTertiary,
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDelete).padding(9.dp),
        )
    }
}

@Composable
private fun DayTask(card: Card, stage: String, done: Boolean, onUnschedule: () -> Unit) {
    val c = VK.colors
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.priorityFg(card.priority)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(card.text, color = if (done) c.inkTertiary else c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = c.inkTertiary, modifier = Modifier.size(12.dp))
                Text(
                    " " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(card.dueAt ?: 0)) + " · $stage" +
                        (REMINDER_CHOICES.firstOrNull { it.first == card.remindBeforeMin && card.remindBeforeMin != null }?.let { " · 🔔 ${it.second.lowercase()}" } ?: ""),
                    color = c.inkSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Rounded.EventBusy, "Remove date", tint = c.inkTertiary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onUnschedule).padding(10.dp))
    }
}
