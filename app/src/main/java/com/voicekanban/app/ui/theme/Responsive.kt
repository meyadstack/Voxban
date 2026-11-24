package com.voicekanban.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-size metrics derived from [LocalConfiguration] so every screen adapts cleanly
 * across compact phones (< 360 dp), standard phones, landscape / split-screen viewports,
 * foldables, and tablets.
 */
@Immutable
data class ResponsiveMetrics(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
) {
    /** Narrow phones or split-screen panes (< 360 dp wide). */
    val isCompactWidth: Boolean get() = screenWidthDp < 360
    /** Large foldables, tablets, or landscape phones (>= 600 dp wide). */
    val isWideWidth: Boolean get() = screenWidthDp >= 600
    /** Short viewports such as landscape phones or split-screen (< 500 dp tall). */
    val isCompactHeight: Boolean get() = screenHeightDp < 500
    /** Use left navigation rail instead of bottom tab bar when wide enough. */
    val useRailNav: Boolean get() = screenWidthDp >= 600

    val gutter: Dp
        get() = when {
            isCompactWidth -> 12.dp
            isWideWidth -> 22.dp
            else -> 16.dp
        }

    val headerGutter: Dp
        get() = when {
            isCompactWidth -> 12.dp
            isWideWidth -> 22.dp
            else -> 18.dp
        }

    val cardPadding: Dp get() = if (isCompactWidth) 12.dp else 16.dp
    val contentMaxWidth: Dp get() = 840.dp
    val formMaxWidth: Dp get() = 560.dp

    val gridColumns: Int
        get() = when {
            screenWidthDp >= 840 -> 3
            screenWidthDp >= 560 -> 2
            else -> 2
        }
}

val LocalResponsive = staticCompositionLocalOf { ResponsiveMetrics(390, 844) }

@Composable
fun rememberResponsiveMetrics(): ResponsiveMetrics {
    val cfg = LocalConfiguration.current
    return ResponsiveMetrics(
        screenWidthDp = cfg.screenWidthDp.coerceAtLeast(240),
        screenHeightDp = cfg.screenHeightDp.coerceAtLeast(320),
    )
}

/** Centers content horizontally with a comfortable max width on wide/tablet viewports. */
@Composable
fun ResponsiveContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = VK.responsive.contentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
            content()
        }
    }
}
