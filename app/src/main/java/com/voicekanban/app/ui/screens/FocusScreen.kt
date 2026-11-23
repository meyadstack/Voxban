package com.voicekanban.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicekanban.app.data.ActiveFocus
import com.voicekanban.app.focus.Focus
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.AiAvatar
import com.voicekanban.app.ui.components.AvatarMood
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.ProgressRing
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Set up a focus session: length, app pinning and Do Not Disturb. */
@Composable
fun FocusScreen(vm: MainViewModel) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    var minutes by remember { mutableIntStateOf(25) }
    var pin by remember { mutableStateOf(true) }
    var dnd by remember { mutableStateOf(true) }
    var dndAllowed by remember { mutableStateOf(Focus.canUseDnd(ctx)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) dndAllowed = Focus.canUseDnd(ctx) }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val today = LocalDate.now()
    val todayMin = app.focusSessions.filter { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }.sumOf { it.actualSec } / 60

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "Focus",
            subtitle = "$todayMin min focused today · ${app.focusSessions.count { it.completed }} sessions completed",
            leading = { Spacer(Modifier.size(44.dp)) },
            trailing = { Spacer(Modifier.size(44.dp)) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(c.limeTile, c.voiceSecondary.copy(alpha = 0.6f)))).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AiAvatar(AvatarMood.IDLE, Modifier.size(120.dp), halo = false)
                Text("$minutes min", fontSize = 46.sp, color = Color(0xFF16201A), letterSpacing = (-1).sp)
                Text("Phone locked to Voxban until the timer ends", fontSize = 13.sp, color = Color(0xFF16201A).copy(alpha = 0.7f), textAlign = TextAlign.Center)
                Slider(
                    minutes.toFloat(), { minutes = (it / 5).toInt() * 5 }, valueRange = 5f..180f, modifier = Modifier.padding(top = 10.dp),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF16201A), activeTrackColor = Color(0xFF16201A), inactiveTrackColor = Color.White.copy(alpha = 0.6f)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 25, 45, 60, 90).forEach { m -> Chip("$m", Modifier.weight(1f), selected = minutes == m) { minutes = m } }
                }
            }

            Option(Icons.Rounded.PushPin, "Lock the phone to Voxban", "Pins the app (Android app pinning): home, recents and other apps are blocked until the session ends.", pin) { pin = it }
            Option(Icons.Rounded.DoNotDisturbOn, "Do Not Disturb", if (dndAllowed) "Silences calls and notifications (except your priority contacts) while you focus." else "Needs permission — tap to allow Voxban to turn on Do Not Disturb.", dnd && dndAllowed) { on ->
                if (on && !dndAllowed) runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                dnd = on
            }

            Button(
                onClick = { vm.startFocus(minutes, pin, dnd && dndAllowed) },
                Modifier.fillMaxWidth().height(58.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.limeTile),
            ) { Icon(Icons.Rounded.SelfImprovement, null); Spacer(Modifier.width(8.dp)); Text("Start focusing", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                "When pinning starts Android shows “App pinned”. The only way out early is to hold “Give up” for 3 seconds (or Android’s own unpin gesture, which this app can’t disable).",
                fontSize = 11.sp, color = c.inkTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )

            if (app.focusSessions.isNotEmpty()) {
                Text("Recent sessions", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                app.focusSessions.take(10).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (f.completed) "✅" else "⏹", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${f.actualSec / 60} of ${f.plannedMin} min", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(relativeTime(f.startedAt) + if (f.completed) " · completed" else " · ended early", color = c.inkSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = VK.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accentText, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(body, color = c.inkSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

/** Full-screen lock shown while a focus session runs. Back is blocked; giving up needs a 3-second hold. */
@Composable
fun FocusOverlay(vm: MainViewModel, f: ActiveFocus) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(f) { while (true) { now = System.currentTimeMillis(); if (now >= f.until) { vm.endFocus(completed = true); break }; delay(500) } }
    BackHandler { /* stay focused */ }
    val left = (f.until - now).coerceAtLeast(0)
    val totalMs = (f.until - f.startedAt).coerceAtLeast(1)
    val hold = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val quotes = remember { listOf("Deep work is a superpower.", "One thing at a time.", "Your future self says thanks.", "Breathe. Focus. Repeat.", "Small steps, every day.") }
    val quote = quotes[((f.startedAt / 1000) % quotes.size).toInt()]
    val ink = Color(0xFFEAFF87)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1410), Color(0xFF1B2A20)))).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("FOCUS MODE", color = ink.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(24.dp))
            ProgressRing(1f - left.toFloat() / totalMs, 280.dp, ink, 10.dp, track = Color.White.copy(alpha = 0.08f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AiAvatar(AvatarMood.THINKING, Modifier.size(86.dp), halo = false)
                    val s = left / 1000
                    Text(if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60), color = Color.White, fontSize = 52.sp, letterSpacing = (-1).sp)
                    Text("of ${f.plannedMin} min", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(quote, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(
                listOfNotNull(if (f.pinned) "Phone locked to Voxban" else null, if (f.dnd) "Do Not Disturb on" else null).joinToString(" · ").ifBlank { "Stay with it" },
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        // Hold to give up.
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).height(52.dp).width(240.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val job = scope.launch { hold.animateTo(1f, tween(3000)); vm.endFocus(completed = false) }
                        waitForUpOrCancellation()
                        job.cancel()
                        scope.launch { hold.animateTo(0f, tween(250)) }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(hold.value).background(Color(0xFFF28B82).copy(alpha = 0.45f)))
            Text("Hold 3 s to give up", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
