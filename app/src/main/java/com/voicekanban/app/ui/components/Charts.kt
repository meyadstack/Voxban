package com.voicekanban.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.ui.theme.VK

/** Grows from 0 to 1 when first shown, for chart entrance animations. */
@Composable
private fun appear(key: Any?): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Donut of [values] with matching [colors]; [center] is drawn in the hole. */
@Composable
fun DonutChart(values: List<Double>, colors: List<Color>, size: Dp, thickness: Dp = 14.dp, center: @Composable () -> Unit = {}) {
    val c = VK.colors
    val t = appear(values)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(c.surfaceWell, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val total = values.sum()
            if (total <= 0) return@Canvas
            var start = -90f
            values.forEachIndexed { i, v ->
                val sweep = (v / total * 360f * t).toFloat()
                if (sweep > 0.5f) drawArc(colors[i % colors.size], start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** Progress ring from 0..1. */
@Composable
fun ProgressRing(progress: Float, size: Dp, color: Color, thickness: Dp = 10.dp, track: Color = VK.colors.surfaceWell, center: @Composable () -> Unit = {}) {
    // Glide from the current value to the new one (starting from empty the first time).
    // Live timers update this several times a second, so it must never restart from zero.
    val target = progress.coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(if (anim.value == 0f) 800 else 400, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val s = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(stroke))
            val sweep = 360f * anim.value
            if (sweep > 0.2f) drawArc(color, -90f, sweep, false, Offset(inset, inset), s, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        center()
    }
}

/** Vertical bars with labels underneath; the last bar (today) is highlighted. */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, color: Color, highlight: Color, modifier: Modifier = Modifier, height: Dp = 110.dp, valueLabel: ((Double) -> String)? = null) {
    val c = VK.colors
    val t = appear(values)
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.56f
            values.forEachIndexed { i, v ->
                val h = (v / max * (size.height - 4f) * t).toFloat()
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(c.surfaceWell, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                if (h > 0) drawRoundRect(if (i == values.lastIndex) highlight else color, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l, fontSize = 10.sp, color = if (i == labels.lastIndex) c.ink else c.inkTertiary, fontWeight = if (i == labels.lastIndex) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    valueLabel?.let { Text(it(values.getOrElse(i) { 0.0 }), fontSize = 9.sp, color = c.inkTertiary) }
                }
            }
        }
    }
}

/** Small line chart for live values (e.g. speed). */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val pt = Offset(i * step, size.height - v / max * size.height)
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        drawPath(p, color, style = Stroke(2.5f * density, cap = StrokeCap.Round))
    }
}

/** Legend row: colour dot + label + value. */
@Composable
fun LegendRow(color: Color, label: String, value: String) {
    val c = VK.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(color, 9.dp)
        Text(label, fontSize = 12.sp, color = c.inkSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, fontSize = 12.sp, color = c.ink, fontWeight = FontWeight.Medium)
    }
}
