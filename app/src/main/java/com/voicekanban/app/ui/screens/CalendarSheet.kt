package com.voicekanban.app.ui.screens

import android.app.TimePickerDialog
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
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.NotificationsActive
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.REMINDER_CHOICES
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

/** Month calendar for the open board: tasks by day, add a task to a day (with an optional reminder), or schedule an undated one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSheet(vm: MainViewModel, board: Board, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val live by vm.state.collectAsState()
    val b = live.workspace.boards.firstOrNull { it.id == board.id } ?: board
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(today) }
    var text by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.of(17, 0)) }
    var reminder by remember { mutableStateOf<Int?>(60) }
    var stageId by remember { mutableStateOf(b.stages.firstOrNull { !b.isDone(it.id) }?.id ?: b.stages.first().id) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }

    val cards = b.stages.flatMap { s -> s.cards.map { it to s } }
    val byDay = cards.filter { it.first.dueAt != null }.groupBy { it.first.dueAt!!.localDate() }
    val undated = cards.filter { it.first.dueAt == null && !b.isDone(it.second.id) }

    fun pickTime() = TimePickerDialog(ctx, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    fun dueMs() = selected.atTime(time).atZone(zoneId).toInstant().toEpochMilli()

    AppSheet("Calendar", b.title, onDismiss = onDismiss) {
        // Month header
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") { month = month.minusMonths(1) }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") { month = month.plusMonths(1) }
        }
        // Weekday labels (Monday first)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            DayOfWeek.entries.forEach { d -> Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), fontSize = 12.sp, color = c.inkTertiary, textAlign = TextAlign.Center) }
        }
        // Day grid
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSel = day == selected
                            val isToday = day == today
                            val items = byDay[day].orEmpty()
                            val overdue = items.any { (card, s) -> !b.isDone(s.id) && (card.dueAt ?: 0) < System.currentTimeMillis() }
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) c.limeTile else if (items.isNotEmpty()) c.raised else Color.Transparent)
                                    .border(1.dp, if (isToday && !isSel) c.ink else if (items.isNotEmpty() && !isSel) c.border else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { selected = day },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${day.dayOfMonth}", fontSize = 14.sp, color = if (isSel) c.onLime else c.ink, fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal)
                                if (items.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                                    items.take(3).forEach { (card, _) -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (overdue) c.high else c.priorityFg(card.priority))) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tasks on the selected day
        Text(
            selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) + if (selected == today) " · Today" else "",
            style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
        val dayItems = byDay[selected].orEmpty().sortedBy { it.first.dueAt }
        if (dayItems.isEmpty()) Text("Nothing scheduled.", fontSize = 13.sp, color = c.inkTertiary)
        dayItems.forEach { (card, stage) -> DayTask(card, stage.title, done = b.isDone(stage.id)) { vm.scheduleCard(card.id, null, card.remindBeforeMin) } }

        // Add a task to this day
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(22.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add a task on ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}", style = MaterialTheme.typography.labelLarge, color = c.ink)
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
                Text("  Reminder", fontSize = 12.sp, color = c.inkTertiary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                REMINDER_CHOICES.forEach { (min, label) -> Chip(label, selected = reminder == min, tint = c.voiceTertiary) { reminder = min } }
            }
            Button(
                onClick = { vm.addDatedCard(stageId, text, dueMs(), reminder, priority); text = "" },
                enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
            ) { Text("Add to ${selected.format(DateTimeFormatter.ofPattern("d MMM"))}", fontWeight = FontWeight.SemiBold) }
        }

        // Schedule an existing task without a date
        if (undated.isNotEmpty()) {
            Text("Or move an unscheduled task here", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                undated.take(12).forEach { (card, _) -> Chip("+ ${card.text.take(28)}") { vm.scheduleCard(card.id, dueMs(), reminder) } }
            }
            Text("Uses the time and reminder chosen above.", fontSize = 11.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 4.dp))
        }
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
                    color = c.inkSecondary, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        Icon(Icons.Rounded.EventBusy, "Remove date", tint = c.inkTertiary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onUnschedule).padding(10.dp))
    }
}
