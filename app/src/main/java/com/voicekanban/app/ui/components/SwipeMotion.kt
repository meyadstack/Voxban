package com.voicekanban.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Visual and behavioral spec for one side of a [SwipeActionCard]. */
data class SwipeActionSpec(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onTrigger: () -> Unit,
)

/**
 * Pure GPU-layer page transition for [androidx.compose.foundation.pager.HorizontalPager].
 * Reads pager offset inside `graphicsLayer` (draw phase only) for locked 120fps swipe physics.
 */
fun Modifier.pagerSwipeTransition(
    pagerState: PagerState,
    page: Int,
    tilt: Boolean = false,
): Modifier = graphicsLayer {
    val offset = pagerState.getOffsetDistanceInPages(page)
    val absOffset = abs(offset).coerceIn(0f, 1f)
    val scale = 1f - absOffset * (if (tilt) 0.055f else 0.04f)
    scaleX = scale
    scaleY = scale
    alpha = 1f - absOffset * (if (tilt) 0.28f else 0.18f)
    translationY = absOffset * (if (tilt) 10.dp else 6.dp).toPx()
    if (tilt) {
        cameraDistance = 14f * density
        rotationY = (-offset * 4.2f).coerceIn(-6.5f, 6.5f)
    }
}

/**
 * Computes elastic rubber-band displacement beyond [maxTravel] so dragging past the limit
 * feels physical rather than hitting a hard wall.
 */
internal fun rubberBandOffset(raw: Float, maxTravel: Float): Float {
    val absRaw = abs(raw)
    if (absRaw <= maxTravel) return raw
    val excess = absRaw - maxTravel
    return sign(raw) * (maxTravel + excess * 0.24f)
}

/**
 * Determines whether a released swipe crosses either distance or fling-velocity thresholds.
 */
internal fun shouldTriggerSwipe(
    offsetPx: Float,
    velocityPxPerSec: Float,
    thresholdPx: Float,
    flingVelocityPx: Float,
): Boolean {
    val absOffset = abs(offsetPx)
    if (absOffset >= thresholdPx) return true
    val sameDirection = offsetPx * velocityPxPerSec > 0f
    return sameDirection && absOffset >= thresholdPx * 0.32f && abs(velocityPxPerSec) >= flingVelocityPx
}

/**
 * 1:1 finger-tracked horizontal swipe action container.
 *
 * - Strictly ignores disabled directions before consuming any pointer event, so parent
 *   `HorizontalPager`s receive swipes seamlessly without gesture conflicts.
 * - Tracks finger 1:1 with elastic rubber-band past max travel and subtle card tilt.
 * - Fires a haptic tick at the exact trigger threshold and springs back with release velocity.
 */
@Composable
fun SwipeActionCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    rightAction: SwipeActionSpec? = null,
    leftAction: SwipeActionSpec? = null,
    threshold: Dp = 82.dp,
    maxTravel: Dp = 136.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var crossedThreshold by remember { mutableStateOf(false) }

    val currentRight by rememberUpdatedState(rightAction)
    val currentLeft by rememberUpdatedState(leftAction)

    val thresholdPx = with(density) { threshold.toPx() }
    val maxTravelPx = with(density) { maxTravel.toPx() }
    val flingVelocityPx = with(density) { 860.dp.toPx() }

    val curOffset = offsetX.value
    val activeSpec = when {
        curOffset > 1f -> rightAction
        curOffset < -1f -> leftAction
        else -> null
    }
    val progress = (abs(curOffset) / thresholdPx).coerceIn(0f, 1.2f)
    val pastThreshold = abs(curOffset) >= thresholdPx
    val iconScale by animateFloatAsState(
        targetValue = if (pastThreshold) 1.18f else 0.78f + (progress.coerceAtMost(1f) * 0.24f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 560f),
        label = "swipeIconScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .pointerInput(rightAction != null, leftAction != null) {
                if (currentRight == null && currentLeft == null) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var accX = 0f
                    var accY = 0f
                    var dragging = false
                    var rawOffset = offsetX.value
                    var hapticFired = abs(rawOffset) >= thresholdPx
                    val velocityTracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                val velX = velocityTracker.calculateVelocity().x
                                val settled = offsetX.value
                                val triggeredRight = currentRight != null && settled > 0f &&
                                    shouldTriggerSwipe(settled, velX, thresholdPx, flingVelocityPx)
                                val triggeredLeft = currentLeft != null && settled < 0f &&
                                    shouldTriggerSwipe(settled, velX, thresholdPx, flingVelocityPx)

                                scope.launch {
                                    when {
                                        triggeredRight -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            offsetX.animateTo(
                                                targetValue = thresholdPx + with(density) { 26.dp.toPx() },
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 920f),
                                            )
                                            currentRight?.onTrigger?.invoke()
                                            offsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                                            )
                                        }
                                        triggeredLeft -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            offsetX.animateTo(
                                                targetValue = -(thresholdPx + with(density) { 26.dp.toPx() }),
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 920f),
                                            )
                                            currentLeft?.onTrigger?.invoke()
                                            offsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                                            )
                                        }
                                        else -> {
                                            offsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(dampingRatio = 0.74f, stiffness = 540f),
                                                initialVelocity = velX,
                                            )
                                        }
                                    }
                                    crossedThreshold = false
                                }
                            }
                            break
                        }

                        if (change.isConsumed && !dragging) break
                        val delta = change.positionChange()

                        if (!dragging) {
                            accX += delta.x
                            accY += delta.y
                            if (abs(accY) > touchSlop && abs(accY) >= abs(accX)) {
                                // Vertical scroll wins cleanly - do not consume.
                                break
                            }
                            if (abs(accX) > touchSlop && abs(accX) > abs(accY)) {
                                val allowDir = (accX > 0f && currentRight != null) || (accX < 0f && currentLeft != null)
                                if (!allowDir) {
                                    // Disabled swipe direction - hand straight through to parent HorizontalPager!
                                    break
                                }
                                dragging = true
                                rawOffset = accX - sign(accX) * touchSlop
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val mapped = mapSwipeOffset(rawOffset, maxTravelPx, currentRight != null, currentLeft != null)
                                scope.launch { offsetX.snapTo(mapped) }
                            }
                        } else {
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            rawOffset += delta.x
                            val mapped = mapSwipeOffset(rawOffset, maxTravelPx, currentRight != null, currentLeft != null)
                            val nowPast = abs(mapped) >= thresholdPx
                            if (nowPast && !hapticFired) {
                                hapticFired = true
                                crossedThreshold = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (!nowPast && abs(mapped) < thresholdPx * 0.84f) {
                                hapticFired = false
                                crossedThreshold = false
                            }
                            scope.launch { offsetX.snapTo(mapped) }
                        }
                    }
                }
            },
    ) {
        // Revealed action backdrop - drawn only while displacing (zero overdraw at rest)
        if (activeSpec != null && abs(curOffset) > 1f) {
            val isRight = curOffset > 0f
            val bgAlpha = if (pastThreshold) 1f else (0.28f + 0.62f * progress.coerceAtMost(1f))
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(activeSpec.containerColor.copy(alpha = bgAlpha))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isRight) Arrangement.Start else Arrangement.End,
            ) {
                if (isRight) {
                    Icon(
                        activeSpec.icon,
                        contentDescription = activeSpec.label,
                        tint = activeSpec.contentColor,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                                alpha = (progress * 1.4f).coerceIn(0.25f, 1f)
                            },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activeSpec.label,
                        color = activeSpec.contentColor,
                        fontSize = 13.sp,
                        fontWeight = if (pastThreshold) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.graphicsLayer { alpha = (progress * 1.3f).coerceIn(0.2f, 1f) },
                    )
                } else {
                    Text(
                        activeSpec.label,
                        color = activeSpec.contentColor,
                        fontSize = 13.sp,
                        fontWeight = if (pastThreshold) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.graphicsLayer { alpha = (progress * 1.3f).coerceIn(0.2f, 1f) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        activeSpec.icon,
                        contentDescription = activeSpec.label,
                        tint = activeSpec.contentColor,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                                alpha = (progress * 1.4f).coerceIn(0.25f, 1f)
                            },
                    )
                }
            }
        }

        // Foreground card with pure GPU translation + micro-tilt
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.value
                    rotationZ = (offsetX.value / thresholdPx).coerceIn(-1.25f, 1.25f) * 1.05f
                },
            content = content,
        )
    }
}

private fun mapSwipeOffset(raw: Float, maxTravelPx: Float, allowRight: Boolean, allowLeft: Boolean): Float =
    when {
        raw > 0f && !allowRight -> (raw * 0.14f).coerceAtMost(maxTravelPx * 0.14f)
        raw < 0f && !allowLeft -> (raw * 0.14f).coerceAtLeast(-maxTravelPx * 0.14f)
        else -> rubberBandOffset(raw, maxTravelPx)
    }

/**
 * Lightweight 1:1 horizontal swipe wrapper for stepping through months or tracks
 * (e.g. Calendar month grid, Money monthly summary card) without blocking vertical scroll.
 */
@Composable
fun SwipeStepContainer(
    modifier: Modifier = Modifier,
    canSwipeLeft: Boolean = true,
    canSwipeRight: Boolean = true,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }

    val allowLeft by rememberUpdatedState(canSwipeLeft)
    val allowRight by rememberUpdatedState(canSwipeRight)
    val leftCb by rememberUpdatedState(onSwipeLeft)
    val rightCb by rememberUpdatedState(onSwipeRight)

    val triggerPx = with(density) { 64.dp.toPx() }
    val maxNudgePx = with(density) { 44.dp.toPx() }
    val flingVelPx = with(density) { 700.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var accX = 0f
                    var accY = 0f
                    var dragging = false
                    var rawX = 0f
                    val velocityTracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                val velX = velocityTracker.calculateVelocity().x
                                val goNext = allowLeft && rawX < 0f && shouldTriggerSwipe(rawX, velX, triggerPx, flingVelPx)
                                val goPrev = allowRight && rawX > 0f && shouldTriggerSwipe(rawX, velX, triggerPx, flingVelPx)
                                if (goNext) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    leftCb()
                                } else if (goPrev) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    rightCb()
                                }
                                scope.launch {
                                    dragX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 540f))
                                }
                            }
                            break
                        }
                        if (change.isConsumed && !dragging) break
                        val delta = change.positionChange()
                        if (!dragging) {
                            accX += delta.x
                            accY += delta.y
                            if (abs(accY) > touchSlop && abs(accY) >= abs(accX)) break
                            if (abs(accX) > touchSlop && abs(accX) > abs(accY)) {
                                val enabled = (accX < 0f && allowLeft) || (accX > 0f && allowRight)
                                if (!enabled) break
                                dragging = true
                                rawX = accX - sign(accX) * touchSlop
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch { dragX.snapTo(rubberBandOffset(rawX * 0.45f, maxNudgePx)) }
                            }
                        } else {
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            rawX += delta.x
                            val visual = mapSwipeOffset(rawX * 0.45f, maxNudgePx, allowRight, allowLeft)
                            scope.launch { dragX.snapTo(visual) }
                        }
                    }
                }
            }
            .graphicsLayer { translationX = dragX.value },
        content = content,
    )
}

/**
 * 1:1 finger-tracked swipe-to-dismiss wrapper for floating toasts (swipe down or sideways).
 */
@Composable
fun SwipeToDismissToast(
    key: Any?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember(key) { Animatable(0f) }
    val offsetY = remember(key) { Animatable(0f) }
    val dismissCb by rememberUpdatedState(onDismiss)

    val dismissX = with(density) { 88.dp.toPx() }
    val dismissY = with(density) { 44.dp.toPx() }
    val flingVel = with(density) { 720.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(key) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var accX = 0f
                    var accY = 0f
                    var axis = 0 // 1 = horizontal, 2 = vertical down
                    val tracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (axis != 0) {
                                val vel = tracker.calculateVelocity()
                                val dismissHoriz = axis == 1 && shouldTriggerSwipe(offsetX.value, vel.x, dismissX, flingVel)
                                val dismissDown = axis == 2 && offsetY.value > 0f && shouldTriggerSwipe(offsetY.value, vel.y, dismissY, flingVel)
                                scope.launch {
                                    when {
                                        dismissHoriz -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            offsetX.animateTo(sign(offsetX.value) * dismissX * 2.4f, spring(Spring.DampingRatioNoBouncy, 680f))
                                            dismissCb()
                                        }
                                        dismissDown -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            offsetY.animateTo(dismissY * 2.4f, spring(Spring.DampingRatioNoBouncy, 680f))
                                            dismissCb()
                                        }
                                        else -> {
                                            launch { offsetX.animateTo(0f, spring(0.72f, 540f), vel.x) }
                                            launch { offsetY.animateTo(0f, spring(0.72f, 540f), vel.y) }
                                        }
                                    }
                                }
                            }
                            break
                        }

                        val d = change.positionChange()
                        if (axis == 0) {
                            accX += d.x
                            accY += d.y
                            if (abs(accX) > touchSlop && abs(accX) > abs(accY)) {
                                axis = 1
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                            } else if (accY > touchSlop && accY > abs(accX)) {
                                axis = 2
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                            } else if (accY < -touchSlop) {
                                break
                            }
                        } else {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (axis == 1) {
                                val next = offsetX.value + d.x
                                scope.launch { offsetX.snapTo(next) }
                            } else {
                                val next = (offsetY.value + d.y).coerceAtLeast(-10f)
                                scope.launch { offsetY.snapTo(next) }
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                translationX = offsetX.value
                translationY = offsetY.value
                val fracX = (abs(offsetX.value) / (dismissX * 1.4f)).coerceIn(0f, 1f)
                val fracY = (offsetY.value.coerceAtLeast(0f) / (dismissY * 1.4f)).coerceIn(0f, 1f)
                alpha = 1f - maxOf(fracX, fracY) * 0.85f
            },
        content = content,
    )
}
