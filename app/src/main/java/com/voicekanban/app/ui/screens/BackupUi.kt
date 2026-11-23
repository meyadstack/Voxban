package com.voicekanban.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.Backup
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Settings → Backup & restore. */
@Composable
fun BackupPanel(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    val vaultCount = vm.vault.collectAsState().value.size

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.Default) { vm.exportJson(pass) }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }.onSuccess {
                vm.showToast(ToastKind.SUCCESS, "Backup saved", if (pass.isNotBlank() && vaultCount > 0) "Passwords are encrypted with your backup password." else "Passwords were not included.")
            }.onFailure { vm.showToast(ToastKind.ERROR, "Couldn’t save the backup", it.message) }
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Export everything", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(
            "Saves boards, notes, chats, money and settings to one JSON file. Keep it somewhere safe (Drive, email to yourself) and restore it after reinstalling. API keys are not included.",
            fontSize = 12.sp, color = c.inkSecondary,
        )
        if (vaultCount > 0) {
            InputField(pass, { pass = it }, "Backup password for your $vaultCount saved logins", leading = Icons.Rounded.Key)
            Text(
                if (pass.isBlank()) "Leave empty to export without passwords." else "Passwords will be encrypted. You’ll need this password to restore them — it can’t be recovered.",
                fontSize = 11.sp, color = if (pass.isBlank()) c.inkTertiary else c.medium,
            )
        }
        Button(
            onClick = { create.launch("voxban-backup-${java.time.LocalDate.now()}.json") },
            Modifier.fillMaxWidth().height(48.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
        ) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export data (.json)") }

        Text("Restore from a backup", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 10.dp))
        Text("Replaces everything on this phone with the backup’s data.", fontSize = 12.sp, color = c.inkSecondary)
        RestoreButton(vm, outlined = true)
    }
}

/** Picks a backup file, asks for the backup password if needed, confirms, then restores. */
@Composable
fun RestoreButton(vm: MainViewModel, outlined: Boolean, label: String = "Import backup (.json)", onRestored: () -> Unit = {}) {
    val c = VK.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<Backup.Parsed?>(null) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                withContext(Dispatchers.Default) { Backup.parse(text) }
            }.onSuccess { parsed = it; pass = ""; error = null }
                .onFailure { vm.showToast(ToastKind.ERROR, "Couldn’t read that file", it.message) }
        }
    }

    val content: @Composable RowScope.() -> Unit = { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
    if (outlined) OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, c.buttonBorder), content = content)
    else Button(
        onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.buttonBorder),
        colors = ButtonDefaults.buttonColors(containerColor = c.raised, contentColor = c.ink), content = content,
    )

    parsed?.let { p ->
        val s = p.state
        AlertDialog(
            onDismissRequest = { parsed = null },
            title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (p.exportedAt > 0) "Saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.exportedAt))}.\n" else "") +
                            "${s.workspace.boards.size} boards · ${s.notes.size} notes · ${s.sessions.size} chats · ${s.transactions.size} transactions" +
                            (if (p.hasPasswords) " · saved logins (encrypted)" else "") +
                            "\n\nEverything currently on this phone will be replaced.",
                    )
                    if (p.hasPasswords) {
                        InputField(pass, { pass = it; error = null }, "Backup password", leading = Icons.Rounded.Key)
                        Text("Leave empty to restore without passwords.", fontSize = 11.sp, color = c.inkTertiary)
                    }
                    error?.let { Text(it, color = c.danger, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val vault = if (p.hasPasswords && pass.isNotBlank()) runCatching { p.vault(pass) }.getOrElse { error = it.message; return@TextButton } else null
                    val msg = vm.restore(p, vault)
                    parsed = null
                    vm.showToast(ToastKind.SUCCESS, "Backup restored", msg)
                    onRestored()
                }) { Text("Restore", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { parsed = null }) { Text("Cancel") } },
        )
    }
}
