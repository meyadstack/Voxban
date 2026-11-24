package com.voicekanban.app.ui.screens

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.Engine
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.ThemeMode
import com.voicekanban.app.security.BiometricGate
import com.voicekanban.app.security.findFragmentActivity
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.SectionLabel
import com.voicekanban.app.ui.theme.VK
import com.voicekanban.app.widget.BoardWidgetReceiver
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onOpenUpdateSheet: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenPairDesktop: () -> Unit = {},
) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    val s = app.settings
    AppSheet("Settings", onDismiss = onDismiss) {
        SectionLabel("Profile & language")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("What should the AI call you?", style = MaterialTheme.typography.labelLarge, color = c.ink)
            InputField(
                value = s.userName,
                onValue = { name -> vm.updateSettings { it.copy(userName = name) } },
                placeholder = "Your name (e.g. Alex)",
            )
            Text("Voice & AI language", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                com.voicekanban.app.data.AppLanguage.entries.forEach { lang ->
                    Chip("${lang.nativeName} · ${lang.label}", selected = s.language == lang.code) {
                        vm.updateSettings { it.copy(language = lang.code) }
                    }
                }
            }
        }

        SectionLabel("AI engine")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Engine.selectable.forEach { e -> Chip(e.label, selected = s.engine == e) { vm.updateSettings { it.copy(engine = e) } } }
        }
        Spacer(Modifier.height(10.dp))
        when (s.engine) {
            else -> CloudEnginePanel(vm, s.engine)
        }

        SectionLabel("Voice")
        VoicePanel(vm)

        SectionLabel("Money")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        ) {
            Text("Currency", style = MaterialTheme.typography.labelLarge, color = c.ink)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Auto (${com.voicekanban.app.data.currencySymbol("")})", selected = s.currency.isBlank()) { vm.updateSettings { it.copy(currency = "") } }
                listOf("৳", "$", "€", "£", "₹", "¥", "₩", "₺", "﷼", "R$").forEach { sym ->
                    Chip(sym, selected = s.currency == sym) { vm.updateSettings { it.copy(currency = sym) } }
                }
            }
        }

        SectionLabel("Workout")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        ) {
            Text("Body weight (for calories): ${s.weightKg.toInt()} kg", style = MaterialTheme.typography.labelLarge, color = c.ink)
            androidx.compose.material3.Slider(s.weightKg.toFloat(), { v -> vm.updateSettings { it.copy(weightKg = v.toInt().toDouble()) } }, valueRange = 30f..160f)
        }

        SectionLabel("Backup & restore")
        BackupPanel(vm)

        SectionLabel("Security")
        BiometricRow(vm)

        SectionLabel("Notifications")
        NotificationRows(vm)

        SectionLabel("Windows PC sync")
        DesktopSyncSettingsPanel(vm, onOpenPairSheet = onOpenPairDesktop)

        SectionLabel("App updates")
        UpdateSettingsPanel(vm, onOpenUpdateSheet = onOpenUpdateSheet)

        SectionLabel("Help & support")
        SupportSettingsPanel(vm, onOpenSupport = onOpenSupport)

        SectionLabel("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m -> Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f), selected = s.themeMode == m) { vm.updateSettings { it.copy(themeMode = m) } } }
        }
        Text("Voxban ${com.voicekanban.app.BuildConfig.VERSION_NAME} · native Kotlin", fontSize = 11.sp, color = c.inkTertiary, modifier = Modifier.padding(top = 20.dp))
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, body: String, tint: Color = VK.colors.accent) {
    val c = VK.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceWell).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = tint)
        Column { Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink); Text(body, fontSize = 12.sp, color = c.inkSecondary) }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = VK.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) c.accent else c.inkTertiary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.ink else c.inkTertiary)
            Text(body, fontSize = 12.sp, color = c.inkSecondary)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------- cloud

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudEnginePanel(vm: MainViewModel, engine: Engine) {
    val c = VK.colors
    val keys by vm.keys.collectAsState()
    val app by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val saved = keys[engine.name].orEmpty()
    var draft by remember(engine) { mutableStateOf(saved) }
    var show by remember { mutableStateOf(false) }
    var status by remember(engine) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var models by remember(engine) { mutableStateOf<List<String>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    val model = app.settings.cloudModels[engine.name] ?: com.voicekanban.app.ai.CloudProviders.DEFAULT_MODELS[engine]

    val hint = when (engine) {
        Engine.OPENAI -> "platform.openai.com → API keys (starts with sk-)"
        Engine.GEMINI -> "aistudio.google.com → Get API key (starts with AIza)"
        Engine.OPENROUTER -> "openrouter.ai/keys (starts with sk-or-). Free models work without credit"
        else -> "console.anthropic.com → API keys (starts with sk-ant-)"
    }

    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceWell).padding(12.dp)) {
        Text("${engine.label} API key", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("Stored encrypted on this phone and sent only to ${if (engine == Engine.OPENROUTER) "OpenRouter" else engine.vendor}. Get one at $hint.", fontSize = 12.sp, color = c.inkSecondary)
        Spacer(Modifier.height(8.dp))
        TextField(
            draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Paste your key", color = c.inkTertiary) },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Rounded.Key, null, tint = c.inkTertiary) },
            trailingIcon = { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key", Modifier.clickable { show = !show }, tint = c.inkSecondary) },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = c.ink, unfocusedTextColor = c.ink),
        )
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    vm.setKey(engine.name, draft)
                    testing = true; status = null
                    scope.launch {
                        status = try { true to vm.testEngine(engine) } catch (e: Exception) { false to (e.message ?: "Failed") }
                        if (status?.first == true) runCatching { models = vm.listModels(engine) }
                        testing = false
                    }
                },
                enabled = draft.isNotBlank() && !testing, shape = RoundedCornerShape(8.dp),
            ) { Text(if (draft != saved) "Save & test" else "Test connection") }
            OutlinedButton(
                onClick = {
                    vm.setKey(engine.name, draft)
                    scope.launch { try { models = vm.listModels(engine); menu = true } catch (e: Exception) { status = false to (e.message ?: "Couldn’t list models") } }
                },
                enabled = draft.isNotBlank(), shape = RoundedCornerShape(8.dp),
            ) { Text("Choose model") }
        }
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = models.isNotEmpty()) { menu = true }, verticalAlignment = Alignment.CenterVertically) {
            Text("Model: ", fontSize = 12.sp, color = c.inkSecondary)
            Text(model ?: "", fontSize = 12.sp, color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (model != null && model in com.voicekanban.app.ai.CloudProviders.freeModels) Text("  FREE", fontSize = 10.sp, color = c.low, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        if (menu) ModelPickerDialog(
            models = models,
            current = model,
            free = com.voicekanban.app.ai.CloudProviders.freeModels,
            onPick = { m ->
                vm.updateSettings { it.copy(cloudModels = it.cloudModels + (engine.name to m)) }
                menu = false; status = null
            },
            onDismiss = { menu = false },
        )
        if (testing) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Testing…", fontSize = 12.sp, color = c.inkSecondary)
        }
        status?.let { (ok, msg) ->
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (ok) c.lowSoft else c.dangerSoft).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) c.low else c.danger, modifier = Modifier.size(18.dp))
                Text(msg, fontSize = 12.sp, color = if (ok) c.low else c.danger)
            }
        }
    }
}

// ---------------------------------------------------------------- security & notifications

@Composable
private fun BiometricRow(vm: MainViewModel) {
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val avail = remember { BiometricGate.availability(ctx) }
    val activity = ctx.findFragmentActivity()
    var note by remember { mutableStateOf<String?>(null) }
    ToggleRow(
        Icons.Rounded.Fingerprint, "Biometric lock",
        when (avail) {
            BiometricGate.Availability.READY -> "Unlock with fingerprint or face (PIN as backup) when the app opens"
            BiometricGate.Availability.NOT_ENROLLED -> "Set up a fingerprint, face or screen lock in your phone settings first"
            BiometricGate.Availability.UNAVAILABLE -> "Not supported on this phone"
        },
        checked = app.settings.biometricLock, enabled = avail == BiometricGate.Availability.READY && activity != null,
    ) { on ->
        // Confirm identity before changing the lock either way.
        BiometricGate.prompt(activity!!, if (on) "Turn on app lock" else "Turn off app lock", "Confirm it’s you",
            onSuccess = { vm.updateSettings { it.copy(biometricLock = on) }; note = if (on) "App lock is on." else "App lock is off." },
            onFail = { note = it })
    }
    note?.let { Text(it, fontSize = 12.sp, color = VK.colors.inkSecondary) }
}

@Composable
private fun NotificationRows(vm: MainViewModel) {
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val s = app.settings
    var granted by remember { mutableStateOf(com.voicekanban.app.reminders.Reminders.canPost(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted && Build.VERSION.SDK_INT >= 33) {
        InfoCard(Icons.Rounded.Notifications, "Notifications are off", "Allow them to get deadline alerts.", VK.colors.medium)
        TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
    }
    ToggleRow(Icons.Rounded.NotificationsActive, "Deadline reminders", "Alert before a card is due (set per card), with Mark done / Snooze buttons", s.remindersEnabled) { on ->
        if (on && !granted && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.updateSettings { it.copy(remindersEnabled = on) }
    }
    if (Build.VERSION.SDK_INT >= 31) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        if (s.remindersEnabled && !am.canScheduleExactAlarms()) TextButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Allow exact alarms for on-time reminders") }
    }
    ToggleRow(Icons.Rounded.Notifications, "Notification bar shortcut", "Silent entry with progress, next task and a Talk button", s.quickActionsEnabled) { on ->
        if (on && !granted && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.updateSettings { it.copy(quickActionsEnabled = on) }
    }
    val awm = ctx.getSystemService(AppWidgetManager::class.java)
    if (awm?.isRequestPinAppWidgetSupported == true) {
        Row(Modifier.fillMaxWidth().clickable { awm.requestPinAppWidget(ComponentName(ctx, BoardWidgetReceiver::class.java), null, null) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Apps, null, tint = VK.colors.accent); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add home-screen widget", style = MaterialTheme.typography.bodyMedium, color = VK.colors.ink)
                Text("Board progress, up-next tasks and a one-tap Voice AI button", fontSize = 12.sp, color = VK.colors.inkSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------- voice

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePanel(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val keys by vm.keys.collectAsState()
    val s = app.settings
    val key = keys[Repository.ELEVENLABS].orEmpty()
    var voices by remember { mutableStateOf<List<com.voicekanban.app.voice.VoiceOutput.Voice>>(emptyList()) }
    var quota by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var keyDraft by remember(key) { mutableStateOf(key) }
    var showKey by remember { mutableStateOf(false) }
    val preview = remember { com.voicekanban.app.voice.VoiceOutput(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { preview.shutdown() } }
    val lang = s.appLanguage
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var adding by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key, lang) {
        voices = emptyList(); quota = null; error = null
        if (key.isBlank()) return@LaunchedEffect
        loading = true
        runCatching {
            // English: the account's voices. Other languages: native speakers from the ElevenLabs Voice Library.
            voices = if (lang == com.voicekanban.app.data.AppLanguage.EN) com.voicekanban.app.voice.VoiceOutput.listVoices(key)
            else com.voicekanban.app.voice.VoiceOutput.listLanguageVoices(key, lang.iso)
        }.onFailure { error = it.message ?: "Couldn’t load voices" }
        quota = com.voicekanban.app.voice.VoiceOutput.quota(key)
        loading = false
    }
    val current = s.voiceFor(lang)
    val usingEleven = key.isNotBlank() && current != null && error == null

    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface).padding(12.dp)) {
        Text("Assistant voice", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(
            when {
                key.isBlank() -> "Add your ElevenLabs API key so replies are spoken by a natural ${lang.label} voice — get one free at elevenlabs.io → Profile → API keys. Without it, replies are only spoken if this phone has a ${lang.label} voice installed."
                usingEleven -> "${lang.nativeName}: spoken by ${current!!.name} (ElevenLabs ${current.model.ifBlank { com.voicekanban.app.voice.VoiceOutput.modelFor(lang.iso) }.replace('_', ' ')})."
                else -> "A native ${lang.label} voice is picked automatically the first time the assistant speaks — or choose one below."
            },
            fontSize = 12.sp, color = c.inkSecondary,
        )

        Text("ElevenLabs API key", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp))
        TextField(
            keyDraft, { keyDraft = it }, Modifier.padding(top = 6.dp).fillMaxWidth(), singleLine = true,
            placeholder = { Text("sk_…", color = c.inkSecondary) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Rounded.Key, null, tint = c.inkSecondary) },
            trailingIcon = { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key", Modifier.clickable { showKey = !showKey }, tint = c.inkSecondary) },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.background, unfocusedContainerColor = c.background, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = c.ink, unfocusedTextColor = c.ink),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.setKey(Repository.ELEVENLABS, keyDraft) }, enabled = keyDraft.trim() != key, shape = RoundedCornerShape(8.dp)) { Text("Save key") }
            if (key.isNotBlank()) TextButton(onClick = { vm.setKey(Repository.ELEVENLABS, ""); keyDraft = "" }) { Text("Remove", color = c.danger) }
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text("Stored encrypted on this phone and sent only to ElevenLabs.", fontSize = 11.sp, color = c.inkSecondary, modifier = Modifier.padding(top = 4.dp))
        error?.let { Text(it, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }

        quota?.let { (used, limit) ->
            val left = (limit - used).coerceAtLeast(0)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { (used.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
            Text("%,d of %,d characters left this month".format(left, limit), fontSize = 12.sp, color = if (left < 500) c.medium else c.inkSecondary, modifier = Modifier.padding(top = 4.dp))
        }

        val previewError by preview.lastError.collectAsState()
        val playing by preview.speakingId.collectAsState()
        (previewError ?: error)?.let { Text(it, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }

        if (voices.isNotEmpty()) {
            Text("${lang.label} voice", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 14.dp))
            Text(
                if (lang != com.voicekanban.app.data.AppLanguage.EN) "Native ${lang.label} speakers from the ElevenLabs Voice Library. ▶ plays the voice’s own sample; tap a voice to use it."
                else "Voices in your ElevenLabs account. Tap one to use it.",
                fontSize = 11.sp, color = c.inkSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                voices.take(25).forEach { v ->
                    val selected = current?.id == v.id || current?.name == v.name
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(if (selected) c.limeTile else c.raised)
                            .border(1.dp, if (selected) Color.Transparent else c.border, RoundedCornerShape(16.dp))
                            .clickable(enabled = adding == null) {
                                adding = v.id
                                scope.launch {
                                    runCatching { com.voicekanban.app.voice.VoiceOutput.addLibraryVoice(key, v) }
                                        .onSuccess { id ->
                                            val model = v.bestModel(lang.iso)
                                            vm.setVoice(lang, com.voicekanban.app.data.VoiceChoice(id, v.name, model))
                                            error = null
                                            // Real test through the API with the model the voice is verified for.
                                            preview.speak("test-$id", sample(lang, v.name), id, v.name, key, lang.code, model = model)
                                        }
                                        .onFailure { error = com.voicekanban.app.voice.VoiceOutput.friendlyError(it.message.orEmpty(), v.name) }
                                    adding = null
                                }
                            }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.name, color = if (selected) c.onLime else c.ink, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                if (v.publicOwnerId != null) Text(
                                    if (v.freeAllowed) "  FREE" else "  PAID PLAN", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = if (selected) c.onLime else if (v.freeAllowed) c.low else c.medium,
                                )
                            }
                            val sub = listOf(v.description, if (adding == v.id) "Adding…" else if (selected) "In use" else "").filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, color = if (selected) c.onLime.copy(alpha = 0.75f) else c.inkSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                        v.previewUrl?.let { url ->
                            val isPlaying = playing == "sample-${v.id}"
                            Icon(
                                if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (isPlaying) "Stop sample" else "Play sample",
                                tint = if (selected) c.onLime else c.ink,
                                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { if (isPlaying) preview.stop() else preview.playSample("sample-${v.id}", url) }.padding(8.dp),
                            )
                        }
                    }
                }
            }
        } else if (key.isNotBlank() && !loading && error == null) {
            Text("No ${lang.label} voices found in the Voice Library for this key.", fontSize = 12.sp, color = c.inkSecondary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

private fun sample(lang: com.voicekanban.app.data.AppLanguage, name: String): String = when (lang) {
    com.voicekanban.app.data.AppLanguage.BN -> "নমস্কার, আমি $name। আপনার কাজে সাহায্য করতে প্রস্তুত।"
    com.voicekanban.app.data.AppLanguage.HI -> "नमस्ते, मैं $name हूँ। आपकी मदद के लिए तैयार हूँ।"
    com.voicekanban.app.data.AppLanguage.ES -> "Hola, soy $name. Lista para ayudarte."
    com.voicekanban.app.data.AppLanguage.AR -> "مرحباً، أنا $name. جاهزة لمساعدتك."
    com.voicekanban.app.data.AppLanguage.FR -> "Bonjour, je suis $name. Prête à vous aider."
    com.voicekanban.app.data.AppLanguage.DE -> "Hallo, ich bin $name. Bereit, dir zu helfen."
    com.voicekanban.app.data.AppLanguage.PT -> "Olá, eu sou $name. Pronta para ajudar."
    com.voicekanban.app.data.AppLanguage.ZH -> "你好，我是${name}，随时为你效劳。"
    com.voicekanban.app.data.AppLanguage.JA -> "こんにちは、${name}です。お手伝いします。"
    com.voicekanban.app.data.AppLanguage.TR -> "Merhaba, ben $name. Yardıma hazırım."
    com.voicekanban.app.data.AppLanguage.KO -> "안녕하세요, 저는 ${name}입니다. 도와드릴게요."
    com.voicekanban.app.data.AppLanguage.EN -> "Hi, I’m $name. Ready when you are."
}

/** Searchable model list — OpenRouter alone has hundreds of models. */
@Composable
private fun ModelPickerDialog(models: List<String>, current: String?, free: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = VK.colors
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    val shown = models.filter { (query.isBlank() || it.contains(query.trim(), true)) && (!freeOnly || it in free) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Choose a model", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                InputField(query, { query = it }, "Search ${models.size} models")
                if (free.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) { Chip("Free only", selected = freeOnly) { freeOnly = !freeOnly } }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 300.dp)) {
                    items(shown.size) { i ->
                        val m = shown[i]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (m == current) c.accentSoft else Color.Transparent)
                                .clickable { onPick(m) }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m, fontSize = 13.sp, color = c.ink, modifier = Modifier.weight(1f))
                            if (m in free) Text("FREE", fontSize = 10.sp, color = c.low, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                    if (shown.isEmpty()) item { Text("No models match.", color = c.inkSecondary, modifier = Modifier.padding(8.dp)) }
                }
            }
        },
        containerColor = c.background,
    )
}
