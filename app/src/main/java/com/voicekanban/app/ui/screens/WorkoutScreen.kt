package com.voicekanban.app.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.voicekanban.app.data.WorkoutReminder
import com.voicekanban.app.data.WorkoutType
import com.voicekanban.app.data.newId
import com.voicekanban.app.fitness.LiveWorkout
import com.voicekanban.app.fitness.WorkoutTracker
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.BarChart
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.ProgressRing
import com.voicekanban.app.ui.components.Sparkline
import com.voicekanban.app.ui.theme.ResponsiveContainer
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private fun Long.wDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
fun WorkoutScreen(vm: MainViewModel) {
    val c = VK.colors
    val r = VK.responsive
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val live by WorkoutTracker.live.collectAsState()
    val pending by vm.pendingWorkout.collectAsState()
    var editing by remember { mutableStateOf<WorkoutType?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var musicBrowserOpen by remember { mutableStateOf(false) }
    var musicInitialQuery by remember { mutableStateOf<String?>(null) }

    // Ask for location (distance/speed) and activity (steps) access, then start.
    var toStart by remember { mutableStateOf<WorkoutType?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        toStart?.let { vm.startWorkout(it) }; toStart = null
    }
    fun begin(t: WorkoutType) {
        val need = buildList {
            if (t.gps && !WorkoutTracker.hasLocation(ctx)) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (t.steps && !WorkoutTracker.hasActivity(ctx) && Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 && !com.voicekanban.app.reminders.Reminders.canPost(ctx)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) vm.startWorkout(t) else { toStart = t; perms.launch(need.toTypedArray()) }
    }
    LaunchedEffect(pending) { pending?.let { if (live == null) begin(it); vm.requestWorkout(null) } }
    // Prewarm the workout music engine in the background so tracks start faster.
    LaunchedEffect(Unit) { com.voicekanban.app.music.WorkoutMusicEngine.prewarm() }

    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val minutes = week.map { d -> app.workouts.filter { it.startedAt.wDay() == d }.sumOf { it.durationSec } / 60.0 }
    val typeCols = when {
        r.screenWidthDp >= 640 -> 3
        r.screenWidthDp < 300 -> 1
        else -> 2
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "Workout",
            subtitle = "This week: ${minutes.sum().toInt()} min · %.1f km".format(app.workouts.filter { it.startedAt.wDay() >= week.first() }.sumOf { it.distanceM } / 1000),
            leading = { HeaderButton(Icons.Rounded.NotificationsActive, "Daily reminder", active = app.workoutReminder.enabled) { editing = null; reminderOpen.value = true } },
            trailing = { HeaderButton(Icons.Rounded.Add, "New workout type") { editing = WorkoutType(newId("wt"), "", "💪") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = r.gutter).padding(bottom = 24.dp),
        ) {
            ResponsiveContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    live?.let { w ->
                        LiveCard(
                            w = w,
                            weightKg = app.settings.weightKg,
                            onPause = { WorkoutTracker.pause() },
                            onResume = { WorkoutTracker.resume() },
                            onStop = { confirmStop = true },
                        )
                    }

                    // Workout Music Player (available both before and during a live workout)
                    WorkoutMusicCard(
                        vm = vm,
                        activeWorkoutId = live?.type?.id,
                        onOpenBrowser = { q ->
                            musicInitialQuery = q
                            musicBrowserOpen = true
                        },
                    )

                    if (live == null) {
                        Text("Start a workout", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                        Text("Tap to start the timer. Long-press to edit.", fontSize = 12.sp, color = c.inkTertiary)
                        app.types.chunked(typeCols).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { t -> TypeCard(Modifier.weight(1f), t, onStart = { begin(t) }, onEdit = { editing = t }) }
                                repeat(typeCols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }

                    // Weekly chart
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(24.dp)).padding(r.cardPadding)) {
                        Text("Active minutes", style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text("Last 7 days", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(bottom = 10.dp))
                        BarChart(minutes, week.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, c.sageTile, c.voiceSecondary, height = 110.dp) { if (it > 0) "${it.toInt()}" else "" }
                    }

                    ReminderCard(app.workoutReminder, app.types, onChange = { vm.setWorkoutReminder(it) }, open = reminderOpen)

                    // History
                    if (app.workouts.isNotEmpty()) Text("History", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                    app.workouts.take(30).forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceWell), contentAlignment = Alignment.Center) { Text(s.emoji, fontSize = 20.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        relativeTime(s.startedAt), WorkoutTracker.formatDuration(s.durationSec * 1000),
                                        if (s.distanceM > 10) "%.2f km".format(s.distanceM / 1000) else null,
                                        if (s.steps > 0) "${s.steps} steps" else null, "%.0f kcal".format(s.calories),
                                    ).joinToString(" · "),
                                    fontSize = 12.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Rounded.DeleteOutline, "Delete workout", tint = c.inkTertiary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { vm.deleteWorkout(s.id) }.padding(10.dp))
                        }
                    }
                }
            }
        }
    }

    if (musicBrowserOpen) {
        WorkoutMusicSheet(
            vm = vm,
            initialQuery = musicInitialQuery,
            onDismiss = { musicBrowserOpen = false },
        )
    }
    editing?.let { t -> TypeEditor(vm, t, onDismiss = { editing = null }) }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Finish workout?") },
        text = { Text("Save it to your history, or discard it.") },
        confirmButton = { TextButton(onClick = { confirmStop = false; vm.stopWorkout() }) { Text("Finish & save") } },
        dismissButton = { TextButton(onClick = { confirmStop = false; vm.stopWorkout(discard = true) }) { Text("Discard", color = c.danger) } },
    )
}

private val reminderOpen = mutableStateOf(false)

@Composable
private fun LiveCard(w: LiveWorkout, weightKg: Double, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val c = VK.colors
    val r = VK.responsive
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(w.running) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
    val elapsed = w.elapsedMs(now)
    val ink = Color(0xFF16201A)

    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.limeTile).padding(if (r.isCompactWidth) 14.dp else 18.dp),
    ) {
        val ringSize = (maxWidth * 0.56f).coerceIn(148.dp, if (r.isCompactHeight) 168.dp else 210.dp)
        val timerFont = if (ringSize < 175.dp) 32.sp else 42.sp
        val stats = buildList {
            if (w.gpsAvailable) {
                add("%.2f".format(w.distanceM / 1000) to "km")
                add("%.1f".format(w.speedMps * 3.6f) to "km/h now")
                val pace = if (w.distanceM > 20) (elapsed / 60000.0) / (w.distanceM / 1000) else 0.0
                add((if (pace > 0 && pace < 60) "%d:%02d".format(pace.toInt(), ((pace % 1) * 60).toInt()) else "--") to "min/km")
            }
            if (w.stepsAvailable) add("${w.steps}" to "steps")
            add("%.0f".format(w.calories(weightKg, now)) to "kcal")
        }
        val statCols = if (maxWidth < 300.dp) 2 else 3

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${w.type.emoji}  ${w.type.name}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(if (w.running) "● LIVE" else "PAUSED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (w.running) Color(0xFFB42318) else ink.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            ProgressRing((elapsed / 60000f) / w.type.goalMin, ringSize, Color(0xFF2E7A55), if (ringSize < 175.dp) 10.dp else 12.dp, track = Color.White.copy(alpha = 0.6f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(WorkoutTracker.formatDuration(elapsed), fontSize = timerFont, color = ink, letterSpacing = (-1).sp, maxLines = 1)
                    Text("goal ${w.type.goalMin} min", fontSize = 12.sp, color = ink.copy(alpha = 0.6f))
                }
            }
            // Responsive stat rows: never jams 5 metrics into one tiny narrow row
            Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.chunked(statCols).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (valTxt, lbl) -> LiveStat(Modifier.weight(1f), valTxt, lbl) }
                    }
                }
            }
            if (w.gpsAvailable) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (w.gpsFix) Icons.Rounded.GpsFixed else Icons.Rounded.GpsNotFixed, null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    Text(if (w.gpsFix) "  GPS locked" else "  Finding GPS… go outdoors for distance", fontSize = 12.sp, color = ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (w.speedHistory.size > 2) Sparkline(w.speedHistory, Color(0xFF2E7A55), Modifier.padding(top = 8.dp).fillMaxWidth().height(36.dp))
            } else if (w.type.gps) Text("Location is off — only time and calories are tracked.", fontSize = 12.sp, color = ink.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))

            // Responsive equal-weight controls (never overflows on compact phones!)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (w.running) onPause else onResume,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ink),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ink.copy(alpha = 0.25f)),
                ) {
                    Icon(if (w.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (w.running) "Pause" else "Resume", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = Color(0xFFEAFF87)),
                ) {
                    Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Finish", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LiveStat(modifier: Modifier, value: String, label: String) {
    val ink = Color(0xFF16201A)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 10.sp, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TypeCard(modifier: Modifier, t: WorkoutType, onStart: () -> Unit, onEdit: () -> Unit) {
    val c = VK.colors
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(c.raised).border(1.dp, c.buttonBorder, RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onStart, onLongClick = onEdit).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.emoji, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.PlayArrow, "Start ${t.name}", tint = c.onLime, modifier = Modifier.size(34.dp).clip(CircleShape).background(c.limeTile).padding(6.dp))
        }
        Text(t.name, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull("${t.goalMin} min goal", if (t.gps) "GPS" else null, if (t.steps) "steps" else null).joinToString(" · "),
            fontSize = 11.sp,
            color = c.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderCard(r: WorkoutReminder, types: List<WorkoutType>, onChange: (WorkoutReminder) -> Unit, open: MutableState<Boolean>) {
    val c = VK.colors
    val ctx = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.raised).border(1.dp, if (open.value) c.accentText else c.border, RoundedCornerShape(24.dp)).padding(VK.responsive.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily workout reminder", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(if (r.enabled) "At %02d:%02d · skipped once today’s goal is done".format(r.hour, r.minute) else "Off", fontSize = 12.sp, color = c.inkSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Switch(r.enabled, { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && !com.voicekanban.app.reminders.Reminders.canPost(ctx)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                onChange(r.copy(enabled = on))
            })
        }
        if (r.enabled) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("⏰ %02d:%02d".format(r.hour, r.minute), selected = true) {
                    TimePickerDialog(ctx, { _, h, m -> onChange(r.copy(hour = h, minute = m)) }, r.hour, r.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }
            }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Chip(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), selected = d.value in r.days, tint = c.voiceTertiary) {
                        onChange(r.copy(days = if (d.value in r.days) r.days - d.value else r.days + d.value))
                    }
                }
            }
            Text("Workout", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t -> Chip("${t.emoji} ${t.name}", selected = r.typeId == t.id, tint = c.voiceTertiary) { onChange(r.copy(typeId = t.id)) } }
            }
        }
    }
    LaunchedEffect(open.value) { if (open.value) { delay(1500); open.value = false } }
}

private val EMOJIS = listOf("💪", "🚶", "🏃", "🚴", "🏊", "🧘", "🏋️", "🔥", "⚽", "🏸", "🥊", "🧗", "⛹️", "🤸", "🛶", "🕺")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeEditor(vm: MainViewModel, t: WorkoutType, onDismiss: () -> Unit) {
    val c = VK.colors
    val isNew = vm.state.value.types.none { it.id == t.id }
    var name by remember { mutableStateOf(t.name) }
    var emoji by remember { mutableStateOf(t.emoji) }
    var gps by remember { mutableStateOf(t.gps) }
    var steps by remember { mutableStateOf(t.steps) }
    var met by remember { mutableDoubleStateOf(t.met) }
    var goal by remember { mutableStateOf(t.goalMin.toString()) }
    AppSheet(if (isNew) "New workout" else "Edit ${t.name}", "Your own workout type, goal and tracking", onDismiss = onDismiss) {
        InputField(name, { name = it }, "Name (e.g. Badminton, Swimming)", leading = Icons.Rounded.Edit)
        Text("Icon", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EMOJIS.forEach { e ->
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (e == emoji) c.limeTile else c.raised)
                        .border(1.dp, if (e == emoji) Color.Transparent else c.buttonBorder, CircleShape).clickable { emoji = e },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 20.sp) }
            }
        }
        Text("Intensity (for calories)", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Light" to 3.0, "Moderate" to 5.5, "Hard" to 8.5).forEach { (l, v) -> Chip(l, selected = kotlin.math.abs(met - v) < 1.3) { met = v } }
        }
        Text("Daily goal (minutes)", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        InputField(goal, { v -> goal = v.filter { it.isDigit() }.take(3) }, "30")
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Track distance & speed (GPS)", color = c.ink, fontSize = 14.sp)
                Text("For outdoor walks, runs and rides", color = c.inkTertiary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Switch(gps, { gps = it })
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Count steps", color = c.ink, fontSize = 14.sp)
                Text("Uses the phone’s step sensor", color = c.inkTertiary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Switch(steps, { steps = it })
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew && !t.builtIn) Button(
                onClick = { vm.deleteWorkoutType(t.id); onDismiss() }, Modifier.heightIn(min = 50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.dangerSoft, contentColor = c.danger),
            ) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
            Button(
                onClick = { vm.saveWorkoutType(t.copy(name = name.trim(), emoji = emoji, gps = gps, steps = steps, met = met, goalMin = goal.toIntOrNull()?.coerceIn(1, 600) ?: 30)); onDismiss() },
                enabled = name.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
            ) { Text(if (isNew) "Create workout" else "Save", fontWeight = FontWeight.SemiBold) }
        }
        Text("Built-in workouts can be edited but not deleted.", fontSize = 11.sp, color = c.inkTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
