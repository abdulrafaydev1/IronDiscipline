package com.irondiscipline.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Reusable Jelly/Rubber Elastic Modifier:
 * - Normal tap: executes normal onClick without interference.
 * - Long press + finger move: activates elastic jelly stretch in any direction (X, Y, diagonal),
 *   dynamically morphs rounded corners, blocks accidental click on release, and springs back smoothly.
 */
fun Modifier.jellyElastic(
    enabled: Boolean = true,
    baseCornerRadius: Dp = 20.dp,
    maxTranslationDp: Dp = 28.dp,
    maxStretchScale: Float = 0.38f,
    longPressTimeMs: Long = 160L
): Modifier = composed {
    if (!enabled) return@composed this

    val density = LocalDensity.current
    val maxTransPx = with(density) { maxTranslationDp.toPx() }
    val baseCornerPx = with(density) { baseCornerRadius.toPx() }
    val dragResistancePx = with(density) { 95.dp.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }

    val scaleX = remember { Animatable(1f) }
    val scaleY = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val cornerMorphX = remember { Animatable(0f) }
    val cornerMorphY = remember { Animatable(0f) }
    val pressGlowScale = remember { Animatable(1f) }

    // Dynamic corner radii that morph naturally as the button stretches horizontally/vertically
    val currentTopStart = with(density) {
        (baseCornerPx + (cornerMorphY.value * 0.25f) - (cornerMorphX.value * 0.15f))
            .coerceIn(baseCornerPx * 0.45f, baseCornerPx * 1.85f).toDp()
    }
    val currentTopEnd = with(density) {
        (baseCornerPx + (cornerMorphY.value * 0.25f) + (cornerMorphX.value * 0.15f))
            .coerceIn(baseCornerPx * 0.45f, baseCornerPx * 1.85f).toDp()
    }
    val currentBottomStart = with(density) {
        (baseCornerPx - (cornerMorphY.value * 0.25f) - (cornerMorphX.value * 0.15f))
            .coerceIn(baseCornerPx * 0.45f, baseCornerPx * 1.85f).toDp()
    }
    val currentBottomEnd = with(density) {
        (baseCornerPx - (cornerMorphY.value * 0.25f) + (cornerMorphX.value * 0.15f))
            .coerceIn(baseCornerPx * 0.45f, baseCornerPx * 1.85f).toDp()
    }

    val dynamicShape = RoundedCornerShape(
        topStart = currentTopStart,
        topEnd = currentTopEnd,
        bottomStart = currentBottomStart,
        bottomEnd = currentBottomEnd
    )

    this
        .graphicsLayer {
            this.scaleX = scaleX.value * pressGlowScale.value
            this.scaleY = scaleY.value * pressGlowScale.value
            this.translationX = offsetX.value
            this.translationY = offsetY.value
            // Anchor transform slightly opposite to drag direction so button stretches outward like rubber
            val originX = (0.5f - (offsetX.value / (maxTransPx * 3.2f))).coerceIn(0.15f, 0.85f)
            val originY = (0.5f - (offsetY.value / (maxTransPx * 3.2f))).coerceIn(0.15f, 0.85f)
            this.transformOrigin = TransformOrigin(originX, originY)
            this.shape = dynamicShape
            this.clip = false
        }
        .clip(dynamicShape)
        .pointerInput(enabled) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var isElasticActive = false
                    var pointerIsDown = true
                    var totalDrag = Offset.Zero

                    // Subtle press feedback
                    launch {
                        pressGlowScale.animateTo(
                            1.04f,
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                        )
                    }

                    // Activate elastic mode once held for longPressTimeMs
                    val holdJob = launch {
                        delay(longPressTimeMs)
                        if (pointerIsDown) {
                            isElasticActive = true
                            pressGlowScale.animateTo(
                                1.08f,
                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    }

                    while (pointerIsDown) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()

                        if (change == null || !change.pressed) {
                            pointerIsDown = false
                            holdJob.cancel()
                            if (isElasticActive) {
                                // Consume release so normal onClick does NOT accidentally fire after a stretch drag
                                change?.consume()
                            }
                            break
                        }

                        val delta = change.position - startPos
                        totalDrag = delta

                        // If user already held past longPressTimeMs OR dragged firmly while holding
                        if (!isElasticActive && (abs(delta.x) > touchSlopPx * 1.5f || abs(delta.y) > touchSlopPx * 1.5f)) {
                            if (change.uptimeMillis - down.uptimeMillis >= 90L) {
                                isElasticActive = true
                            }
                        }

                        if (isElasticActive) {
                            // Consume movement so parent scroll containers don't steal gesture and click is suppressed
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                            }

                            val normX = tanh(totalDrag.x / dragResistancePx)
                            val normY = tanh(totalDrag.y / dragResistancePx)

                            val absNormX = abs(normX)
                            val absNormY = abs(normY)

                            // Natural jelly deformation: stretch along drag axis + slight rubber Poisson compression/expansion
                            val targetScaleX = (1f + (absNormX * maxStretchScale) - (absNormY * 0.08f)).coerceIn(0.88f, 1.45f)
                            val targetScaleY = (1f + (absNormY * maxStretchScale) - (absNormX * 0.08f)).coerceIn(0.88f, 1.45f)

                            val targetTransX = normX * maxTransPx
                            val targetTransY = normY * maxTransPx

                            val targetCornerMorphX = normX * baseCornerPx * 0.6f
                            val targetCornerMorphY = normY * baseCornerPx * 0.6f

                            launch {
                                scaleX.snapTo(targetScaleX)
                                scaleY.snapTo(targetScaleY)
                                offsetX.snapTo(targetTransX)
                                offsetY.snapTo(targetTransY)
                                cornerMorphX.snapTo(targetCornerMorphX)
                                cornerMorphY.snapTo(targetCornerMorphY)
                            }
                        }
                    }

                    // Finger released: Smooth spring-back animation to original shape, position & corner radius
                    val springSpec = spring<Float>(
                        dampingRatio = 0.52f, // Smooth rubber spring without excessive wobble
                        stiffness = Spring.StiffnessMediumLow
                    )
                    launch { pressGlowScale.animateTo(1f, springSpec) }
                    launch { scaleX.animateTo(1f, springSpec) }
                    launch { scaleY.animateTo(1f, springSpec) }
                    launch { offsetX.animateTo(0f, springSpec) }
                    launch { offsetY.animateTo(0f, springSpec) }
                    launch { cornerMorphX.animateTo(0f, springSpec) }
                    launch { cornerMorphY.animateTo(0f, springSpec) }
                }
            }
        }
}
