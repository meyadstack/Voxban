package com.voicekanban.app.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.newId
import com.voicekanban.app.security.BiometricGate
import com.voicekanban.app.security.findFragmentActivity
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.theme.VK

private val TILE_COLORS = listOf(0xFFEAFF87, 0xFFC8F2DC, 0xFFCFE6FF, 0xFFE3DAFF, 0xFFFFDCC7, 0xFFFFD6E2)

/** Password manager. Entries live in encrypted storage; the screen unlocks with fingerprint / face / screen lock. */
@Composable
fun VaultScreen(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val unlocked by vm.vaultUnlocked.collectAsState()
    val vault by vm.vault.collectAsState()
    val focusId by vm.focusVaultId.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PasswordEntry?>(null) }

    fun unlock() {
        val act = ctx.findFragmentActivity()
        if (act == null || BiometricGate.availability(ctx) != BiometricGate.Availability.READY) { vm.vaultUnlocked.value = true; return }
        BiometricGate.prompt(act, "Unlock Vault", "Use your fingerprint, face or screen lock", onSuccess = { vm.vaultUnlocked.value = true }, onFail = {})
    }
    LaunchedEffect(Unit) { if (!unlocked) unlock() }
    LaunchedEffect(focusId, unlocked) { if (unlocked && focusId != null) { openId = focusId; vm.clearFocusVault() } }

    val list = vault.filter { query.isBlank() || it.title.contains(query, true) || it.username.contains(query, true) || it.url.contains(query, true) }
        .sortedBy { it.title.lowercase() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "Vault",
            subtitle = "${vault.size} saved login${if (vault.size == 1) "" else "s"} · encrypted on this phone",
            leading = { HeaderButton(Icons.Rounded.Lock, "Lock vault", enabled = unlocked) { vm.vaultUnlocked.value = false } },
            trailing = { HeaderButton(Icons.Rounded.Add, "New login", enabled = unlocked) { editing = PasswordEntry(newId("p"), "", password = vm.generatePassword()) } },
        ) {
            if (unlocked) InputField(query, { query = it }, "Search logins", leading = Icons.Rounded.Search)
        }

        if (!unlocked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(90.dp).clip(CircleShape).background(c.limeTile), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Shield, null, tint = c.onLime, modifier = Modifier.size(42.dp))
                }
                Text("Your vault is locked", style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                Text("Passwords are encrypted with your phone’s keystore. The AI can add, change and remove logins for you, but never sees or speaks your passwords.", color = c.inkSecondary, textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp).widthIn(max = 360.dp))
                Button(onClick = ::unlock, Modifier.padding(top = 22.dp).heightIn(min = 52.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised)) {
                    Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock")
                }
            }
            return@Column
        }

        val r = VK.responsive
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = r.gutter, end = r.gutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AutofillCard() }
            if (vault.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AiAvatar(AvatarMood.IDLE, Modifier.size(110.dp), interactive = true)
                    Text("No saved logins yet", style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Text("Tap + or just tell the assistant: “Save my Gmail login, username sam@mail.com, make a strong password.”", color = c.inkSecondary, textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(list, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(22.dp))
                        .clickable { openId = e.id }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EntryTile(e, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.username.ifBlank { e.url.ifBlank { "No username" } }, fontSize = 13.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { copySecret(ctx, "Password", e.password); vm.showToast(ToastKind.SUCCESS, "Password copied", "Clears from the clipboard in 45 s") }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ContentCopy, "Copy password", tint = c.inkSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    openId?.let { id -> vault.firstOrNull { it.id == id }?.let { e -> EntrySheet(vm, e, onDismiss = { openId = null }, onEdit = { openId = null; editing = e }) } }
    editing?.let { e -> EntryEditor(vm, e, onDismiss = { editing = null }) }
}

/** Prompts the user to make Voxban the phone's autofill service (save + suggest passwords everywhere). */
@Composable
private fun AutofillCard() {
    val c = VK.colors
    val ctx = LocalContext.current
    val afm = remember { ctx.getSystemService(android.view.autofill.AutofillManager::class.java) }
    var enabled by remember { mutableStateOf(afm?.hasEnabledAutofillServices() == true) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) enabled = afm?.hasEnabledAutofillServices() == true }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    if (afm == null || !afm.isAutofillSupported) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (enabled) c.surface else c.limeTile)
            .border(1.dp, if (enabled) c.border else Color.Transparent, RoundedCornerShape(22.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null, tint = if (enabled) c.low else c.onLime, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(if (enabled) "Autofill is on" else "Save & fill passwords everywhere", style = MaterialTheme.typography.titleMedium, color = if (enabled) c.ink else c.onLime)
        }
        Text(
            if (enabled) "When you create an account Voxban offers to save it, and on sign-in screens it suggests your saved login. In Chrome, also turn on Settings → Autofill services → “Autofill using another service”."
            else "Turn on Voxban autofill: when you sign up on an app or website it offers to save the password, and when you log in it suggests the saved one.",
            fontSize = 13.sp, color = if (enabled) c.inkSecondary else c.onLime.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp),
        )
        if (!enabled) Button(
            onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(android.net.Uri.parse("package:${ctx.packageName}")))
                }.onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
            },
            Modifier.padding(top = 12.dp).height(46.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.onLime, contentColor = c.limeTile),
        ) { Text("Turn on autofill", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EntryTile(e: PasswordEntry, size: Int) {
    val col = TILE_COLORS[(e.title.hashCode() and 0x7fffffff) % TILE_COLORS.size]
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(Color(col)), contentAlignment = Alignment.Center) {
        Text(e.title.trim().take(1).uppercase().ifEmpty { "?" }, color = Color(0xFF1B201C), fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
    }
}

@Composable
private fun EntrySheet(vm: MainViewModel, e: PasswordEntry, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AppSheet(e.title, "Updated ${relativeTime(e.updatedAt)}", onDismiss = onDismiss) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            EntryTile(e, 56); Spacer(Modifier.width(14.dp))
            Text(strength(e.password).first, color = strength(e.password).second(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Field(Icons.Rounded.Person, "Username", e.username.ifBlank { "-" }, onCopy = e.username.takeIf { it.isNotBlank() }?.let { { copySecret(ctx, "Username", it, sensitive = false); vm.showToast(ToastKind.SUCCESS, "Username copied") } })
        Field(
            Icons.Rounded.Key, "Password", if (reveal) e.password else "•".repeat(e.password.length.coerceIn(8, 16)), mono = true,
            trailing = { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show", tint = c.inkSecondary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { reveal = !reveal }.padding(10.dp)) },
            onCopy = { copySecret(ctx, "Password", e.password); vm.showToast(ToastKind.SUCCESS, "Password copied", "Clears from the clipboard in 45 s") },
        )
        if (e.url.isNotBlank()) Field(Icons.Rounded.Language, "Website", e.url)
        if (e.notes.isNotBlank()) Field(Icons.Rounded.Notes, "Notes", e.notes)
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onEdit, Modifier.weight(1f).height(50.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised)) {
                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
            }
            Button(
                onClick = { if (confirmDelete) { vm.deleteEntry(e.id); onDismiss() } else confirmDelete = true },
                Modifier.weight(1f).height(50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.dangerSoft, contentColor = c.danger),
            ) { Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (confirmDelete) "Tap to confirm" else "Delete") }
        }
    }
}

@Composable
private fun Field(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, mono: Boolean = false, trailing: (@Composable () -> Unit)? = null, onCopy: (() -> Unit)? = null) {
    val c = VK.colors
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.inkTertiary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = c.inkTertiary)
            Text(value, color = c.ink, fontSize = 15.sp, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        trailing?.invoke()
        if (onCopy != null) Icon(Icons.Rounded.ContentCopy, "Copy $label", tint = c.inkSecondary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCopy).padding(11.dp))
    }
}

@Composable
private fun EntryEditor(vm: MainViewModel, e: PasswordEntry, onDismiss: () -> Unit) {
    val c = VK.colors
    var title by remember { mutableStateOf(e.title) }
    var user by remember { mutableStateOf(e.username) }
    var pw by remember { mutableStateOf(e.password) }
    var url by remember { mutableStateOf(e.url) }
    var notes by remember { mutableStateOf(e.notes) }
    val isNew = vm.vault.value.none { it.id == e.id }
    AppSheet(if (isNew) "New login" else "Edit login", onDismiss = onDismiss) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InputField(title, { title = it }, "Name (e.g. Gmail, Netflix)", leading = Icons.Rounded.Shield)
            InputField(user, { user = it }, "Username or email", leading = Icons.Rounded.Person)
            InputField(pw, { pw = it }, "Password", leading = Icons.Rounded.Key)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(strength(pw).first, color = strength(pw).second(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
                TextButton(onClick = { pw = vm.generatePassword() }) { Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Generate strong password", color = c.accentText) }
            }
            InputField(url, { url = it }, "Website (optional)", leading = Icons.Rounded.Language)
            InputField(notes, { notes = it }, "Notes (optional)", leading = Icons.Rounded.Notes, singleLine = false)
            Button(
                onClick = { vm.saveEntry(e.copy(title = title.trim(), username = user.trim(), password = pw, url = url.trim(), notes = notes.trim())); vm.showToast(ToastKind.SUCCESS, if (isNew) "Login saved" else "Login updated", title.trim()); onDismiss() },
                enabled = title.isNotBlank() && pw.isNotBlank(),
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(52.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
            ) { Text(if (isNew) "Save login" else "Save changes", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun strength(pw: String): Pair<String, @Composable () -> Color> {
    var classes = 0
    if (pw.any { it.isLowerCase() }) classes++
    if (pw.any { it.isUpperCase() }) classes++
    if (pw.any { it.isDigit() }) classes++
    if (pw.any { !it.isLetterOrDigit() }) classes++
    val score = pw.length / 4 + classes
    return when {
        pw.isEmpty() -> "" to { VK.colors.inkTertiary }
        score >= 8 -> "Strong password" to { VK.colors.low }
        score >= 5 -> "Okay password" to { VK.colors.medium }
        else -> "Weak password" to { VK.colors.high }
    }
}

/** Copies to the clipboard, hidden from clipboard previews, and clears it after 45 s. */
private fun copySecret(ctx: Context, label: String, value: String, sensitive: Boolean = true) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    if (sensitive) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == value) { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() } }
    }, 45_000)
}
