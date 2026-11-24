package com.voicekanban.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.voicekanban.app.data.NOTE_COLORS
import com.voicekanban.app.data.Note
import com.voicekanban.app.data.newId
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.theme.VK
import com.voicekanban.app.voice.SpeechController

/** Note pages keep their pastel colour in both themes, so text on them is always dark ink. */
private val NOTE_INK = Color(0xFF1B201C)
private val NOTE_INK_SOFT = Color(0xFF1B201C).copy(alpha = 0.66f)

@Composable
fun NotesScreen(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val composing by vm.composing.collectAsState()
    val focusNoteId by vm.focusNoteId.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val speech = remember { SpeechController(ctx) }
    val voice by speech.state.collectAsState()
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    val draftNow by rememberUpdatedState(draft)
    fun dictate() = speech.start(app.settings.language, onResult = { draft = (draftNow.trimEnd() + " " + it).trim() }, onError = { vm.showToast(com.voicekanban.app.ui.ToastKind.ERROR, it) })
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) dictate() }

    LaunchedEffect(focusNoteId) { focusNoteId?.let { openId = it; vm.clearFocusNote() } }

    val notes = app.notes
        .filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "Notes",
            subtitle = "${app.notes.size} note${if (app.notes.size == 1) "" else "s"} · AI note taker",
            leading = { HeaderButton(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search notes", active = searching) { searching = !searching; if (!searching) query = "" } },
            trailing = {
                HeaderButton(Icons.Rounded.Add, "Blank note") {
                    val n = Note(newId("n"), "Untitled note", "", color = NOTE_COLORS[app.notes.size % NOTE_COLORS.size].second)
                    vm.saveNote(n); openId = n.id
                }
            },
        ) {
            AnimatedVisibility(searching) { InputField(query, { query = it }, "Search notes", leading = Icons.Rounded.Search) }
        }

        val r = VK.responsive
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Adaptive(minSize = 156.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = r.gutter, end = r.gutter, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Composer(
                    draft = draft, onDraft = { draft = it }, composing = composing, listening = voice.listening,
                    onMic = {
                        if (voice.listening) speech.stop()
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictate()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onMake = { vm.composeNote(draft) { n -> if (n != null) { draft = ""; openId = n.id } } },
                )
            }
            if (notes.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    if (query.isNotBlank()) "No notes match “$query”." else "Your notes will appear here. Jot down scattered words above — or just say them — and the AI turns them into a tidy, colourful note.",
                    color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
            items(notes, key = { it.id }) { n -> NoteCard(n) { openId = n.id } }
        }
    }

    openId?.let { id -> app.notes.firstOrNull { it.id == id }?.let { n -> NoteEditor(vm, n, onClose = { openId = null }) } ?: run { openId = null } }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, composing: Boolean, listening: Boolean, onMic: () -> Unit, onMake: () -> Unit) {
    val c = VK.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(26.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiAvatar(if (composing) AvatarMood.THINKING else if (listening) AvatarMood.LISTENING else AvatarMood.IDLE, Modifier.size(38.dp), halo = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Drop your thoughts", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Type or say scattered words — I’ll write the note.", fontSize = 12.sp, color = c.inkSecondary)
            }
        }
        TextField(
            draft, onDraft, Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 88.dp),
            placeholder = { Text("e.g. groceries milk eggs, dentist thu 4pm, idea: weekend trip to the hills, call mom", color = c.inkTertiary, fontSize = 14.sp) },
            shape = RoundedCornerShape(18.dp), enabled = !composing,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceWell, unfocusedContainerColor = c.surfaceWell, disabledContainerColor = c.surfaceWell,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
            ),
        )
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val micBg by animateColorAsState(if (listening) c.voiceSecondary else c.surfaceWell, tween(200), label = "mic")
            Box(Modifier.size(46.dp).clip(CircleShape).background(micBg).clickable(enabled = !composing, onClick = onMic), contentAlignment = Alignment.Center) {
                Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening) "Stop dictation" else "Dictate", tint = c.ink, modifier = Modifier.size(20.dp))
            }
            if (listening) Text("Listening…", color = c.inkSecondary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(46.dp).clip(CircleShape).background(if (draft.isNotBlank() && !composing) c.ink else c.surfaceWell)
                    .clickable(enabled = draft.isNotBlank() && !composing, onClick = onMake).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (composing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.ink)
                else Icon(Icons.Rounded.AutoAwesome, null, tint = if (draft.isNotBlank()) c.limeTile else c.inkTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (composing) "Writing…" else "Make note", color = if (draft.isNotBlank() && !composing) c.raised else c.inkTertiary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun NoteCard(n: Note, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(n.color))
            .border(1.dp, NOTE_INK.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(n.title, color = NOTE_INK, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (n.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = NOTE_INK_SOFT, modifier = Modifier.size(16.dp))
        }
        val preview = n.body.lines().filter { it.isNotBlank() }.take(7)
        if (preview.isNotEmpty()) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            preview.forEach { line -> NoteLine(line, compact = true) }
        }
        Text(relativeTime(n.updatedAt), color = NOTE_INK_SOFT, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun NoteEditor(vm: MainViewModel, note: Note, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val composing by vm.composing.collectAsState()
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var editing by remember(note.id) { mutableStateOf(note.body.isBlank()) }
    var addMore by remember(note.id) { mutableStateOf("") }
    // AI rewrites land in the saved note; refresh local copies.
    LaunchedEffect(note.updatedAt) { if (!editing) { title = note.title; body = note.body } }

    val speech = remember { SpeechController(ctx) }
    val voice by speech.state.collectAsState()
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    val addNow by rememberUpdatedState(addMore)
    val app by vm.state.collectAsState()
    fun dictate() = speech.start(app.settings.language, onResult = { addMore = (addNow.trimEnd() + " " + it).trim() }, onError = {})
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) dictate() }

    fun save() { if (title != note.title || body != note.body) vm.saveNote(note.copy(title = title.ifBlank { "Untitled note" }, body = body)) }
    fun close() { save(); onClose() }
    BackHandler { close() }

    val bg by animateColorAsState(Color(note.color), tween(300), label = "noteBg")
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = ::close)
                Spacer(Modifier.weight(1f))
                NoteButton(if (note.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (note.pinned) "Unpin" else "Pin") { save(); vm.toggleNotePin(note.id) }
                NoteButton(if (editing) Icons.Rounded.Visibility else Icons.Rounded.Edit, if (editing) "Preview" else "Edit") { if (editing) save(); editing = !editing }
                NoteButton(Icons.Rounded.DeleteOutline, "Delete note") { vm.deleteNote(note.id); onClose() }
            }
            // Page colour (horizontally scrollable on narrow screens so all 8 colours remain accessible)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NOTE_COLORS.forEach { (_, col) ->
                    val sel = note.color == col
                    Box(
                        Modifier.size(if (sel) 32.dp else 28.dp).clip(CircleShape).background(Color(col))
                            .border(if (sel) 3.dp else 1.dp, NOTE_INK.copy(alpha = if (sel) 0.8f else 0.18f), CircleShape)
                            .clickable { save(); vm.saveNote(note.copy(title = title, body = body, color = col)) },
                    )
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
                if (editing) {
                    PlainField(title, { title = it }, "Title", big = true)
                    PlainField(body, { body = it }, "Write freely — # heading, - bullet, - [ ] to-do, **bold**", big = false)
                } else {
                    Text(title, color = NOTE_INK, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                    Text("Edited ${relativeTime(note.updatedAt)}", color = NOTE_INK_SOFT, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        body.lines().forEachIndexed { i, line ->
                            NoteLine(line, compact = false, onToggle = {
                                val lines = body.lines().toMutableList()
                                lines[i] = if (line.trimStart().startsWith("- [ ]")) line.replaceFirst("- [ ]", "- [x]") else line.replaceFirst(Regex("""- \[[xX]]"""), "- [ ]")
                                body = lines.joinToString("\n")
                                vm.saveNote(note.copy(title = title, body = body))
                            })
                        }
                    }
                }
            }
            // Add more with AI (typed or spoken)
            Row(
                Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.72f)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (voice.listening) NOTE_INK else Color.Transparent).clickable {
                        if (voice.listening) speech.stop()
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictate()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (voice.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, "Dictate", tint = if (voice.listening) Color.White else NOTE_INK, modifier = Modifier.size(20.dp)) }
                TextField(
                    addMore, { addMore = it }, Modifier.weight(1f),
                    placeholder = { Text(if (voice.listening) "Listening…" else "Add words, or tap ✨ to polish", color = NOTE_INK_SOFT, fontSize = 14.sp) },
                    maxLines = 3, enabled = !composing,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = NOTE_INK, unfocusedTextColor = NOTE_INK, cursorColor = NOTE_INK,
                    ),
                )
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(NOTE_INK).clickable(enabled = !composing) {
                        save(); editing = false
                        vm.composeNote(addMore, note.copy(title = title, body = body)) { if (it != null) addMore = "" }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (composing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Rounded.AutoAwesome, "Polish with AI", tint = Color(0xFFEAFF87), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun NoteButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = NOTE_INK, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PlainField(value: String, onValue: (String) -> Unit, placeholder: String, big: Boolean) {
    TextField(
        value, onValue, Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = NOTE_INK_SOFT, fontSize = if (big) 26.sp else 16.sp) },
        textStyle = if (big) MaterialTheme.typography.headlineMedium.copy(color = NOTE_INK, fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyLarge.copy(color = NOTE_INK, lineHeight = 24.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = NOTE_INK,
        ),
    )
}

/** Renders one line of the note's light markdown. */
@Composable
private fun NoteLine(raw: String, compact: Boolean, onToggle: (() -> Unit)? = null) {
    val line = raw.trimEnd()
    val t = line.trimStart()
    val indent = ((line.length - t.length) / 2).coerceAtMost(3) * 14
    val size = if (compact) 13.sp else 16.sp
    when {
        t.isEmpty() -> if (!compact) Spacer(Modifier.height(4.dp))
        t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") -> {
            val level = t.takeWhile { it == '#' }.length
            Text(
                inline(t.drop(level).trim()), color = NOTE_INK,
                fontSize = if (compact) 14.sp else when (level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (compact) 2.dp else 10.dp),
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
        }
        Regex("""^[-*•]\s\[( |x|X)]\s?""").containsMatchIn(t) -> {
            val done = t.contains("[x]", true)
            Row(Modifier.padding(start = indent.dp).let { if (onToggle != null) it.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle) else it }, verticalAlignment = Alignment.Top) {
                Icon(if (done) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = NOTE_INK, modifier = Modifier.padding(top = 2.dp).size(if (compact) 14.dp else 20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    inline(t.replaceFirst(Regex("""^[-*•]\s\[( |x|X)]\s?"""), "")), color = if (done) NOTE_INK_SOFT else NOTE_INK, fontSize = size,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Regex("""^([-*•]|\d+[.)])\s""").containsMatchIn(t) -> {
            val marker = Regex("""^([-*•]|\d+[.)])\s""").find(t)!!.groupValues[1]
            Row(Modifier.padding(start = indent.dp), verticalAlignment = Alignment.Top) {
                Text(if (marker.first().isDigit()) marker else "•", color = NOTE_INK, fontSize = size, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = if (compact) 10.dp else 18.dp))
                Spacer(Modifier.width(4.dp))
                Text(inline(t.substring(marker.length).trim()), color = NOTE_INK, fontSize = size, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            }
        }
        else -> Text(inline(t), color = NOTE_INK, fontSize = size, lineHeight = if (compact) 17.sp else 23.sp, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

/** **bold** spans. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var rest = s
    while (true) {
        val a = rest.indexOf("**")
        val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
        if (a < 0 || b < 0) { append(rest); break }
        append(rest.substring(0, a))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(a + 2, b)) }
        rest = rest.substring(b + 2)
    }
}
