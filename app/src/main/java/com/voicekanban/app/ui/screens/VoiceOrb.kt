package com.voicekanban.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * The voice assistant's orb: a luminous gradient sphere with an animated
 * multi-stop radial + linear gradient aura, halftone texture, and three
 * tilted gradient orbits with glowing satellites.
 * Listening widens the orbits with the mic level, thinking spins them,
 * speaking pulses the core with the voice's real amplitude.
 */
@Composable
fun VoiceOrb(
    state: OrbState,
    level: Float,
    ink: Color,
    accent: Color,
    glow: Color,
    secondaryGlow: Color = Color(0xFF7BE495),
    tertiaryGlow: Color = Color(0xFF23533A),
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == OrbState.THINKING) 2200 else 12000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 0.58f, stiffness = 280f), label = "lvl")
    val expand by animateFloatAsState(
        when (state) { OrbState.IDLE -> 0.1f; OrbState.LISTENING -> 0.62f; OrbState.THINKING -> 0.38f; OrbState.SPEAKING -> 0.52f },
        spring(dampingRatio = 0.72f, stiffness = 220f), label = "expand",
    )

    Canvas(modifier) {
        val c = center
        val base = size.minDimension * 0.24f
        val pulse = when (state) {
            OrbState.SPEAKING, OrbState.LISTENING -> 1f + 0.18f * lvl
            else -> 1f + 0.04f * breathe
        }
        val coreR = base * pulse
        val rad = Math.toRadians(spin.toDouble())
        val shift = Offset((cos(rad) * coreR * 0.35f).toFloat(), (sin(rad) * coreR * 0.35f).toFloat())

        // Outer ambient multi-stop radial gradient aura
        val outerGlowR = coreR * (2.25f + 0.35f * expand + 0.25f * lvl)
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    glow.copy(alpha = 0.42f + 0.18f * lvl),
                    secondaryGlow.copy(alpha = 0.24f + 0.12f * expand),
                    tertiaryGlow.copy(alpha = 0.08f),
                    Color.Transparent,
                ),
                center = c + shift * 0.4f,
                radius = outerGlowR,
            ),
            radius = outerGlowR,
            center = c,
        )

        // Inner rich gradient halo
        val innerGlowR = coreR * (1.42f + 0.2f * expand + 0.15f * lvl)
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    glow.copy(alpha = 0.65f),
                    secondaryGlow.copy(alpha = 0.35f),
                    Color.Transparent,
                ),
                center = c - shift * 0.3f,
                radius = innerGlowR,
            ),
            radius = innerGlowR,
            center = c,
        )

        // Orbits: three tilted ellipses with gradient strokes and glowing satellites
        val orbitScale = 1f + expand * 0.24f + (if (state == OrbState.LISTENING) lvl * 0.18f else 0f)
        val orbitBrush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                glow.copy(alpha = 0.75f),
                secondaryGlow.copy(alpha = 0.55f),
                tertiaryGlow.copy(alpha = 0.35f),
                ink.copy(alpha = 0.2f),
            ),
            start = Offset(c.x - base * 2f, c.y - base * 2f),
            end = Offset(c.x + base * 2f, c.y + base * 2f),
        )
        listOf(Triple(1.52f, 18f, 1f), Triple(1.88f, -32f, -0.7f), Triple(2.22f, 64f, 0.45f)).forEachIndexed { i, (r, tilt, dir) ->
            val rx = base * r * orbitScale
            val ry = rx * 0.36f
            withTransform({ rotate(tilt + spin * 0.09f * dir, c) }) {
                drawOval(
                    brush = orbitBrush,
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                    style = Stroke(width = (1.4f + 0.4f * expand) * density),
                )
                val a = Math.toRadians((spin * dir + i * 120).toDouble())
                val sat = Offset(c.x + (rx * cos(a)).toFloat(), c.y + (ry * sin(a)).toFloat())
                val behind = sin(a) < 0 // satellite passes behind the sphere
                if (!(behind && hypot(sat.x - c.x, sat.y - c.y) < coreR)) {
                    val satR = (3.8f + 2.4f * expand) * density
                    val satColor = when (i) { 0 -> glow; 1 -> secondaryGlow; else -> tertiaryGlow }
                    drawCircle(satColor.copy(alpha = 0.35f), radius = satR * 2.1f, center = sat)
                    drawCircle(satColor, radius = satR, center = sat)
                }
            }
        }

        // Gradient core base sphere (smooth multi-stop sphere before halftone overlay)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(glow, secondaryGlow, tertiaryGlow),
                start = Offset(c.x - coreR * 0.85f, c.y - coreR * 0.85f),
                end = Offset(c.x + coreR * 0.9f, c.y + coreR * 0.9f),
            ),
            radius = coreR,
            center = c,
        )

        // Halftone core overlay: dot grid, dots shrink toward the rim and away from the light.
        halftoneSphere(c, coreR, ink.copy(alpha = 0.78f), glow, spin)
    }
}

private fun DrawScope.halftoneSphere(c: Offset, r: Float, color: Color, highlightColor: Color, spin: Float) {
    val step = r / 9.5f
    val light = Offset(c.x - r * 0.38f, c.y - r * 0.42f)
    val drift = (spin / 360f) * step // slow drift makes the sphere feel alive
    var y = c.y - r
    var row = 0
    while (y <= c.y + r) {
        val offsetX = if (row % 2 == 0) 0f else step / 2
        var x = c.x - r + offsetX + drift % step
        while (x <= c.x + r) {
            val d = hypot(x - c.x, y - c.y) / r
            if (d <= 1f) {
                val shade = 1f - (hypot(x - light.x, y - light.y) / (r * 1.9f)).coerceIn(0f, 1f)
                val rim = (1f - d * d).coerceIn(0f, 1f)
                val dot = step * 0.46f * (0.35f + 0.65f * rim) * (0.45f + 0.55f * shade)
                val dotColor = if (shade > 0.68f) highlightColor.copy(alpha = 0.9f) else color
                drawCircle(dotColor, radius = dot, center = Offset(x, y))
            }
            x += step
        }
        y += step * 0.87f
        row++
    }
}

/** Small decorative halftone disc for empty states and onboarding. */
@Composable
fun HalftoneDisc(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2.2f
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFFEAFF87), Color(0xFF8BE28B), Color(0xFF23533A)),
            ),
            radius = r,
            center = center,
        )
        halftoneSphere(center, r, color.copy(alpha = 0.75f), Color(0xFFEAFF87), 0f)
    }
}

