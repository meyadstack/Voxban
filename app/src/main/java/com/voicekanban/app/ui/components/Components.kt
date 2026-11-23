package com.voicekanban.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.Priority
import com.voicekanban.app.ui.theme.VK

/** Brand mark: the assistant's face (same character as the Voice AI avatar). */
@Composable
fun LogoMark(size: Dp = 36.dp, modifier: Modifier = Modifier) = AvatarLogo(size, modifier)

@Composable
fun PriorityPill(priority: Priority, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = VK.colors
    val icon = when (priority) { Priority.HIGH -> Icons.Rounded.ArrowUpward; Priority.MEDIUM -> Icons.Rounded.Remove; Priority.LOW -> Icons.Rounded.ArrowDownward }
    Row(
        modifier.clip(CircleShape).background(c.priorityBg(priority)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = c.priorityFg(priority), modifier = Modifier.size(11.dp))
        Text(priority.label, color = c.priorityFg(priority), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Deadline chip: red when overdue, lime within 24 h, neutral pill otherwise. */
@Composable
fun DueChip(dueAt: Long, done: Boolean, modifier: Modifier = Modifier) {
    val c = VK.colors
    val now = System.currentTimeMillis()
    val (fg, bg) = when {
        done -> c.inkTertiary to c.surfaceWell
        dueAt < now -> c.high to c.highSoft
        dueAt - now < 24 * 3_600_000L -> c.onLime to c.limeTile
        else -> c.inkSecondary to c.surfaceWell
    }
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.Schedule, null, tint = fg, modifier = Modifier.size(11.dp))
        Text(
            if (!done && dueAt < now) "Overdue · ${DateParser.label(dueAt)}" else DateParser.label(dueAt),
            color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, tint: Color? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = VK.colors
    val bgColor = when {
        selected && tint != null -> tint
        selected -> c.raised
        else -> Color.Transparent
    }
    val fgColor = when {
        selected && tint != null -> Color.White
        selected -> c.ink
        else -> c.inkSecondary
    }
    Row(
        modifier.clip(CircleShape)
            .background(bgColor)
            .border(1.dp, if (selected) (tint ?: c.buttonBorder) else c.buttonBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp).height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) fgColor else (tint ?: c.inkSecondary), modifier = Modifier.size(14.dp))
        Text(text, color = fgColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 18.dp, bottom = 8.dp),
        color = VK.colors.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(VK.colors.border))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

val CardShape = RoundedCornerShape(24.dp)
