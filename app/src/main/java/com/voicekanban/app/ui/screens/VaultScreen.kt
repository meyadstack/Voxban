package com.voicekanban.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.newId
import com.voicekanban.app.security.ActivePasswordShare
import com.voicekanban.app.security.BiometricGate
import com.voicekanban.app.security.PasswordShareManager
import com.voicekanban.app.security.ReceiveShareResult
import com.voicekanban.app.security.ShareMode
import com.voicekanban.app.security.findFragmentActivity
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TILE_COLORS = listOf(0xFFEAFF87, 0xFFC8F2DC, 0xFFCFE6FF, 0xFFE3DAFF, 0xFFFFDCC7, 0xFFFFD6E2)

/** Password manager. Entries live in encrypted storage; supports biometric unlock and one-time QR sharing with friends. */
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
    var sharingEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var scanShareOpen by remember { mutableStateOf(false) }

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
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderButton(Icons.Rounded.QrCodeScanner, "Scan friend's shared password QR", enabled = unlocked) { scanShareOpen = true }
                    HeaderButton(Icons.Rounded.Add, "New login", enabled = unlocked) { editing = PasswordEntry(newId("p"), "", password = vm.generatePassword()) }
                }
            },
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
                    Text("Tap +, scan a friend's one-time QR, or tell the assistant: “Save my Gmail login with a strong password.”", color = c.inkSecondary, textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
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
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { sharingEntry = e }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.QrCode2, "Share via 1-time QR", tint = c.inkSecondary, modifier = Modifier.size(19.dp))
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { copySecret(ctx, "Password", e.password); vm.showToast(ToastKind.SUCCESS, "Password copied", "Clears from the clipboard in 45 s") }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ContentCopy, "Copy password", tint = c.inkSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    openId?.let { id ->
        vault.firstOrNull { it.id == id }?.let { e ->
            EntrySheet(
                vm = vm,
                e = e,
                onDismiss = { openId = null },
                onEdit = { openId = null; editing = e },
                onShareQr = { openId = null; sharingEntry = e },
            )
        }
    }
    editing?.let { e -> EntryEditor(vm, e, onDismiss = { editing = null }) }
    sharingEntry?.let { e -> SharePasswordQrSheet(e, onDismiss = { sharingEntry = null }) }
    if (scanShareOpen) ReceivePasswordQrSheet(vm, onDismiss = { scanShareOpen = false })
}

/** Displays a single-use encrypted QR code with live countdown & instant-burn status. */
@Composable
private fun SharePasswordQrSheet(entry: PasswordEntry, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val hasWifiLan = remember { PasswordShareManager.detectLanIpv4() != null }
    var mode by remember { mutableStateOf(if (hasWifiLan) ShareMode.LAN_INSTANT_BURN else ShareMode.OFFLINE_TIMED) }
    var ttlSec by remember { mutableIntStateOf(120) }
    var burned by remember { mutableStateOf(false) }
    var claimedByFriend by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableIntStateOf(120) }
    var share by remember { mutableStateOf<ActivePasswordShare?>(null) }

    fun regenerate() {
        share?.burn()
        burned = false
        claimedByFriend = false
        val created = PasswordShareManager.createShare(
            entry = entry,
            preferredMode = mode,
            ttlSeconds = ttlSec,
            onBurned = {
                claimedByFriend = true
                burned = true
            },
        )
        share = created
        remainingSec = ttlSec
    }

    LaunchedEffect(mode, ttlSec) { regenerate() }

    DisposableEffect(Unit) {
        onDispose { share?.burn() }
    }

    LaunchedEffect(share?.shareId, burned) {
        val current = share ?: return@LaunchedEffect
        while (!burned) {
            val left = ((current.expiresAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            remainingSec = left
            if (current.isClaimed) {
                claimedByFriend = true
                burned = true
                break
            }
            if (left <= 0) {
                current.burn()
                burned = true
                break
            }
            delay(400)
        }
    }

    AppSheet(
        title = "Share “${entry.title}”",
        subtitle = "Encrypted one-time QR code for a friend's Voxban Vault",
        onDismiss = { share?.burn(); onDismiss() },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Same Wi-Fi", mode == ShareMode.LAN_INSTANT_BURN, Modifier.weight(1f), Icons.Rounded.Wifi) {
                if (hasWifiLan) mode = ShareMode.LAN_INSTANT_BURN
            }
            Pill("Any Network", mode == ShareMode.OFFLINE_TIMED, Modifier.weight(1f), Icons.Rounded.WifiOff) {
                mode = ShareMode.OFFLINE_TIMED
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, tint = c.inkTertiary, modifier = Modifier.size(15.dp))
            Text("Expires in:", fontSize = 12.sp, color = c.inkSecondary)
            Chip("2 min", selected = ttlSec == 120) { ttlSec = 120 }
            Chip("5 min", selected = ttlSec == 300) { ttlSec = 300 }
        }

        if (burned) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(if (claimedByFriend) c.lowSoft else c.surface)
                    .border(1.dp, if (claimedByFriend) c.low else c.border, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (claimedByFriend) Icons.Rounded.CheckCircle else Icons.Rounded.LocalFireDepartment,
                    null,
                    tint = if (claimedByFriend) c.low else c.medium,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    if (claimedByFriend) "Claimed & Burned!" else "One-Time QR Burned",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.ink,
                )
                Text(
                    if (claimedByFriend) "Your friend scanned “${entry.title}” and this QR code was automatically destroyed."
                    else "This one-time QR code has expired or was manually burned and can no longer be used.",
                    fontSize = 13.sp,
                    color = c.inkSecondary,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = ::regenerate,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Generate new 1-time QR")
                }
            }
        } else {
            val active = share
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White)
                    .border(1.dp, c.border, RoundedCornerShape(24.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                active?.let {
                    Image(
                        bitmap = it.qrBitmap.asImageBitmap(),
                        contentDescription = "One-time password QR code",
                        modifier = Modifier.size(230.dp),
                    )
                }
                val mins = remainingSec / 60
                val secs = remainingSec % 60
                Row(
                    Modifier.clip(CircleShape).background(Color(0xFFEAFF87)).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Timer, null, tint = Color(0xFF1B201C), modifier = Modifier.size(14.dp))
                    Text(
                        "One-time QR · expires in %d:%02d".format(mins, secs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1B201C),
                    )
                }
                Text(
                    if (active?.mode == ShareMode.LAN_INSTANT_BURN)
                        "AES-256-GCM encrypted. Auto-destroys on the very first scan over Wi-Fi."
                    else
                        "AES-256-GCM encrypted. Works offline on any network, expires automatically, and can only be imported once.",
                    fontSize = 11.sp,
                    color = Color(0xFF4A5568),
                    textAlign = TextAlign.Center,
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        active?.qrText?.let { copySecret(ctx, "One-time share link", it, sensitive = true) }
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy code", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        share?.burn()
                        burned = true
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.dangerSoft, contentColor = c.danger),
                ) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Burn Now", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

private enum class ReceiveTab { SCAN, PASTE }

/** Scans or pastes a friend's one-time password share QR and imports it into the encrypted Vault. */
@Composable
private fun ReceivePasswordQrSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ReceiveTab.SCAN) }
    var pasteInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scanPaused by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var received by remember { mutableStateOf<ReceiveShareResult.Success?>(null) }

    var hasCameraPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCameraPerm = ok
        if (!ok) errorMsg = "Camera permission is needed to scan QR codes (or switch to Paste Code)."
    }

    fun processQr(raw: String) {
        if (busy) return
        busy = true
        scanPaused = true
        errorMsg = null
        scope.launch {
            val res = PasswordShareManager.receiveShare(
                rawQr = raw,
                isAlreadyConsumed = { vm.repo.isPasswordShareConsumed(it) },
                markConsumed = { vm.repo.markPasswordShareConsumed(it) },
            )
            when (res) {
                is ReceiveShareResult.Success -> received = res
                is ReceiveShareResult.Error -> errorMsg = res.message
            }
            busy = false
        }
    }

    AppSheet(
        title = "Receive Shared Password",
        subtitle = "Scan your friend's one-time QR code to save directly into your Vault",
        onDismiss = onDismiss,
    ) {
        val got = received
        if (got != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.lowSoft)
                    .border(1.dp, c.low.copy(alpha = 0.35f), RoundedCornerShape(22.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.low)
                    Text(
                        if (got.burnedOnSender) "Decrypted & burned on sender!" else "One-time QR decrypted!",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.ink,
                    )
                }
                Text("Site: ${got.entry.title}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                if (got.entry.username.isNotBlank()) Text("Username: ${got.entry.username}", fontSize = 13.sp, color = c.inkSecondary)
                if (got.entry.url.isNotBlank()) Text("URL: ${got.entry.url}", fontSize = 12.sp, color = c.inkSecondary)
                Text("Password: ${"•".repeat(got.entry.password.length.coerceIn(8, 16))}", fontSize = 13.sp, color = c.inkSecondary)
                Button(
                    onClick = {
                        vm.saveEntry(got.entry)
                        vm.showToast(ToastKind.SUCCESS, "Saved shared login", got.entry.title)
                        onDismiss()
                    },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) { Text("Save to My Vault", fontWeight = FontWeight.SemiBold) }
            }
            return@AppSheet
        }

        errorMsg?.let { err ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(c.dangerSoft).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp))
                Text(err, fontSize = 12.sp, color = c.danger)
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Scan QR", tab == ReceiveTab.SCAN, Modifier.weight(1f), Icons.Rounded.QrCodeScanner) { tab = ReceiveTab.SCAN }
            Pill("Paste Code", tab == ReceiveTab.PASTE, Modifier.weight(1f), Icons.Rounded.Keyboard) { tab = ReceiveTab.PASTE }
        }

        when (tab) {
            ReceiveTab.SCAN -> {
                if (!hasCameraPerm) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, null, tint = c.accent, modifier = Modifier.size(38.dp))
                        Text("Allow camera to scan friend's QR", style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Button(
                            onClick = { camLauncher.launch(Manifest.permission.CAMERA) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                        ) { Text("Allow Camera") }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(22.dp))
                            .background(Color.Black).border(2.dp, c.limeTile, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCameraPreview(
                            enabled = !busy && !scanPaused,
                            onQrDetected = { raw -> processQr(raw) },
                        )
                        Box(Modifier.size(190.dp).border(2.5.dp, c.limeTile.copy(alpha = 0.85f), RoundedCornerShape(24.dp)))
                        if (busy) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = c.limeTile)
                            }
                        } else if (scanPaused) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                Button(
                                    onClick = { scanPaused = false; errorMsg = null },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                                ) { Text("Scan Again", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            ReceiveTab.PASTE -> {
                InputField(pasteInput, { pasteInput = it; errorMsg = null }, "Paste voxban://pwshare?... code")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { processQr(pasteInput) },
                    enabled = pasteInput.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.raised)
                    else Text("Claim & Decrypt", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
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
private fun EntrySheet(vm: MainViewModel, e: PasswordEntry, onDismiss: () -> Unit, onEdit: () -> Unit, onShareQr: () -> Unit) {
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
        Button(
            onClick = onShareQr,
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(50.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
        ) {
            Icon(Icons.Rounded.QrCode2, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share via One-Time QR Code", fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
