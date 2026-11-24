package com.voicekanban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.TxType
import com.voicekanban.app.data.categoryEmoji
import com.voicekanban.app.data.formatMoney
import com.voicekanban.app.fitness.WorkoutTracker
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.Section
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.components.BarChart
import com.voicekanban.app.ui.components.DonutChart
import com.voicekanban.app.ui.components.LegendRow
import com.voicekanban.app.ui.components.ProgressRing
import com.voicekanban.app.ui.theme.ResponsiveContainer
import com.voicekanban.app.ui.theme.VK
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val CHART_COLORS = listOf(
    Color(0xFF8BE28B), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFF06B6D4),
    Color(0xFFEC4899), Color(0xFFEF4444), Color(0xFF14B8A6), Color(0xFFA855F7),
)

private fun Long.day(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** Home: everything at a glance - tasks, money, workouts, focus, notes and vault - mobile responsive. */
@Composable
fun DashboardScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val c = VK.colors
    val r = VK.responsive
    val app by vm.state.collectAsState()
    val vault by vm.vault.collectAsState()
    val live by WorkoutTracker.live.collectAsState()
    val cur = vm.currency
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val dayLabels = week.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

    // Tasks across all boards
    val open = app.workspace.boards.flatMap { b -> b.stages.filterNot { b.isDone(it.id) }.flatMap { it.cards } }
    val doneCount = app.workspace.boards.sumOf { it.doneCount }
    val total = app.workspace.boards.sumOf { it.totalCards }
    val dueToday = open.count { it.dueAt?.day() == today }
    val overdue = open.count { (it.dueAt ?: Long.MAX_VALUE) < now }
    val upcoming = open.filter { it.dueAt != null && it.dueAt >= now }.sortedBy { it.dueAt }.take(3)
    val board = app.workspace.activeBoard

    // Money (this month)
    val month = YearMonth.now()
    val monthTx = app.transactions.filter { YearMonth.from(it.at.day()) == month }
    val income = monthTx.filter { it.type == TxType.INCOME }.sumOf { it.amount }
    val spent = monthTx.filter { it.type == TxType.EXPENSE }.sumOf { it.amount }
    val byCat = monthTx.filter { it.type == TxType.EXPENSE }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val topCats = byCat.take(5)
    val spentByDay = week.map { d -> app.transactions.filter { it.type == TxType.EXPENSE && it.at.day() == d }.sumOf { it.amount } }

    // Workouts (last 7 days)
    val minutesByDay = week.map { d -> app.workouts.filter { it.startedAt.day() == d }.sumOf { it.durationSec } / 60.0 }
    val weekKm = app.workouts.filter { it.startedAt.day() >= week.first() }.sumOf { it.distanceM } / 1000
    val weekKcal = app.workouts.filter { it.startedAt.day() >= week.first() }.sumOf { it.calories }
    val goal = app.types.firstOrNull { it.id == app.workoutReminder.typeId }?.goalMin ?: 30
    var streak = 0
    run { var d = today; if (app.workouts.none { it.startedAt.day() == d }) d = d.minusDays(1); while (app.workouts.any { it.startedAt.day() == d }) { streak++; d = d.minusDays(1) } }

    // Focus
    val focusToday = app.focusSessions.filter { it.startedAt.day() == today }.sumOf { it.actualSec } / 60
    val focusWeek = app.focusSessions.filter { it.startedAt.day() >= week.first() }

    val greeting = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "$greeting, ${app.settings.userName.ifBlank { "friend" }}",
            subtitle = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")),
            leading = { AiAvatar(AvatarMood.IDLE, Modifier.size(if (r.isCompactWidth) 40.dp else 44.dp), halo = false, onTap = { vm.openAssistant(false) }) },
            trailing = { HeaderButton(Icons.Rounded.Settings, "Settings", onClick = onSettings) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = r.gutter).padding(bottom = 24.dp),
        ) {
            ResponsiveContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Quick actions (wraps into 2x2 on ultra-narrow screens < 330dp)
                    val quickActions = listOf(
                        Triple(Icons.Rounded.GraphicEq, "Talk", Triple(c.limeTile, c.onLime) { vm.openAssistant(true) }),
                        Triple(Icons.Rounded.DirectionsRun, if (live != null) "Live" else "Workout", Triple(c.raised, c.ink) { vm.setSection(Section.WORKOUT) }),
                        Triple(Icons.Rounded.SelfImprovement, "Focus", Triple(c.raised, c.ink) { vm.setSection(Section.FOCUS) }),
                        Triple(Icons.AutoMirrored.Rounded.StickyNote2, "Note", Triple(c.raised, c.ink) { vm.setSection(Section.NOTES) }),
                    )
                    if (r.screenWidthDp < 330) {
                        quickActions.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (icon, label, rest) -> Quick(Modifier.weight(1f), icon, label, rest.first, rest.second, rest.third) }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickActions.forEach { (icon, label, rest) -> Quick(Modifier.weight(1f), icon, label, rest.first, rest.second, rest.third) }
                        }
                    }

                    // Headline stats
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat(Modifier.weight(1f), "${open.size}", "Open tasks", c.sageTile, c.ink)
                        Stat(Modifier.weight(1f), "$dueToday", "Due today", c.limeTile, c.onLime)
                        Stat(Modifier.weight(1f), "$overdue", "Overdue", if (overdue > 0) c.highSoft else c.raised, if (overdue > 0) c.high else c.ink)
                    }

                    val tasksCard: @Composable () -> Unit = {
                        DashCard("Tasks", "${board.title} · ${board.progressPct}% done", onOpen = { vm.setSection(Section.BOARD) }) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val stack = maxWidth < 270.dp
                                val donutSize = if (maxWidth < 310.dp) 90.dp else 112.dp
                                val stages = board.stages
                                val donut: @Composable () -> Unit = {
                                    DonutChart(stages.map { it.cards.size.toDouble() }, stages.map { Color(it.color) }, donutSize) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("$doneCount/$total", fontSize = if (donutSize < 100.dp) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1)
                                            Text("done", fontSize = 11.sp, color = c.inkTertiary)
                                        }
                                    }
                                }
                                if (stack) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        donut()
                                        Column(Modifier.fillMaxWidth()) { stages.forEach { LegendRow(Color(it.color), it.title, "${it.cards.size}") } }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        donut()
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) { stages.forEach { LegendRow(Color(it.color), it.title, "${it.cards.size}") } }
                                    }
                                }
                            }
                            if (upcoming.isNotEmpty()) {
                                Text("Coming up", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                upcoming.forEach { card ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.priorityFg(card.priority)))
                                        Text(card.text, fontSize = 13.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp, end = 6.dp))
                                        Text(DateParser.relative(card.dueAt!!), fontSize = 12.sp, color = c.inkSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    val moneyCard: @Composable () -> Unit = {
                        DashCard("Money", "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} · balance ${formatMoney(income - spent, cur)}", onOpen = { vm.setSection(Section.MONEY) }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MiniStat(Modifier.weight(1f), "Income", formatMoney(income, cur), c.low)
                                MiniStat(Modifier.weight(1f), "Spent", formatMoney(spent, cur), c.high)
                            }
                            if (topCats.isNotEmpty()) {
                                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                    val stack = maxWidth < 270.dp
                                    val sz = if (maxWidth < 310.dp) 84.dp else 100.dp
                                    val donut: @Composable () -> Unit = {
                                        DonutChart(topCats.map { it.value }, CHART_COLORS, sz, 11.dp) {
                                            Text(if (income > 0) "${(spent / income * 100).toInt()}%" else "-", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                                        }
                                    }
                                    if (stack) {
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            donut()
                                            Column(Modifier.fillMaxWidth()) {
                                                topCats.forEachIndexed { i, e -> LegendRow(CHART_COLORS[i % CHART_COLORS.size], categoryEmoji(TxType.EXPENSE, e.key) + " " + e.key, formatMoney(e.value, cur)) }
                                            }
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            donut()
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                topCats.forEachIndexed { i, e -> LegendRow(CHART_COLORS[i % CHART_COLORS.size], categoryEmoji(TxType.EXPENSE, e.key) + " " + e.key, formatMoney(e.value, cur)) }
                                            }
                                        }
                                    }
                                }
                            }
                            Text("Spending, last 7 days", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                            BarChart(spentByDay, dayLabels, c.voiceSecondary.copy(alpha = 0.55f), c.voiceSecondary, height = 80.dp)
                        }
                    }

                    if (r.screenWidthDp >= 720) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { tasksCard() }
                            Box(Modifier.weight(1f)) { moneyCard() }
                        }
                    } else {
                        tasksCard()
                        moneyCard()
                    }

                    // Workout
                    DashCard("Workout", if (live != null) "${live!!.type.emoji} ${live!!.type.name} in progress" else "$streak-day streak · %.1f km · %.0f kcal this week".format(weekKm, weekKcal), onOpen = { vm.setSection(Section.WORKOUT) }) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val ringSz = if (maxWidth < 300.dp) 82.dp else 96.dp
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val todayMin = minutesByDay.last()
                                ProgressRing((todayMin / goal).toFloat(), ringSz, c.voiceSecondary) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${todayMin.toInt()}", fontSize = if (ringSz < 90.dp) 17.sp else 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                                        Text("of $goal min", fontSize = 10.sp, color = c.inkTertiary)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                BarChart(minutesByDay, dayLabels, c.sageTile, c.voiceSecondary, Modifier.weight(1f), height = 80.dp)
                            }
                        }
                    }

                    // Focus · Notes · Vault · Budgets (4-across on wide screens, 2x2 on phones)
                    val smalls = listOf(
                        Triple(Icons.Rounded.SelfImprovement, "Focus", Triple("$focusToday min today", "${focusWeek.count { it.completed }} sessions this week") { vm.setSection(Section.FOCUS) }),
                        Triple(Icons.AutoMirrored.Rounded.StickyNote2, "Notes", Triple("${app.notes.size} notes", app.notes.maxByOrNull { it.updatedAt }?.title ?: "Make your first") { vm.setSection(Section.NOTES) }),
                        Triple(Icons.Rounded.Lock, "Vault", Triple("${vault.size} logins", "Encrypted on this phone") { vm.setSection(Section.VAULT) }),
                        Triple(Icons.Rounded.AccountBalanceWallet, "Budgets", Triple("${app.budgets.size} set", app.budgets.firstOrNull()?.let { b -> "${b.category}: ${formatMoney(byCat.firstOrNull { it.key == b.category }?.value ?: 0.0, cur)} / ${formatMoney(b.limit, cur)}" } ?: "Tap to add one") { vm.setSection(Section.MONEY) }),
                    )
                    val smallCols = if (r.screenWidthDp >= 720) 4 else 2
                    smalls.chunked(smallCols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (icon, title, meta) ->
                                SmallCard(Modifier.weight(1f), icon, title, meta.first, meta.second, meta.third)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Quick(modifier: Modifier, icon: ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    val c = VK.colors
    val compact = VK.responsive.isCompactWidth
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, if (bg == c.raised) c.buttonBorder else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 20.dp else 22.dp))
        Text(label, fontSize = if (compact) 11.sp else 12.sp, color = fg, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Stat(modifier: Modifier, value: String, label: String, bg: Color, fg: Color) {
    val compact = VK.responsive.isCompactWidth
    Column(modifier.heightIn(min = if (compact) 76.dp else 84.dp).clip(RoundedCornerShape(20.dp)).background(bg).padding(if (compact) 11.dp else 14.dp), verticalArrangement = Arrangement.Bottom) {
        Text(value, fontSize = if (compact) 22.sp else 26.sp, color = fg, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.sp, color = fg.copy(alpha = 0.7f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniStat(modifier: Modifier, label: String, value: String, dot: Color) {
    val c = VK.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, fontSize = 16.sp, color = c.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DashCard(title: String, subtitle: String, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = VK.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(26.dp)).padding(VK.responsive.cardPadding)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(32.dp).clip(CircleShape).border(1.dp, c.buttonBorder, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open $title", tint = c.ink, modifier = Modifier.size(16.dp))
            }
        }
        content()
    }
}

@Composable
private fun SmallCard(modifier: Modifier, icon: ImageVector, title: String, value: String, sub: String, onClick: () -> Unit) {
    val c = VK.colors
    val compact = VK.responsive.isCompactWidth
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(if (compact) 12.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.accentText, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp))
            Text(title, fontSize = 13.sp, color = c.inkSecondary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, fontSize = if (compact) 16.sp else 18.sp, color = c.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(sub, fontSize = 11.sp, color = c.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
