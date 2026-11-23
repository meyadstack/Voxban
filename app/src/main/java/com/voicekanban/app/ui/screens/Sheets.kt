package com.voicekanban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Presets
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.REMINDER_CHOICES
import com.voicekanban.app.data.STAGE_COLORS
import com.voicekanban.app.data.Stage
import com.voicekanban.app.data.newId
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.Dot
import com.voicekanban.app.ui.components.SectionLabel
import com.voicekanban.app.ui.theme.VK
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Bottom sheet chrome: keyboard-aware (imePadding) and scrollable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = VK.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    subtitle?.let { Text(it, fontSize = 12.sp, color = c.inkSecondary) }
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.surfaceWell).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = c.inkSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), content = content)
        }
    }
}

// ---------------------------------------------------------------- card editor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CardEditorSheet(card: Card, board: Board, onDismiss: () -> Unit, onSave: (Card, String) -> Unit, onDelete: () -> Unit) {
    val c = VK.colors
    var text by remember { mutableStateOf(card.text) }
    var notes by remember { mutableStateOf(card.notes.orEmpty()) }
    var priority by remember { mutableStateOf(card.priority) }
    var tags by remember { mutableStateOf(card.tags.joinToString(", ")) }
    var stageId by remember { mutableStateOf(board.stages.first { s -> s.cards.any { it.id == card.id } }.id) }
    var due by remember { mutableStateOf(card.dueAt) }
    var remind by remember { mutableStateOf(card.remindBeforeMin) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    AppSheet("Edit task", onDismiss = onDismiss) {
        SectionLabel("Title")
        InputField(text, { text = it }, "What needs doing?")
        SectionLabel("Notes")
        InputField(notes, { notes = it }, "Details, links, context…", singleLine = false)

        SectionLabel("Deadline")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickDate = true }, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Rounded.Event, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(due?.let { DateParser.label(it) } ?: "Set date")
            }
            if (due != null) {
                OutlinedButton(onClick = { pickTime = true }, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Rounded.Schedule, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Time")
                }
                TextButton(onClick = { due = null }) { Text("Clear", color = c.danger) }
            }
        }
        if (due == null) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Today", "Tomorrow", "Friday", "Next week").forEach { label ->
                Chip(label) { due = DateParser.parse(label.lowercase()) }
            }
        }
        if (due != null) {
            SectionLabel("Reminder")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                REMINDER_CHOICES.forEach { (mins, label) ->
                    Chip(label, selected = remind == mins, icon = if (mins != null) Icons.Rounded.NotificationsActive else null) { remind = mins }
                }
            }
        }

        SectionLabel("Priority")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { p -> Chip(p.label, Modifier.weight(1f), selected = priority == p, tint = c.priorityFg(p)) { priority = p } }
        }
        SectionLabel("Stage")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            board.stages.forEach { s -> Chip(s.title, selected = stageId == s.id, tint = Color(s.color)) { stageId = s.id } }
        }
        SectionLabel("Tags")
        InputField(tags, { tags = it }, "comma, separated")

        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger)) {
                Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("Delete")
            }
            Button(
                onClick = {
                    if (text.isNotBlank()) onSave(
                        card.copy(
                            text = text.trim(), notes = notes.trim().ifBlank { null }, priority = priority,
                            tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            dueAt = due, remindBeforeMin = remind,
                        ),
                        stageId,
                    )
                },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
            ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Save") }
        }
    }

    if (pickDate) {
        val zone = ZoneId.systemDefault()
        val initial = due ?: System.currentTimeMillis()
        val localDate = Instant.ofEpochMilli(initial).atZone(zone).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val d = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val t = due?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(17, 0)
                        due = LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickTime && due != null) {
        val zone = ZoneId.systemDefault()
        val cur = Instant.ofEpochMilli(due!!).atZone(zone)
        val state = rememberTimePickerState(cur.hour, cur.minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    due = cur.withHour(state.hour).withMinute(state.minute).toInstant().toEpochMilli()
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

// ---------------------------------------------------------------- quick actions

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardActionsSheet(card: Card, board: Board, onDismiss: () -> Unit, onMove: (String) -> Unit, onPriority: (Priority) -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = VK.colors
    val current = board.stages.first { s -> s.cards.any { it.id == card.id } }.id
    AppSheet(card.text, onDismiss = onDismiss) {
        SectionLabel("Move to")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            board.stages.forEach { s ->
                val here = s.id == current
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (here) c.accentSoft else c.surface)
                        .border(1.dp, if (here) c.accent else c.border, RoundedCornerShape(8.dp))
                        .clickable(enabled = !here) { onMove(s.id) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(stageIcon(s), null, tint = Color(s.color), modifier = Modifier.size(16.dp))
                    Text(s.title, color = if (here) c.accentText else c.ink, style = MaterialTheme.typography.labelLarge)
                    if (here) Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(14.dp))
                }
            }
        }
        SectionLabel("Priority")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { p -> Chip(p.label, Modifier.weight(1f), selected = card.priority == p, tint = c.priorityFg(p)) { onPriority(p) } }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp))) {
            ActionRow(Icons.Rounded.Edit, "Edit details & deadline", c.ink, onEdit)
            ActionRow(Icons.Rounded.ContentCopy, "Duplicate", c.ink, onDuplicate)
            ActionRow(Icons.Rounded.Delete, "Delete card", c.danger, onDelete)
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- boards

@Composable
fun BoardsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    val ws = app.workspace
    var newTitle by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("Blank") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    AppSheet("Your boards", "${ws.boards.size} board${if (ws.boards.size == 1) "" else "s"} · saved on this phone", onDismiss) {
        ws.boards.forEach { b ->
            val active = b.id == ws.activeBoardId
            Column(
                Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (active) c.accentSoft else c.surface).border(1.dp, if (active) c.accent else c.border, RoundedCornerShape(8.dp))
                    .clickable(enabled = renaming != b.id) { vm.switchBoard(b.id); onDismiss() }.padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { b.stages.take(4).forEach { Dot(Color(it.color), 5.dp) } }
                    Spacer(Modifier.width(8.dp))
                    if (renaming == b.id) {
                        InputField(renameText, { renameText = it }, "Board name", Modifier.weight(1f), onDone = { vm.renameBoard(b.id, renameText); renaming = null })
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent).clickable { vm.renameBoard(b.id, renameText); renaming = null }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, "Save name", tint = c.onAccent)
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${b.stages.size} stages · ${b.totalCards} cards · ${b.progressPct}% done", fontSize = 12.sp, color = c.inkSecondary)
                        }
                        if (active) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent)
                    }
                }
                if (confirmDelete == b.id) {
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.dangerSoft).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Delete “${b.title}” and its ${b.totalCards} cards?", color = c.danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
                        Button(onClick = { confirmDelete = null; vm.deleteBoard(b.id) }, colors = ButtonDefaults.buttonColors(containerColor = c.danger)) { Text("Delete") }
                    }
                } else if (renaming != b.id) {
                    Row(Modifier.padding(top = 10.dp, start = 32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Rename", icon = Icons.Rounded.Edit) { renaming = b.id; renameText = b.title }
                        Chip("Duplicate", icon = Icons.Rounded.ContentCopy) { vm.duplicateBoard(b.id); onDismiss() }
                        Chip("Delete", icon = Icons.Rounded.DeleteOutline, tint = c.danger) { confirmDelete = b.id }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceWell).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text("New board", style = MaterialTheme.typography.labelLarge, color = c.ink)
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Presets.templates.forEach { (name, _) -> Chip(name, selected = template == name) { template = name } }
            }
            InputField(newTitle, { newTitle = it }, "Board name, e.g. Marketing Q3", onDone = { vm.createBoard(newTitle, template); onDismiss() })
            Button(onClick = { vm.createBoard(newTitle, template); onDismiss() }, Modifier.padding(top = 10.dp).fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Create board")
            }
        }
    }
}

// ---------------------------------------------------------------- stages

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StagesSheet(vm: MainViewModel, board: Board, focusId: String?, onDismiss: () -> Unit) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    val live = app.workspace.activeBoard
    var expanded by remember { mutableStateOf(focusId) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(STAGE_COLORS.firstOrNull { col -> board.stages.none { it.color == col } } ?: STAGE_COLORS[1]) }
    val stages = live.stages

    fun save(next: List<Stage>, msg: String) = vm.updateStages(next, msg)

    AppSheet("Stages", "${live.title} · ${stages.size} of 10 stages", onDismiss) {
        stages.forEachIndexed { idx, s ->
            val open = expanded == s.id
            Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).border(1.dp, if (open) c.accent else c.border, RoundedCornerShape(8.dp)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(s.color)).clickable { expanded = if (open) null else s.id }, contentAlignment = Alignment.Center) {
                        Icon(stageIcon(s), "Change colour", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    InputField(drafts[s.id] ?: s.title, { drafts[s.id] = it }, "Stage name", Modifier.weight(1f), onDone = {
                        drafts.remove(s.id)?.trim()?.takeIf { it.isNotEmpty() && it != s.title }?.let { t -> save(stages.map { if (it.id == s.id) it.copy(title = t) else it }, "Renamed stage to “$t”") }
                    })
                    Text("${s.cards.size}", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(horizontal = 6.dp))
                    IconBtn(Icons.Rounded.ArrowUpward, "Move earlier", idx > 0) { save(stages.toMutableList().apply { add(idx - 1, removeAt(idx)) }, "Moved “${s.title}” earlier") }
                    IconBtn(Icons.Rounded.ArrowDownward, "Move later", idx < stages.lastIndex) { save(stages.toMutableList().apply { add(idx + 1, removeAt(idx)) }, "Moved “${s.title}” later") }
                    IconBtn(Icons.Rounded.DeleteOutline, "Delete stage", stages.size > 1, c.danger) { confirm = if (confirm == s.id) null else s.id }
                }
                if (drafts.containsKey(s.id)) TextButton(onClick = {
                    drafts.remove(s.id)?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> save(stages.map { if (it.id == s.id) it.copy(title = t) else it }, "Renamed stage to “$t”") }
                }) { Text("Save name") }
                if (open) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    STAGE_COLORS.forEach { col -> ColorDot(col, col == s.color) { save(stages.map { if (it.id == s.id) it.copy(color = col) else it }, "Recoloured “${s.title}”") } }
                }
                if (confirm == s.id) Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.dangerSoft).padding(10.dp)) {
                    Text(
                        if (s.cards.isEmpty()) "Delete the empty stage “${s.title}”?" else "“${s.title}” has ${s.cards.size} card${if (s.cards.size == 1) "" else "s"}. What should happen to them?",
                        color = c.danger, fontSize = 12.sp,
                    )
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.cards.isNotEmpty()) stages.filter { it.id != s.id }.take(3).forEach { o ->
                            Chip("Move to ${o.title}", tint = Color(o.color)) {
                                confirm = null
                                save(stages.filter { it.id != s.id }.map { if (it.id == o.id) it.copy(cards = it.cards + s.cards) else it }, "Deleted “${s.title}” · cards moved to ${o.title}")
                            }
                        }
                        Chip(if (s.cards.isEmpty()) "Delete stage" else "Delete cards too", selected = true, tint = c.danger) {
                            confirm = null; save(stages.filter { it.id != s.id }, "Deleted stage “${s.title}”")
                        }
                        Chip("Cancel") { confirm = null }
                    }
                }
            }
        }
        if (stages.size < 10) Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceWell).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text("Add a stage", style = MaterialTheme.typography.labelLarge, color = c.ink)
            Spacer(Modifier.height(8.dp))
            val add = {
                newName.trim().takeIf { it.isNotEmpty() }?.let { t ->
                    val list = stages.toMutableList()
                    val doneIdx = list.indexOfFirst { it.id == "done" }
                    list.add(if (doneIdx >= 0) doneIdx else list.size, Stage(newId("s"), t, newColor))
                    save(list, "Added stage “$t”")
                    newName = ""
                    newColor = STAGE_COLORS.firstOrNull { col -> list.none { it.color == col } } ?: STAGE_COLORS[0]
                }
                Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Color(newColor), 18.dp); Spacer(Modifier.width(8.dp))
                InputField(newName, { newName = it }, "e.g. Blocked, QA, Ready to ship", Modifier.weight(1f), onDone = add)
                Spacer(Modifier.width(8.dp))
                Button(onClick = add, enabled = newName.isNotBlank(), shape = RoundedCornerShape(8.dp)) { Text("Add") }
            }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                STAGE_COLORS.forEach { col -> ColorDot(col, col == newColor) { newColor = col } }
            }
        }
        Text("Tip: tap a stage’s colour square to recolour it. Every change can be undone.", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tint: Color = VK.colors.inkSecondary, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ColorDot(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color(color)).border(2.dp, if (selected) VK.colors.ink else Color.Transparent, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp)) }
}

// ---------------------------------------------------------------- help

private val HELP = listOf(
    "Add" to listOf("Add write release notes", "Add fix crash to review due Friday with high priority", "Add milk, eggs and bread"),
    "Move" to listOf("Move release notes to in progress", "Finish signing config", "Start voice demo clip"),
    "Deadlines" to listOf("Set deadline for release notes to tomorrow at 5pm", "What’s due this week?", "What’s overdue?"),
    "Update" to listOf("Mark storage schema as low", "Prioritize touch targets", "Rename demo clip to Record launch video"),
    "Boards" to listOf("Create a board called Wedding with stages Ideas, Booked and Paid", "Add a stage called Blocked", "List my boards"),
)

@Composable
fun HelpSheet(onDismiss: () -> Unit, onTry: (String) -> Unit) {
    val c = VK.colors
    AppSheet("What can I say?", "Tap an example to run it in the Assistant. Every change can be undone.", onDismiss) {
        HELP.forEach { (group, examples) ->
            SectionLabel(group)
            examples.forEach { ex ->
                Row(
                    Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceWell).clickable { onTry(ex) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("“$ex”", color = c.ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.PlayCircle, null, tint = c.accent)
                }
            }
        }
    }
}
