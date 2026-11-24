package com.voicekanban.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.Stage
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.Toast
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.DueChip
import com.voicekanban.app.ui.components.PriorityPill
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun stageIcon(stage: Stage): ImageVector {
    val t = stage.title.lowercase()
    return when {
        stage.id == "done" || Regex("done|complete|finish|shipped|closed").containsMatchIn(t) -> Icons.Rounded.CheckCircle
        stage.id == "review" || Regex("review|qa|test|check").containsMatchIn(t) -> Icons.Rounded.Search
        stage.id == "progress" || Regex("progress|doing|active|wip").containsMatchIn(t) -> Icons.Rounded.Schedule
        Regex("block|stuck|hold|wait").containsMatchIn(t) -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.Layers
    }
}

fun timeAgo(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

private fun clockTime(ts: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BoardScreen(
    vm: MainViewModel,
    onOpenUpdateSheet: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenPairDesktop: () -> Unit = {},
) {
    val app by vm.state.collectAsState()
    val board = app.workspace.activeBoard
    val highlight by vm.highlight.collectAsState()
    val focusStage by vm.focusStage.collectAsState()
    val openCalDate by vm.openCalendarDate.collectAsState()
    val busy by vm.busy.collectAsState()
    val c = VK.colors
    val r = VK.responsive
    val scope = rememberCoroutineScope()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var priorityFilter by rememberSaveable { mutableStateOf<Priority?>(null) }
    var editing by remember { mutableStateOf<Card?>(null) }
    var actionsFor by remember { mutableStateOf<Card?>(null) }
    var boardsOpen by remember { mutableStateOf(false) }
    var stagesOpen by remember { mutableStateOf(false) }
    var stageFocus by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var calendarInitialDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var command by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(openCalDate) {
        openCalDate?.let { d ->
            calendarInitialDate = d
            calendarOpen = true
            vm.clearOpenCalendar()
        }
    }

    val filtering = query.isNotBlank() || priorityFilter != null
    fun visible(cards: List<Card>) = cards.filter { card ->
        (priorityFilter == null || card.priority == priorityFilter) &&
            (query.isBlank() || card.text.contains(query, true) || card.notes.orEmpty().contains(query, true) || card.tags.any { it.contains(query, true) })
    }

    val pager = rememberPagerState { board.stages.size }
    LaunchedEffect(board.id) { pager.scrollToPage(0) }
    LaunchedEffect(focusStage) {
        focusStage?.let { id -> board.stages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } }
        vm.clearFocusStage()
    }

    val open = board.stages.filterNot { board.isDone(it.id) }.flatMap { it.cards }
    val urgentCount = open.count { it.priority == Priority.HIGH }
    val todayReminderCount = remember(app.calendarReminders) {
        val today = java.time.LocalDate.now()
        app.calendarReminders.count { !it.done && it.occursOn(today) }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- header
            Surface(color = c.background, shadowElevation = 0.dp) {
                Column(Modifier.statusBarsPadding().padding(horizontal = r.headerGutter).padding(top = if (r.isCompactHeight) 4.dp else 8.dp, bottom = if (r.isCompactHeight) 6.dp else 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HeaderCircleButton(Icons.Rounded.Layers, "Switch board") { boardsOpen = true }
                        Column(
                            Modifier.weight(1f).padding(horizontal = 6.dp).clickable { boardsOpen = true },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                board.title.ifBlank { "Tasks" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontSize = if (r.isCompactWidth) 18.sp else 21.sp,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (app.settings.userName.isNotBlank()) {
                                Text(
                                    "${app.settings.userName} · ${app.settings.appLanguage.nativeName}",
                                    fontSize = 11.sp,
                                    color = c.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HeaderCircleButton(Icons.Rounded.MoreVert, "Settings") { settingsOpen = true }
                    }

                    // Stage / View Pills row
                    LazyRow(
                        Modifier.padding(top = if (r.isCompactHeight) 8.dp else 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            // Calendar: shows board tasks + calendar-only reminders
                            Row(
                                Modifier.heightIn(min = 38.dp).clip(CircleShape).background(c.limeTile)
                                    .clickable { calendarInitialDate = java.time.LocalDate.now(); calendarOpen = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.CalendarMonth, "Calendar", tint = c.onLime, modifier = Modifier.size(17.dp))
                                Text(
                                    if (todayReminderCount > 0) " Calendar · $todayReminderCount" else " Calendar",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.onLime,
                                    maxLines = 1,
                                )
                            }
                        }
                        items(board.stages.size) { i ->
                            val s = board.stages[i]
                            val active = pager.currentPage == i
                            val pillBg by animateColorAsState(if (active) c.raised else Color.Transparent, tween(200), label = "stagePillBg")
                            Row(
                                Modifier.height(38.dp).clip(CircleShape)
                                    .background(pillBg)
                                    .border(1.dp, c.buttonBorder, CircleShape)
                                    .clickable { scope.launch { pager.animateScrollToPage(i) } }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    s.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = c.ink,
                                )
                                Text(
                                    "${if (filtering) visible(s.cards).size else s.cards.size}",
                                    fontSize = 12.sp,
                                    color = c.inkTertiary,
                                )
                            }
                        }
                        item {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(Color.Transparent)
                                    .border(1.dp, c.buttonBorder, CircleShape)
                                    .clickable { stageFocus = null; stagesOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Add, "Add stage", tint = c.ink, modifier = Modifier.size(18.dp))
                            }
                        }
                        item {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (searchOpen) c.limeTile else Color.Transparent)
                                    .border(1.dp, if (searchOpen) c.onLime.copy(alpha = 0.2f) else c.buttonBorder, CircleShape)
                                    .clickable {
                                        if (searchOpen) { query = ""; priorityFilter = null }
                                        searchOpen = !searchOpen
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                    "Search",
                                    tint = if (searchOpen) c.onLime else c.ink,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        item {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(Color.Transparent)
                                    .border(1.dp, c.buttonBorder, CircleShape)
                                    .clickable { helpOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.HelpOutline, "Voice help", tint = c.ink, modifier = Modifier.size(17.dp))
                            }
                        }
                    }

                    // Overview container: full 3-tile card on standard screens, compact 1-line bar on short screens (< 620 dp)
                    if (r.screenHeightDp >= 620) {
                        Column(
                            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                                .background(c.surface).border(1.dp, c.border, RoundedCornerShape(26.dp))
                                .padding(if (r.isCompactWidth) 10.dp else 14.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "All time Completed",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = c.ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Rounded.Code,
                                    null,
                                    tint = c.inkSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Row(
                                Modifier.padding(top = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (r.isCompactWidth) 6.dp else 10.dp),
                            ) {
                                OverviewStatTile(
                                    modifier = Modifier.weight(1f),
                                    value = "${board.totalCards}",
                                    label = "Total Tasks",
                                    bg = c.sageTile,
                                    fg = c.ink,
                                    subFg = c.ink.copy(alpha = 0.72f),
                                )
                                OverviewStatTile(
                                    modifier = Modifier.weight(1f),
                                    value = "${board.progressPct}%",
                                    label = "Completed",
                                    bg = c.limeTile,
                                    fg = c.onLime,
                                    subFg = c.onLime.copy(alpha = 0.72f),
                                )
                                OverviewStatTile(
                                    modifier = Modifier.weight(1f),
                                    value = "${board.doneCount}/$urgentCount",
                                    label = "Done · Urgent",
                                    bg = c.raised,
                                    fg = c.inkSecondary,
                                    subFg = c.inkTertiary,
                                )
                            }
                        }
                    } else if (!r.isCompactHeight) {
                        Row(
                            Modifier.padding(top = 8.dp).fillMaxWidth().clip(CircleShape)
                                .background(c.surface).border(1.dp, c.border, CircleShape)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${board.totalCards} tasks", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink)
                            Text("${board.progressPct}% done", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.accentText)
                            Text("${board.doneCount} done · $urgentCount urgent", fontSize = 11.sp, color = c.inkSecondary)
                        }
                    }

                    AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            InputField(query, { query = it }, "Search titles, notes or #tags", Modifier.padding(top = 10.dp), leading = Icons.Rounded.Search)
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip("All", selected = priorityFilter == null) { priorityFilter = null }
                                Priority.entries.forEach { p -> Chip(p.label, selected = priorityFilter == p, tint = c.priorityFg(p)) { priorityFilter = p } }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- columns ("Today Tasks" cards)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    val colW = (maxWidth / 2 - 24.dp).coerceIn(280.dp, 360.dp)
                    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = r.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        board.stages.forEachIndexed { i, s ->
                            StageColumn(vm, board, s, i, visible(s.cards), filtering, highlight, Modifier.width(colW).fillMaxHeight(),
                                onEdit = { editing = it }, onActions = { actionsFor = it }, onStageOptions = { stageFocus = s.id; stagesOpen = true })
                        }
                    }
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = r.headerGutter), pageSpacing = 12.dp) { i ->
                        val s = board.stages[i]
                        StageColumn(vm, board, s, i, visible(s.cards), filtering, highlight, Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp),
                            onEdit = { editing = it }, onActions = { actionsFor = it }, onStageOptions = { stageFocus = s.id; stagesOpen = true })
                    }
                }
            }

            // ---------------------------------------------------------------- command bar
            CommandBar(
                value = command, onValue = { command = it }, busy = busy,
                onSend = { vm.runCommand(command) { ok -> if (ok) command = "" } },
                onTalk = { vm.openAssistant(listen = true) },
            )
        }

        ToastHost(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
    }

    editing?.let { card ->
        CardEditorSheet(card, board, onDismiss = { editing = null }, onSave = { updated, stageId -> vm.saveCard(updated, stageId); editing = null },
            onDelete = { vm.deleteCard(card.id); editing = null })
    }
    actionsFor?.let { card ->
        CardActionsSheet(card, board, onDismiss = { actionsFor = null },
            onMove = { vm.moveCard(card.id, it); actionsFor = null },
            onPriority = { vm.setPriority(card.id, it); actionsFor = null },
            onEdit = { actionsFor = null; editing = card },
            onDuplicate = { vm.duplicateCard(card.id); actionsFor = null },
            onDelete = { vm.deleteCard(card.id); actionsFor = null })
    }
    if (boardsOpen) BoardsSheet(vm, onDismiss = { boardsOpen = false })
    if (stagesOpen) StagesSheet(vm, board, stageFocus, onDismiss = { stagesOpen = false })
    if (settingsOpen) SettingsSheet(
        vm,
        onDismiss = { settingsOpen = false },
        onOpenUpdateSheet = { settingsOpen = false; onOpenUpdateSheet() },
        onOpenSupport = { settingsOpen = false; onOpenSupport() },
        onOpenPairDesktop = { settingsOpen = false; onOpenPairDesktop() },
    )
    if (calendarOpen) CalendarSheet(vm, board, initialDate = calendarInitialDate, onDismiss = { calendarOpen = false })
    if (helpOpen) HelpSheet(onDismiss = { helpOpen = false }, onTry = { helpOpen = false; vm.openAssistant(false); vm.sendChat(it) })
}

@Composable
private fun HeaderCircleButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = VK.colors
    val sz = if (VK.responsive.isCompactWidth) 40.dp else 44.dp
    Box(
        Modifier.size(sz).clip(CircleShape)
            .background(if (active) c.limeTile else c.raised)
            .border(1.dp, if (active) Color.Transparent else c.buttonBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (active) c.onLime else c.ink, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun OverviewStatTile(
    modifier: Modifier,
    value: String,
    label: String,
    bg: Color,
    fg: Color,
    subFg: Color,
) {
    val compact = VK.responsive.isCompactWidth
    Column(
        modifier.heightIn(min = if (compact) 76.dp else 90.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            value,
            fontSize = if (compact) 22.sp else 27.sp,
            lineHeight = if (compact) 26.sp else 30.sp,
            fontWeight = FontWeight.Normal,
            color = fg,
            letterSpacing = (-0.8).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = subFg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun InputField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, leading: ImageVector? = null, onDone: (() -> Unit)? = null, singleLine: Boolean = true) {
    val c = VK.colors
    TextField(
        value, onValue, modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = c.inkTertiary) },
        leadingIcon = leading?.let { { Icon(it, null, tint = c.inkTertiary) } },
        singleLine = singleLine,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(
            imeAction = if (!singleLine) androidx.compose.ui.text.input.ImeAction.Default
            else if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done
            else androidx.compose.ui.text.input.ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceWell, unfocusedContainerColor = c.surfaceWell,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StageColumn(
    vm: MainViewModel, board: Board, stage: Stage, index: Int, cards: List<Card>, filtering: Boolean, highlight: Set<String>,
    modifier: Modifier, onEdit: (Card) -> Unit, onActions: (Card) -> Unit, onStageOptions: () -> Unit,
) {
    val c = VK.colors
    var draft by rememberSaveable(stage.id) { mutableStateOf("") }
    var showQuickAdd by rememberSaveable(stage.id) { mutableStateOf(false) }
    val isDone = board.isDone(stage.id)

    Column(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(28.dp))
            .padding(14.dp),
    ) {
        // Container Header matching screenshot's "Today Tasks   +"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                stage.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (filtering) "${cards.size}/${stage.cards.size}" else "${stage.cards.size}",
                fontSize = 12.sp,
                color = c.inkTertiary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Tune,
                "Edit stage ${stage.title}",
                tint = c.inkTertiary,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onStageOptions).padding(7.dp),
            )
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(if (showQuickAdd) c.limeTile else Color.Transparent)
                    .clickable { showQuickAdd = !showQuickAdd },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "Quick add card", tint = if (showQuickAdd) c.onLime else c.ink, modifier = Modifier.size(20.dp))
            }
        }

        AnimatedVisibility(showQuickAdd || (!filtering && cards.isEmpty()), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth().clip(CircleShape).background(c.raised)
                    .border(BorderStroke(1.dp, c.border), CircleShape).padding(start = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    draft, { draft = it }, Modifier.weight(1f),
                    placeholder = { Text("Add a task… (try “due friday”)", color = c.inkTertiary, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, tint = c.ink) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.quickAdd(stage.id, draft); draft = ""; showQuickAdd = false }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
                    ),
                )
                if (draft.isNotBlank()) Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c.limeTile).clickable { vm.quickAdd(stage.id, draft); draft = ""; showQuickAdd = false },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ArrowUpward, "Add", tint = c.onLime, modifier = Modifier.size(16.dp)) }
            }
        }

        LazyColumn(
            Modifier.weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (cards.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (filtering) Icons.Rounded.Search else Icons.Rounded.AutoAwesome, null, tint = c.inkTertiary)
                    Text(
                        if (filtering) "No matches" else if (isDone) "Nothing finished yet" else "All clear",
                        color = c.inkSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        if (filtering) "Try a different filter." else "Say “Add … to ${stage.title}”",
                        color = c.inkTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
            itemsIndexed(cards, key = { _, it -> it.id }) { cardIdx, card ->
                TaskCard(
                    card = card,
                    cardIdx = cardIdx,
                    isDone = isDone,
                    highlighted = card.id in highlight,
                    canLeft = index > 0,
                    canRight = index < board.stages.lastIndex,
                    onClick = { onEdit(card) },
                    onLongPress = { onActions(card) },
                    onPriority = { vm.cyclePriority(card.id) },
                    onLeft = { vm.moveCardBy(card.id, -1) },
                    onRight = { vm.moveCardBy(card.id, 1) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(220),
                        fadeOutSpec = tween(180),
                        placementSpec = spring(dampingRatio = 0.78f, stiffness = 320f),
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    card: Card,
    cardIdx: Int,
    isDone: Boolean,
    highlighted: Boolean,
    canLeft: Boolean,
    canRight: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPriority: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VK.colors
    val haptics = LocalHapticFeedback.current

    // Match screenshot's stacked card palette: White, Electric Lime (#EAFF87), Soft Sage (#B5BDB4)
    val baseBg = when {
        highlighted -> c.limeTile
        card.priority == Priority.HIGH && !isDone -> c.limeTile
        cardIdx % 3 == 1 -> c.limeTile
        cardIdx % 3 == 2 -> c.sageTile
        else -> c.raised
    }
    val onCardInk = when (baseBg) {
        c.limeTile -> c.onLime
        c.sageTile -> c.ink
        else -> c.ink
    }
    val onCardSub = when (baseBg) {
        c.limeTile -> c.onLime.copy(alpha = 0.68f)
        c.sageTile -> c.ink.copy(alpha = 0.68f)
        else -> c.inkSecondary
    }

    val bg by animateColorAsState(baseBg, tween(260), label = "cardBg")
    val border by animateColorAsState(
        if (highlighted) c.onLime else Color.Transparent,
        tween(260), label = "cardBorder",
    )

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() })
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        // Editorial row: large display title on left, "Start / 03:20 PM" metadata on right
        val compact = VK.responsive.isCompactWidth
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                card.text,
                fontSize = if (compact) 20.sp else 23.sp,
                lineHeight = if (compact) 24.sp else 27.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-0.6).sp,
                color = if (isDone) onCardSub else onCardInk,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 10.dp),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (card.dueAt != null) "Due" else "Start",
                    fontSize = 11.sp,
                    color = onCardSub,
                )
                Text(
                    clockTime(card.dueAt ?: card.createdAt),
                    fontSize = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = onCardInk,
                    maxLines = 1,
                )
            }
        }

        card.notes?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = onCardSub,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Responsive secondary metadata (wraps cleanly on narrow screens) + guaranteed-visible action buttons
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PriorityPill(card.priority, onClick = onPriority)
                card.tags.take(2).forEach { t ->
                    Text(
                        "#$t",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = onCardInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(CircleShape).background(onCardInk.copy(alpha = 0.08f)).padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                card.dueAt?.let { DueChip(it, isDone) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (canLeft) MoveButton(Icons.AutoMirrored.Rounded.ArrowBack, "Move to previous stage", onCardInk, onLeft)
                if (canRight) MoveButton(Icons.AutoMirrored.Rounded.ArrowForward, "Move to next stage", onCardInk, onRight)
                Icon(
                    Icons.Rounded.MoreHoriz,
                    "More actions",
                    tint = onCardSub,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onLongPress).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun MoveButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.1f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun CommandBar(value: String, onValue: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onTalk: () -> Unit) {
    val c = VK.colors
    val r = VK.responsive
    Surface(color = c.background) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = r.headerGutter, vertical = 6.dp)
                .clip(CircleShape).background(c.raised).border(1.dp, c.buttonBorder, CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accentText, modifier = Modifier.padding(start = 10.dp).size(18.dp))
            TextField(
                value, onValue, Modifier.weight(1f),
                placeholder = { Text("Quick command or tap Voice AI…", color = c.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
                singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
                ),
            )
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = c.accent)
            else if (value.isNotBlank()) Box(Modifier.size(40.dp).clip(CircleShape).background(c.ink).clickable(onClick = onSend), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowUpward, "Run command", tint = c.raised, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            // Gradient Voice AI trigger button
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c.voicePrimary, c.voiceSecondary)))
                    .clickable(onClick = onTalk),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, "Talk to the voice assistant", tint = c.onLime)
            }
        }
    }
}

@Composable
fun ToastHost(vm: MainViewModel, modifier: Modifier = Modifier) {
    val toast by vm.toast.collectAsState()
    val c = VK.colors
    AnimatedVisibility(toast != null, modifier.padding(horizontal = 18.dp), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val t: Toast = toast ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.snackbar).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                when (t.kind) { ToastKind.SUCCESS -> Icons.Rounded.CheckCircle; ToastKind.ERROR -> Icons.Rounded.ErrorOutline; ToastKind.INFO -> Icons.Rounded.Info },
                null, tint = when (t.kind) { ToastKind.SUCCESS -> c.limeTile; ToastKind.ERROR -> Color(0xFFFB7185); ToastKind.INFO -> c.snackbarAction },
            )
            Column(Modifier.weight(1f)) {
                Text(t.title, color = c.onSnackbar, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                t.detail?.let { Text(it, color = c.onSnackbar.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 4) }
            }
            if (t.undo != null) Row(Modifier.clip(CircleShape).clickable { vm.undo(t.undo) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Undo, null, tint = c.snackbarAction, modifier = Modifier.size(15.dp))
                Text(" Undo", color = c.snackbarAction, style = MaterialTheme.typography.labelLarge)
            } else Icon(Icons.Rounded.Close, "Dismiss", tint = c.onSnackbar.copy(alpha = 0.6f), modifier = Modifier.size(18.dp).clickable { vm.dismissToast() })
        }
    }
}
