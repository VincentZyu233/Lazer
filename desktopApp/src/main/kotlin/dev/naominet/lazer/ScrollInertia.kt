package dev.naominet.lazer

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.pow

/** Shared wheel, trackpad, and direct-touch inertia used across the desktop app. */
@Composable
fun rememberScrollInertiaController(): ScrollInertiaController {
    val controller = remember { ScrollInertiaController() }
    LaunchedEffect(controller) {
        var previousFrameNs = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (previousFrameNs == 0L) {
                    1f / 60f
                } else {
                    ((now - previousFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                }
                previousFrameNs = now
                controller.advance(dt)
            }
        }
    }
    return controller
}

class ScrollInertiaController {
    private val motion = WheelInertiaMotion()
    private var target: ScrollableState? = null

    fun impulse(scrollState: ScrollableState, delta: Float) {
        if (target !== scrollState) {
            stop()
            target = scrollState
        }
        scrollState.dispatchRawDelta(motion.impulse(delta))
    }

    /** Continue a direct touch/mouse drag with the release velocity, in content pixels/second. */
    fun fling(scrollState: ScrollableState, velocity: Float) {
        if (target !== scrollState) {
            stop()
            target = scrollState
        }
        motion.fling(velocity)
    }

    internal fun advance(dt: Float) {
        val movement = motion.advance(dt)
        if (movement == 0f) return
        val consumed = target?.dispatchRawDelta(movement) ?: 0f
        // Stop pushing once a list reaches an edge. This also prevents a stale fling from being
        // resumed if another scroll target is selected immediately afterwards.
        if (abs(consumed) < abs(movement) * 0.05f) stop()
    }

    fun stop() {
        motion.stop()
        target = null
    }
}

/** The motion model shared by wheel input and released direct drags. */
internal class WheelInertiaMotion {
    private var velocity = 0f

    fun impulse(delta: Float): Float {
        velocity = (velocity + delta * WheelInertiaDefaults.VelocityMultiplier)
            .coerceIn(-WheelInertiaDefaults.MaximumVelocity, WheelInertiaDefaults.MaximumVelocity)
        return delta * WheelInertiaDefaults.DirectMultiplier
    }

    /** Adopt direct-manipulation velocity while keeping the same decay curve as wheel input. */
    fun fling(velocityPixelsPerSecond: Float) {
        velocity = (velocityPixelsPerSecond / 60f)
            .coerceIn(-WheelInertiaDefaults.MaximumVelocity, WheelInertiaDefaults.MaximumVelocity)
        if (abs(velocity) < WheelInertiaDefaults.StopVelocity) velocity = 0f
    }

    fun advance(dt: Float): Float {
        if (abs(velocity) <= WheelInertiaDefaults.StopVelocity) {
            velocity = 0f
            return 0f
        }
        val movement = velocity * dt * 60f
        velocity *= WheelInertiaDefaults.DecayPerFrame.pow(dt * 60f)
        if (abs(velocity) < WheelInertiaDefaults.StopVelocity) velocity = 0f
        return movement
    }

    fun stop() {
        velocity = 0f
    }
}

/** Low-pass velocity estimate from the deltas supplied by Compose's drag detector. */
internal class DragVelocityTracker {
    private var velocityPixelsPerSecond = 0f
    private var hasSample = false

    fun addDelta(delta: Float, elapsedMillis: Long) {
        val seconds = elapsedMillis.coerceIn(1L, 50L) / 1_000f
        val instantaneous = delta / seconds
        velocityPixelsPerSecond = if (hasSample) {
            velocityPixelsPerSecond * 0.65f + instantaneous * 0.35f
        } else {
            instantaneous
        }
        hasSample = true
    }

    fun releaseVelocity(): Float {
        val result = if (hasSample) velocityPixelsPerSecond else 0f
        reset()
        return result
    }

    fun reset() {
        velocityPixelsPerSecond = 0f
        hasSample = false
    }
}

internal object WheelInertiaDefaults {
    const val DirectMultiplier = 36f
    const val VelocityMultiplier = 28f
    const val MaximumVelocity = 110f
    const val StopVelocity = 0.35f
    const val DecayPerFrame = 0.90f
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.scrollInertia(
    scrollState: ScrollableState,
    controller: ScrollInertiaController,
    orientation: Orientation = Orientation.Vertical,
    enabled: Boolean = true,
    onUserScroll: (() -> Unit)? = null,
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(scrollState, orientation) {
            var touchGesture = false
            val velocityTracker = DragVelocityTracker()
            detectDragGestures(
                onDragStart = {
                    touchGesture = false
                    velocityTracker.reset()
                    controller.stop()
                },
                onDragEnd = {
                    controller.fling(scrollState, velocityTracker.releaseVelocity())
                },
                onDragCancel = {
                    velocityTracker.reset()
                    controller.stop()
                },
                onDrag = { change, dragAmount ->
                    if (!touchGesture) {
                        touchGesture = true
                        onUserScroll?.invoke()
                    }
                    val delta = when (orientation) {
                        Orientation.Vertical -> dragAmount.y
                        Orientation.Horizontal -> dragAmount.x
                    }
                    if (delta != 0f) {
                        change.consume()
                        val contentDelta = -delta
                        velocityTracker.addDelta(
                            delta = contentDelta,
                            elapsedMillis = change.uptimeMillis - change.previousUptimeMillis,
                        )
                        scrollState.dispatchRawDelta(contentDelta)
                    }
                },
            )
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.fold(0f) { acc, change ->
                val scroll = change.scrollDelta
                acc + when (orientation) {
                    Orientation.Vertical -> scroll.y
                    // Keep a normal vertical wheel moving the surrounding page. Horizontal
                    // trackpad/shift-wheel input belongs to the nested playlist strip.
                    Orientation.Horizontal -> scroll.x
                }
            }
            if (delta != 0f) {
                event.changes.forEach { it.consume() }
                onUserScroll?.invoke()
                controller.impulse(scrollState, delta)
            }
        }
}
