package com.voicekanban.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.ui.theme.VK

/** Shared top bar so every tab lines up: 44 dp buttons, centred title, 18 dp gutters. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onTitle: (() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = VK.colors
    Column(Modifier.fillMaxWidth().background(c.background).statusBarsPadding().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Column(
                Modifier.weight(1f).let { if (onTitle != null) it.clip(CircleShape).clickable(onClick = onTitle) else it },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, fontSize = 11.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
        below?.let { Column(Modifier.padding(top = 12.dp), content = it) }
    }
}

@Composable
fun HeaderButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = VK.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (active) c.limeTile else c.raised)
            .border(1.dp, if (active) Color.Transparent else c.buttonBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = (if (active) c.onLime else c.ink).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp)) }
}

@Composable
fun Pill(label: String, active: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = VK.colors
    val bg by animateColorAsState(if (active) c.raised else Color.Transparent, tween(200), label = "pillBg")
    Row(
        modifier.height(38.dp).clip(CircleShape).background(bg)
            .border(1.dp, c.buttonBorder, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = c.ink, maxLines = 1)
    }
}

fun relativeTime(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 7 * 86_400_000L -> "${d / 86_400_000} d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ts))
    }
}
