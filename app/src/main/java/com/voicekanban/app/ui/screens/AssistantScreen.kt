package com.voicekanban.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voicekanban.app.data.ChatMessage
import com.voicekanban.app.data.Engine
import com.voicekanban.app.data.Repository
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.theme.VK
import com.voicekanban.app.voice.SpeechController
import com.voicekanban.app.voice.VoiceOutput
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val SUGGESTIONS = listOf(
    "What should I work on next?",
    "Add call the plumber due tomorrow at 10am with high priority",
    "Make a note: milk eggs, dentist thursday 4pm, call mom",
    "Save my Netflix login with a strong password",
    "Give me a summary",
)

/**
 * Assistant tab. Voice mode talks with the interactive avatar (spoken in the
 * chosen language with an ElevenLabs voice and a rolling memory of the voice
 * conversation). Chat mode is typed only, with separate saved sessions.
 */
@Composable
fun AssistantScreen(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val undoable by vm.undoable.collectAsState()
    val autoListen by vm.autoListen.collectAsState()
    val keys by vm.keys.collectAsState()
    val settings = app.settings
    val scope = rememberCoroutineScope()

    val speech = remember { SpeechController(ctx) }
    val voiceOut = remember { VoiceOutput(ctx) }
    val voice by speech.state.collectAsState()
    val speakingId by voiceOut.speakingId.collectAsState()
    val outLevel by voiceOut.level.collectAsState()
    val lastVoiceEngine by voiceOut.lastEngine.collectAsState()
    val voiceError by voiceOut.lastError.collectAsState()
    // Say clearly when ElevenLabs can't speak (instead of silently using another voice).
    LaunchedEffect(voiceError) { voiceError?.let { vm.showToast(com.voicekanban.app.ui.ToastKind.ERROR, "Voice problem", it) } }
    DisposableEffect(Unit) { onDispose { speech.cancel(); voiceOut.shutdown() } }

    var chatMode by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    val settingsNow by rememberUpdatedState(settings)
    val elevenKey = keys[Repository.ELEVENLABS].orEmpty()
    lateinit var listen: () -> Unit

    /** Always the ElevenLabs voice chosen for the current language (a native speaker is picked automatically). */
    fun speak(id: String, text: String, then: (() -> Unit)? = null) {
        scope.launch {
            val v = vm.voiceForSpeech()
            voiceOut.speak(id, text, v?.id.orEmpty(), v?.name.orEmpty(), elevenKey, settingsNow.language, then, model = v?.model.orEmpty())
        }
    }

    fun sendVoice(text: String) {
        voiceOut.stop()
        vm.sendChat(text, fromVoice = true) { reply ->
            if (settingsNow.speakReplies) speak(reply.id, reply.text) { if (settingsNow.handsFree && !reply.isError) listen() }
        }
    }

    fun sendTyped(text: String) {
        draft = ""
        vm.sendChat(text, fromVoice = false)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else vm.addChatError("Microphone permission is needed for voice. You can still type in Chat.", fromVoice = true)
    }
    listen = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            voiceOut.stop()
            speech.start(
                languageTag = settingsNow.language,
                onResult = { sendVoice(it) },
                onError = { vm.addChatError(it, fromVoice = true) },
            )
        }
    }

    // Opened with "listen" (from the board's mic): start listening straight away - no canned greeting.
    LaunchedEffect(autoListen) {
        if (autoListen) {
            vm.consumeAutoListen()
            chatMode = false
            kotlinx.coroutines.delay(200)
            listen()
        }
    }
    LaunchedEffect(chatMode) { if (chatMode) { speech.cancel(); voiceOut.stop() } }

    val engineLabel = settings.engine.label
    val session = app.activeSession

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = if (chatMode) session?.title?.takeIf { it != "New chat" } ?: "Chat" else "Voice AI",
            subtitle = "${settings.userName.ifBlank { "Friend" }} · ${settings.appLanguage.nativeName} · $engineLabel",
            leading = {
                if (chatMode) HeaderButton(Icons.Rounded.History, "Chat history") { historyOpen = true }
                else HeaderButton(Icons.Rounded.RecordVoiceOver, "Voice") { chatMode = false }
            },
            trailing = {
                if (chatMode) HeaderButton(Icons.Rounded.Add, "New chat") { vm.newSession() }
                else HeaderButton(
                    if (settings.speakReplies) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    "Spoken replies", active = settings.speakReplies,
                ) {
                    if (settings.speakReplies) voiceOut.stop()
                    vm.updateSettings { it.copy(speakReplies = !it.speakReplies) }
                }
            },
            onTitle = if (chatMode) ({ historyOpen = true }) else null,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Voice", !chatMode, Modifier.weight(1f), Icons.Rounded.GraphicEq) { chatMode = false }
                Pill("Chat", chatMode, Modifier.weight(1f), Icons.Rounded.Forum) { chatMode = true }
            }
        }

        AnimatedContent(chatMode, Modifier.weight(1f), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) }, label = "mode") { chat ->
            if (!chat) {
                val mood = when {
                    voice.listening -> AvatarMood.LISTENING
                    thinking -> AvatarMood.THINKING
                    speakingId != null -> AvatarMood.SPEAKING
                    else -> AvatarMood.IDLE
                }
                val last = app.voiceChat.lastOrNull()?.takeIf { !it.fromUser }
                VoicePane(
                    mood = mood,
                    userName = settings.userName,
                    languageLabel = settings.appLanguage.nativeName,
                    voiceLabel = when {
                        elevenKey.isBlank() -> "Add an ElevenLabs key in Settings for a natural ${settings.appLanguage.label} voice"
                        else -> settings.voiceFor()?.name?.let { "$it · ElevenLabs" } ?: lastVoiceEngine.ifBlank { "ElevenLabs" }
                    },
                    micLevel = voice.level,
                    outLevel = outLevel,
                    loadingModel = false,
                    last = last,
                    canUndo = last?.let { it.id in undoable } == true,
                    onTalk = {
                        when {
                            voice.listening -> speech.stop()
                            speakingId != null -> { voiceOut.stop(); listen() }
                            !thinking -> listen()
                        }
                    },
                    onUndo = { last?.let { vm.undoChat(it.id) } },
                    onChat = { chatMode = true },
                    onReplay = { last?.let { speak(it.id, it.text) } },
                )
            } else ChatPane(
                vm = vm,
                messages = session?.messages.orEmpty(),
                thinking = thinking,
                undoable = undoable,
                draft = draft,
                onDraft = { draft = it },
                onSend = { sendTyped(it) },
            )
        }
    }

    if (historyOpen) ChatHistorySheet(vm, onDismiss = { historyOpen = false })
}

@Composable
private fun VoicePane(
    mood: AvatarMood,
    userName: String,
    languageLabel: String,
    voiceLabel: String,
    micLevel: Float,
    outLevel: Float,
    loadingModel: Boolean,
    last: ChatMessage?,
    canUndo: Boolean,
    onTalk: () -> Unit,
    onUndo: () -> Unit,
    onChat: () -> Unit,
    onReplay: () -> Unit,
) {
    val c = VK.colors
    val displayName = userName.trim().ifBlank { "Friend" }
    val label = when (mood) {
        AvatarMood.IDLE -> "Tap me to talk, $displayName"
        AvatarMood.LISTENING -> "I’m listening ($languageLabel)"
        AvatarMood.THINKING -> if (loadingModel) "Waking up the model" else "Thinking…"
        AvatarMood.SPEAKING -> "Speaking"
    }

    val r = VK.responsive
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = r.gutter, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        c.surface,
                        c.voicePrimary.copy(alpha = if (c.isDark) 0.12f else 0.34f),
                        c.voiceSecondary.copy(alpha = if (c.isDark) 0.16f else 0.22f),
                        c.surface,
                    ),
                ),
            )
            .border(1.dp, c.border, RoundedCornerShape(32.dp)),
    ) {
        val landscapeSplit = maxWidth > maxHeight && maxHeight < 500.dp
        val avatarSize = if (landscapeSplit) {
            minOf(maxHeight * 0.76f, maxWidth * 0.38f, 240.dp).coerceAtLeast(110.dp)
        } else {
            minOf(maxWidth * 0.64f, maxHeight * 0.40f, 300.dp).coerceAtLeast(110.dp)
        }

        val voiceBadge: @Composable () -> Unit = {
            Row(
                Modifier.clip(CircleShape).background(c.raised.copy(alpha = 0.85f)).border(1.dp, c.border, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = c.accentText, modifier = Modifier.size(15.dp))
                Text("  $voiceLabel", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val avatarView: @Composable () -> Unit = {
            AiAvatar(
                mood = mood,
                level = if (mood == AvatarMood.SPEAKING) outLevel else micLevel,
                interactive = true,
                onTap = onTalk,
                modifier = Modifier.size(avatarSize)
                    .semantics { contentDescription = "Voice assistant: $label. Tap to ${if (mood == AvatarMood.IDLE) "talk" else "stop"}." },
            )
        }
        val statusAndControls: @Composable ColumnScope.() -> Unit = {
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                fontSize = if (landscapeSplit) 20.sp else if (r.isCompactWidth) 22.sp else 26.sp,
                lineHeight = if (landscapeSplit) 24.sp else 30.sp,
                color = c.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                when (mood) {
                    AvatarMood.IDLE -> "Ask anything, or tell me to add, change or delete tasks, notes and logins. Drag across me - I’ll watch your finger."
                    AvatarMood.LISTENING -> "Go ahead - tap me when you’re done."
                    AvatarMood.THINKING -> if (loadingModel) "First reply on this phone takes a few seconds." else "Working on it…"
                    AvatarMood.SPEAKING -> "Tap me to interrupt and speak."
                },
                style = MaterialTheme.typography.bodySmall, color = c.inkSecondary, textAlign = TextAlign.Center,
                maxLines = if (landscapeSplit) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (landscapeSplit) 4.dp else 6.dp).widthIn(max = 340.dp),
            )

            AnimatedVisibility(mood == AvatarMood.IDLE && last != null) {
                Row(
                    Modifier.padding(top = if (landscapeSplit) 8.dp else 12.dp).clip(CircleShape)
                        .background(if (last?.isError == true) c.dangerSoft else c.raised)
                        .border(1.dp, if (last?.isError == true) c.danger else c.border, CircleShape)
                        .padding(horizontal = 12.dp, vertical = if (landscapeSplit) 6.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        last?.isError == true -> {
                            Icon(Icons.Rounded.WarningAmber, null, tint = c.danger, modifier = Modifier.size(16.dp))
                            Text(last.text.take(60), color = c.danger, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        last?.changes?.isNotEmpty() == true -> {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.low, modifier = Modifier.size(16.dp))
                            val n = last.changes.size
                            Text("$n change${if (n == 1) "" else "s"} made", color = c.ink, style = MaterialTheme.typography.labelMedium)
                            if (canUndo) Row(Modifier.clickable(onClick = onUndo), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Undo, null, tint = c.accentText, modifier = Modifier.size(14.dp))
                                Text(" Undo", color = c.accentText, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        else -> {
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = c.accentText, modifier = Modifier.size(16.dp))
                            Text("Say it again", color = c.ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onReplay))
                        }
                    }
                }
            }

            Spacer(Modifier.height(if (landscapeSplit) 8.dp else 14.dp))

            Row(Modifier.fillMaxWidth().padding(bottom = if (landscapeSplit) 4.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.heightIn(min = if (landscapeSplit) 44.dp else 50.dp).clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(c.voicePrimary, c.voiceSecondary)))
                        .clickable(onClick = onTalk).padding(horizontal = if (r.isCompactWidth) 18.dp else 22.dp, vertical = if (landscapeSplit) 9.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (mood == AvatarMood.LISTENING) Icons.Rounded.Stop else Icons.Rounded.Mic, null, tint = c.onLime, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (mood) { AvatarMood.LISTENING -> "Done speaking"; AvatarMood.SPEAKING -> "Interrupt & talk"; else -> "Start talking" },
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.onLime, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(if (landscapeSplit) 44.dp else 50.dp).clip(CircleShape).background(c.raised).border(1.dp, c.buttonBorder, CircleShape).clickable(onClick = onChat),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Forum, "Open chat", tint = c.ink, modifier = Modifier.size(20.dp)) }
            }
        }

        if (landscapeSplit) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.weight(0.40f), contentAlignment = Alignment.Center) { avatarView() }
                Column(
                    Modifier.weight(0.60f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    voiceBadge()
                    Spacer(Modifier.height(6.dp))
                    statusAndControls()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = if (r.isCompactWidth) 14.dp else 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                voiceBadge()
                Spacer(Modifier.height(12.dp))
                avatarView()
                Spacer(Modifier.height(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) { statusAndControls() }
            }
        }
    }
}

@Composable
private fun ChatPane(
    vm: MainViewModel,
    messages: List<ChatMessage>,
    thinking: Boolean,
    undoable: Map<String, *>,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val c = VK.colors
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + if (thinking) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AiAvatar(AvatarMood.IDLE, Modifier.size(120.dp), interactive = true)
                    Text("What’s on your mind?", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Ask questions, or have me add, edit and delete tasks, notes and saved logins. Each chat keeps its own history.",
                        color = c.inkSecondary, textAlign = TextAlign.Center, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    SUGGESTIONS.forEach { s ->
                        Row(
                            Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised)
                                .border(1.dp, c.buttonBorder, RoundedCornerShape(18.dp)).clickable { onSend(s) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accentText, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(10.dp))
                            Text(s, color = c.ink, fontSize = 14.sp)
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                Bubble(m, canUndo = m.id in undoable, onUndo = { vm.undoChat(m.id) })
            }
            if (thinking) item {
                Row(verticalAlignment = Alignment.Bottom) {
                    AiAvatar(AvatarMood.THINKING, Modifier.size(34.dp), halo = false); Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(c.raised).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Thinking…", color = c.inkSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.raised)
                .border(1.dp, c.border, RoundedCornerShape(26.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                // Enter inserts a new line; use the Send button to send the message.
                draft, onDraft, Modifier.weight(1f),
                placeholder = { Text("Message the assistant…", color = c.inkSecondary) },
                enabled = !thinking, singleLine = false, maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.accent,
                ),
            )
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (draft.isNotBlank()) c.ink else c.surfaceWell)
                    .clickable(enabled = !thinking && draft.isNotBlank()) { onSend(draft) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = if (draft.isNotBlank()) c.raised else c.inkTertiary, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, canUndo: Boolean, onUndo: () -> Unit) {
    val c = VK.colors
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (m.fromUser) 22.dp else 6.dp, bottomEnd = if (m.fromUser) 6.dp else 22.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubbleW = (maxWidth * if (m.fromUser) 0.84f else 0.78f).coerceAtMost(480.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
            if (!m.fromUser) { AiAvatar(if (m.isError) AvatarMood.THINKING else AvatarMood.IDLE, Modifier.size(34.dp), halo = false); Spacer(Modifier.width(8.dp)) }
            Column(
                Modifier.widthIn(max = maxBubbleW).clip(shape)
                .background(if (m.fromUser) c.limeTile else if (m.isError) c.dangerSoft else c.raised)
                .border(1.dp, if (m.isError) c.danger else if (m.fromUser) Color.Transparent else c.border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SelectionContainer { Text(m.text, color = if (m.fromUser) c.onLime else if (m.isError) c.danger else c.ink, fontSize = 15.sp, lineHeight = 21.sp) }
            if (m.changes.isNotEmpty() || m.failures.isNotEmpty()) {
                Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(c.border))
                m.changes.forEach { ch ->
                    val undone = ch.startsWith("↩︎")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Icon(if (undone) Icons.Rounded.Undo else Icons.Rounded.CheckCircle, null, tint = if (undone) c.inkTertiary else c.low, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp)); Text(ch.removePrefix("↩︎ "), fontSize = 12.sp, color = c.inkSecondary)
                    }
                }
                m.failures.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = c.medium, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp)); Text(f, fontSize = 12.sp, color = c.inkSecondary)
                    }
                }
            }
            if (!m.fromUser && !m.isError) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(m.engine?.uppercase(), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.createdAt))).joinToString(" · "),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c.inkTertiary, modifier = Modifier.weight(1f, fill = false),
                )
                    if (canUndo) {
                        Spacer(Modifier.width(10.dp))
                        Row(Modifier.clip(CircleShape).clickable(onClick = onUndo).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Undo, null, tint = c.accentText, modifier = Modifier.size(13.dp)); Text(" Undo", fontSize = 12.sp, color = c.accentText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistorySheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    AppSheet("Chats", "${app.sessions.size} conversation${if (app.sessions.size == 1) "" else "s"}", onDismiss = onDismiss) {
        Row(
            Modifier.padding(top = 6.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.limeTile)
                .clickable { vm.newSession(); onDismiss() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = c.onLime); Spacer(Modifier.width(10.dp))
            Text("New chat", color = c.onLime, style = MaterialTheme.typography.titleMedium)
        }
        if (app.sessions.isEmpty()) Text("No chats yet.", color = c.inkSecondary, modifier = Modifier.padding(vertical = 12.dp))
        app.sessions.filter { it.messages.isNotEmpty() || it.id == app.activeSessionId }.forEach { s ->
            val active = s.id == app.activeSessionId
            Row(
                Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(if (active) c.raised else c.surface)
                    .border(1.dp, if (active) c.borderStrong else c.border, RoundedCornerShape(18.dp))
                    .clickable { vm.openSession(s.id); onDismiss() }.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiAvatar(AvatarMood.IDLE, Modifier.size(30.dp), halo = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = c.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val preview = s.messages.lastOrNull()?.text?.replace("\n", " ").orEmpty()
                    Text(
                        "${relativeTime(s.updatedAt)} · ${s.messages.size} message${if (s.messages.size == 1) "" else "s"}" + if (preview.isNotBlank()) " · $preview" else "",
                        color = c.inkSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { vm.deleteSession(s.id) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete chat", tint = c.inkTertiary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
