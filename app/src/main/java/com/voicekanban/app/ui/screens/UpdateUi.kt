package com.voicekanban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.theme.VK
import com.voicekanban.app.update.AppUpdater
import com.voicekanban.app.update.ReleaseInfo
import com.voicekanban.app.update.UpdateState

/** Compact in-app banner shown when a newer GitHub release is available or downloading. */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = VK.colors
    val (title, subtitle) = when (state) {
        is UpdateState.Available -> "Update available · v${state.release.version}" to "Tap to view release notes and update inside Voxban"
        is UpdateState.Downloading -> {
            val pct = if (state.progress >= 0f) "${(state.progress * 100).toInt()}%" else AppUpdater.formatBytes(state.downloadedBytes)
            "Downloading v${state.release.version} · $pct" to "Tap to view progress"
        }
        is UpdateState.ReadyToInstall -> "v${state.release.version} ready to install" to "Tap to install update now"
        else -> return
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.limeTile)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(c.onLime),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state is UpdateState.ReadyToInstall) Icons.Rounded.InstallMobile else Icons.Rounded.SystemUpdate,
                null,
                tint = c.limeTile,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.onLime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.onLime.copy(alpha = 0.72f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state is UpdateState.Available) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "Dismiss update banner", tint = c.onLime.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Modal sheet for downloading and installing a GitHub release inside the app. */
@Composable
fun UpdateSheet(
    vm: MainViewModel,
    state: UpdateState,
    onDismiss: () -> Unit,
) {
    val c = VK.colors
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var canInstall by remember { mutableStateOf(AppUpdater.canInstallPackages(ctx)) }
    var pendingAutoInstall by remember { mutableStateOf(false) }

    // Re-check install permission when user returns from Android Settings and auto-launch installer!
    DisposableEffect(lifecycle, state) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val allowed = AppUpdater.canInstallPackages(ctx)
                canInstall = allowed
                if (allowed && pendingAutoInstall && state is UpdateState.ReadyToInstall) {
                    pendingAutoInstall = false
                    AppUpdater.installApk(ctx, state.apkFile)
                }
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    val release: ReleaseInfo? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Error -> state.release
        else -> null
    }

    AppSheet(
        title = release?.title ?: "App update",
        subtitle = release?.let { "v${AppUpdater.currentVersion} → v${it.version}" + if (it.apkSize > 0) " · ${AppUpdater.formatBytes(it.apkSize)}" else "" },
        onDismiss = onDismiss,
    ) {
        if (release != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("What’s new", style = MaterialTheme.typography.labelLarge, color = c.ink)
                Text(
                    release.notes.ifBlank { "Bug fixes and improvements from GitHub release ${release.tagName}." },
                    fontSize = 13.sp,
                    color = c.inkSecondary,
                    lineHeight = 19.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        when (state) {
            is UpdateState.Available -> {
                Button(
                    onClick = {
                        AppUpdater.downloadUpdate(ctx, state.release) { file ->
                            if (AppUpdater.canInstallPackages(ctx)) AppUpdater.installApk(ctx, file)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download & update", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        vm.updateSettings { it.copy(dismissedUpdateTag = state.release.tagName) }
                        AppUpdater.dismissBanner()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, c.buttonBorder),
                ) { Text("Remind me later", color = c.ink) }
            }

            is UpdateState.Downloading -> {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading update…", color = c.ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (state.totalBytes > 0L) "${AppUpdater.formatBytes(state.downloadedBytes)} / ${AppUpdater.formatBytes(state.totalBytes)}"
                            else AppUpdater.formatBytes(state.downloadedBytes),
                            fontSize = 12.sp,
                            color = c.inkSecondary,
                        )
                    }
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = c.voiceTertiary,
                            trackColor = c.surfaceWell,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    }
                    OutlinedButton(
                        onClick = { AppUpdater.cancelDownload() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        shape = CircleShape,
                    ) { Text("Cancel download") }
                }
            }

            is UpdateState.ReadyToInstall -> {
                if (!canInstall) {
                    InfoCard(
                        Icons.Rounded.InstallMobile,
                        "Allow installing updates",
                        "Android requires enabling “Install unknown apps” for Voxban once so it can update itself. After toggling it on, press Back to install automatically.",
                        c.accentText,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = {
                        if (AppUpdater.canInstallPackages(ctx)) {
                            AppUpdater.installApk(ctx, state.apkFile)
                        } else {
                            pendingAutoInstall = true
                            AppUpdater.openUnknownSourcesSettings(ctx)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.limeTile),
                ) {
                    Icon(Icons.Rounded.InstallMobile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (canInstall) "Install v${state.release.version}" else "Allow & install", fontWeight = FontWeight.SemiBold)
                }
            }

            is UpdateState.Error -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.dangerSoft).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp))
                    Text(state.message, color = c.danger, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (state.release != null) AppUpdater.downloadUpdate(ctx, state.release)
                        else AppUpdater.checkForUpdate(ctx, manual = true)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CircleShape,
                ) { Text("Try again") }
            }

            else -> {
                Text("You’re running Voxban v${AppUpdater.currentVersion}.", color = c.inkSecondary, fontSize = 14.sp)
            }
        }
    }
}

/** Settings → App updates panel: auto-check toggle, manual check, inline download/install. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSettingsPanel(vm: MainViewModel, onOpenUpdateSheet: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val uState by AppUpdater.state.collectAsState()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GitHub Releases", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Current version: v${AppUpdater.currentVersion}", fontSize = 12.sp, color = c.inkSecondary)
            }
            if (uState is UpdateState.Checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check automatically", fontSize = 14.sp, color = c.ink, fontWeight = FontWeight.Medium)
                Text("Checks github.com/${AppUpdater.REPO_OWNER}/${AppUpdater.REPO_NAME} when you open Voxban", fontSize = 12.sp, color = c.inkSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Switch(app.settings.autoCheckUpdates, { on -> vm.updateSettings { it.copy(autoCheckUpdates = on) } })
        }

        when (val st = uState) {
            is UpdateState.UpToDate -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.lowSoft).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.low, modifier = Modifier.size(16.dp))
                    Text("You’re on the latest release (v${AppUpdater.currentVersion}).", color = c.low, fontSize = 12.sp)
                }
            }
            is UpdateState.Available -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.limeTile)
                        .clickable(onClick = onOpenUpdateSheet).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = c.onLime, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("v${st.release.version} is available", color = c.onLime, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Tap to download & install inside the app", color = c.onLime.copy(alpha = 0.72f), fontSize = 11.sp)
                    }
                }
            }
            is UpdateState.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Downloading v${st.release.version}…", fontSize = 12.sp, color = c.ink)
                    if (st.progress >= 0f) {
                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                    }
                }
            }
            is UpdateState.ReadyToInstall -> {
                Button(
                    onClick = onOpenUpdateSheet,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.limeTile),
                ) {
                    Icon(Icons.Rounded.InstallMobile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Install v${st.release.version}", fontWeight = FontWeight.SemiBold)
                }
            }
            is UpdateState.Error -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.dangerSoft).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(16.dp))
                    Text(st.message, color = c.danger, fontSize = 12.sp)
                }
            }
            else -> Unit
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { AppUpdater.checkForUpdate(ctx, manual = true) },
                enabled = uState !is UpdateState.Checking && uState !is UpdateState.Downloading,
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, c.buttonBorder),
            ) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (uState is UpdateState.Checking) "Checking…" else "Check for updates", color = c.ink)
            }
            if (uState is UpdateState.Available || uState is UpdateState.Downloading || uState is UpdateState.ReadyToInstall) {
                Button(
                    onClick = onOpenUpdateSheet,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                ) { Text("View update") }
            }
        }
    }
}
