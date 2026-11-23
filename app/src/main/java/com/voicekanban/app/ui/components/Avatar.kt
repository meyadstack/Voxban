package com.voicekanban.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** What the assistant is doing; drives the avatar's face and body. */
enum class AvatarMood { IDLE, LISTENING, THINKING, SPEAKING }

private val FACE = Color(0xFF16201A)
private val BODY_TOP = Color(0xFFEAFF87)
private val BODY_MID = Color(0xFF9BE58F)
private val BODY_LOW = Color(0xFF2E7A55)
private val CHEEK = Color(0xFFFF8FA3)

/**
 * "Kiwi", the Voxban assistant: a soft, breathing blob with a face.
 * It blinks, its eyes follow your finger (or wander), it listens with
 * ripples that grow with your voice, ponders with orbiting dots, and its
 * mouth moves with the real audio level while speaking. Tap it and it
 * giggles. The same character is used in voice mode, chat and the logo.
 */
@Composable
fun AiAvatar(
    mood: AvatarMood,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    interactive: Boolean = false,
    /** Draw ripples / orbiting dots around the body (off for tiny chat avatars). */
    halo: Boolean = true,
    onTap: (() -> Unit)? = null,
) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { val start = System.nanoTime(); while (true) withFrameNanos { time = (it - start) / 1_000_000_000f } }

    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.55f, stiffness = 320f), label = "lvl")
    val listen by animateFloatAsState(if (mood == AvatarMood.LISTENING) 1f else 0f, tween(380), label = "listen")
    val think by animateFloatAsState(if (mood == AvatarMood.THINKING) 1f else 0f, tween(380), label = "think")
    val speak by animateFloatAsState(if (mood == AvatarMood.SPEAKING) 1f else 0f, tween(260), label = "speak")
    val joy = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val lookX = remember { Animatable(0f) }
    val lookY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Eyes ease toward the finger / the mood's gaze instead of snapping.
    LaunchedEffect(touch, mood) {
        while (true) {
            val (tx, ty) = when {
                touch != null -> touch!!.x to touch!!.y
                mood == AvatarMood.THINKING -> 0.55f + 0.15f * cos(time * 2.2f) to -0.55f + 0.12f * sin(time * 2.2f)
                mood == AvatarMood.LISTENING -> 0.08f * sin(time * 0.9f) to 0.05f
                mood == AvatarMood.SPEAKING -> 0.18f * sin(time * 0.6f) to 0f
                else -> 0.45f * sin(time * 0.37f) * sin(time * 0.11f + 1f) to 0.25f * cos(time * 0.29f)
            }
            launch { lookX.animateTo(tx.coerceIn(-1f, 1f), spring(dampingRatio = 0.8f, stiffness = 120f)) }
            lookY.animateTo(ty.coerceIn(-1f, 1f), spring(dampingRatio = 0.8f, stiffness = 120f))
            kotlinx.coroutines.delay(if (touch != null) 16 else 420)
        }
    }

    val gestures = if (!interactive && onTap == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = min(size.width, size.height) / 2f
            fun rel(p: Offset) = Offset(((p.x - c.x) / r).coerceIn(-1f, 1f), ((p.y - c.y) / r).coerceIn(-1f, 1f))
            if (interactive) touch = rel(down.position)
            scope.launch { squash.animateTo(0.9f, spring(stiffness = 900f)) }
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val p = ev.changes.firstOrNull() ?: break
                if (!p.pressed) break
                if (hypot(p.position.x - down.position.x, p.position.y - down.position.y) > 18f) moved = true
                if (interactive) touch = rel(p.position)
            }
            touch = null
            scope.launch { squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow)) }
            if (!moved) {
                scope.launch { joy.snapTo(0f); joy.animateTo(1f, tween(160)); kotlinx.coroutines.delay(900); joy.animateTo(0f, tween(420)) }
                onTap?.invoke()
            }
        }
    }

    Canvas(modifier.then(gestures)) {
        drawAvatar(
            time = time, level = lvl, listen = listen, think = think, speak = speak,
            joy = joy.value, squash = squash.value, look = Offset(lookX.value, lookY.value), halo = halo,
        )
    }
}

/** Static avatar used by the logo mark (no animation). */
fun DrawScope.drawAvatarStatic(scale: Float = 1f) =
    drawAvatar(time = 0.6f, level = 0f, listen = 0f, think = 0f, speak = 0f, joy = 0f, squash = 1f, look = Offset(0.1f, 0.05f), halo = false, scale = scale, blinkEnabled = false)

private fun DrawScope.drawAvatar(
    time: Float, level: Float, listen: Float, think: Float, speak: Float, joy: Float, squash: Float,
    look: Offset, halo: Boolean, scale: Float = 1f, blinkEnabled: Boolean = true,
) {
    val c = center
    val base = size.minDimension / 2f * scale
    val r = base * (if (halo) 0.62f else 0.9f)
    val breathe = 1f + 0.025f * sin(time * 1.9f) + 0.1f * level * (listen + speak)
    val bodyR = r * breathe

    // ---------------------------------------------------------------- halo
    if (halo) {
        // Soft glow.
        drawCircle(
            Brush.radialGradient(listOf(BODY_MID.copy(alpha = 0.35f + 0.25f * level), BODY_TOP.copy(alpha = 0.12f), Color.Transparent), c, base),
            radius = base, center = c,
        )
        // Listening ripples that grow with the mic.
        if (listen > 0.01f) repeat(3) { i ->
            val p = ((time * 0.55f + i / 3f) % 1f)
            val rr = bodyR * (1.05f + p * (0.5f + 0.35f * level))
            drawCircle(BODY_MID.copy(alpha = (1f - p) * 0.55f * listen), radius = rr, center = c, style = Stroke(width = (2.2f + 3f * level) * density))
        }
        // Thinking: dots orbiting like thoughts.
        if (think > 0.01f) repeat(3) { i ->
            val a = time * 2.6f + i * (2f * PI.toFloat() / 3f)
            val p = Offset(c.x + cos(a) * bodyR * 1.25f, c.y + sin(a) * bodyR * 0.42f - bodyR * 0.95f)
            drawCircle(FACE.copy(alpha = 0.18f * think), radius = (5f + i * 1.5f) * density * scale, center = p + Offset(0f, 2f * density))
            drawCircle(BODY_TOP.copy(alpha = think), radius = (4.5f + i * 1.5f) * density * scale, center = p)
        }
        // Speaking: sound arcs either side.
        if (speak > 0.01f) for (side in listOf(-1f, 1f)) repeat(2) { i ->
            val rr = bodyR * (1.18f + i * 0.18f + 0.12f * level)
            drawArc(
                BODY_MID.copy(alpha = speak * (0.65f - i * 0.25f) * (0.4f + level)),
                startAngle = if (side > 0) -28f else 152f, sweepAngle = 56f, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(width = 3f * density * scale, cap = StrokeCap.Round),
            )
        }
    }

    withTransform({
        // Squash & stretch on touch, gentle bounce when happy.
        scale(1f / squash.coerceAtLeast(0.5f) * (1f + 0.04f * joy), squash * (1f + 0.04f * joy), c)
        translate(0f, -abs(sin(time * 9f)) * joy * r * 0.08f)
    }) {
        // ---------------------------------------------------------------- body (wobbly blob)
        val wobble = 0.018f + 0.05f * level * (listen + speak) + 0.02f * think
        val path = Path()
        val n = 72
        for (k in 0..n) {
            val th = k / n.toFloat() * 2f * PI.toFloat()
            val w = 1f + wobble * (sin(3f * th + time * 2.1f) + 0.6f * sin(5f * th - time * 1.7f) + 0.4f * sin(2f * th + time * 0.8f))
            val p = Offset(c.x + cos(th) * bodyR * w, c.y + sin(th) * bodyR * w * 0.97f)
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, FACE.copy(alpha = 0.16f), style = Stroke(width = bodyR * 0.08f))
        drawPath(path, Brush.linearGradient(listOf(BODY_TOP, BODY_MID, BODY_LOW), Offset(c.x - bodyR, c.y - bodyR), Offset(c.x + bodyR * 0.8f, c.y + bodyR * 1.2f)))
        // Glossy highlight.
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(c.x - bodyR * 0.38f, c.y - bodyR * 0.45f), bodyR * 0.55f),
            radius = bodyR * 0.55f, center = Offset(c.x - bodyR * 0.38f, c.y - bodyR * 0.45f),
        )

        // ---------------------------------------------------------------- face
        val face = c + Offset(look.x * bodyR * 0.14f, look.y * bodyR * 0.1f)
        val eyeDx = bodyR * 0.3f
        val eyeY = face.y - bodyR * 0.1f
        val blinkPhase = (time + 0.3f) % 3.9f
        val blink = if (!blinkEnabled) 0f else if (blinkPhase < 0.16f) 1f - abs(blinkPhase - 0.08f) / 0.08f else 0f
        val open = (1f - blink).coerceIn(0.08f, 1f) * (1f - 0.35f * think) * (1f + 0.12f * listen)
        val eyeW = bodyR * 0.15f
        val eyeH = bodyR * 0.24f * open

        for (side in listOf(-1f, 1f)) {
            val ec = Offset(face.x + side * eyeDx, eyeY)
            if (joy > 0.5f) {
                // Happy "^" eyes.
                val arc = Path().apply {
                    moveTo(ec.x - eyeW * 0.9f, ec.y + eyeW * 0.35f)
                    quadraticTo(ec.x, ec.y - eyeW * 1.1f, ec.x + eyeW * 0.9f, ec.y + eyeW * 0.35f)
                }
                drawPath(arc, FACE, style = Stroke(width = bodyR * 0.07f, cap = StrokeCap.Round))
            } else {
                drawRoundRect(FACE, Offset(ec.x - eyeW / 2, ec.y - eyeH / 2), Size(eyeW, eyeH), CornerRadius(eyeW / 2, eyeW / 2))
                if (open > 0.4f) {
                    val g = Offset(ec.x - eyeW * 0.12f + look.x * eyeW * 0.18f, ec.y - eyeH * 0.22f + look.y * eyeH * 0.1f)
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = eyeW * 0.2f, center = g)
                }
            }
            // Thinking: one eyebrow raised.
            if (think > 0.05f && side > 0) {
                drawLine(FACE.copy(alpha = think), Offset(ec.x - eyeW * 0.8f, ec.y - eyeH / 2 - bodyR * 0.12f), Offset(ec.x + eyeW * 0.8f, ec.y - eyeH / 2 - bodyR * 0.18f), strokeWidth = bodyR * 0.05f, cap = StrokeCap.Round)
            }
        }

        // Cheeks.
        val cheekA = 0.28f + 0.35f * joy
        for (side in listOf(-1f, 1f)) drawOval(
            CHEEK.copy(alpha = cheekA), Offset(face.x + side * bodyR * 0.48f - bodyR * 0.11f, face.y + bodyR * 0.1f - bodyR * 0.05f), Size(bodyR * 0.22f, bodyR * 0.12f),
        )

        // Mouth.
        val m = Offset(face.x, face.y + bodyR * 0.22f)
        val talking = speak * (0.15f + 0.85f * level) * (0.75f + 0.25f * sin(time * 22f))
        when {
            joy > 0.5f -> {
                val d = Path().apply {
                    moveTo(m.x - bodyR * 0.2f, m.y - bodyR * 0.02f)
                    quadraticTo(m.x, m.y + bodyR * 0.3f, m.x + bodyR * 0.2f, m.y - bodyR * 0.02f)
                    close()
                }
                drawPath(d, FACE)
                drawOval(CHEEK, Offset(m.x - bodyR * 0.08f, m.y + bodyR * 0.07f), Size(bodyR * 0.16f, bodyR * 0.08f))
            }
            talking > 0.04f -> {
                val h = bodyR * (0.05f + 0.22f * talking)
                val w = bodyR * (0.2f - 0.04f * talking)
                drawOval(FACE, Offset(m.x - w / 2, m.y - h / 2 + bodyR * 0.02f), Size(w, h))
                if (h > bodyR * 0.1f) drawOval(CHEEK.copy(alpha = 0.9f), Offset(m.x - w * 0.3f, m.y + h * 0.05f + bodyR * 0.02f), Size(w * 0.6f, h * 0.35f))
            }
            listen > 0.5f -> {
                val rr = bodyR * (0.055f + 0.04f * level)
                drawCircle(FACE, radius = rr, center = m + Offset(0f, bodyR * 0.02f))
            }
            think > 0.5f -> {
                val wav = Path().apply {
                    moveTo(m.x - bodyR * 0.12f, m.y + bodyR * 0.03f)
                    cubicTo(m.x - bodyR * 0.05f, m.y - bodyR * 0.03f, m.x + bodyR * 0.02f, m.y + bodyR * 0.08f, m.x + bodyR * 0.13f, m.y)
                }
                drawPath(wav, FACE, style = Stroke(width = bodyR * 0.05f, cap = StrokeCap.Round))
            }
            else -> {
                val smile = Path().apply {
                    moveTo(m.x - bodyR * 0.16f, m.y - bodyR * 0.02f)
                    quadraticTo(m.x, m.y + bodyR * 0.14f, m.x + bodyR * 0.16f, m.y - bodyR * 0.02f)
                }
                drawPath(smile, FACE, style = Stroke(width = bodyR * 0.055f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Brand mark: the assistant's face on a deep botanical squircle. */
@Composable
fun AvatarLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF1B201C), Color(0xFF2B3B2E)), Offset.Zero, Offset(this.size.width, this.size.height)),
            cornerRadius = CornerRadius(this.size.width * 0.28f),
        )
        drawAvatarStatic(scale = 0.74f)
    }
}
