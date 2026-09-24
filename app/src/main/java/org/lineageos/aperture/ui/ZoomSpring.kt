/*
 * SPDX-FileCopyrightText: 2026 The LegacyDroid Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.ui

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min

/**
 * Drives a camera zoom value towards a target with a critically damped spring,
 * advanced once per display frame.
 *
 * The spring is simulated rather than time-scaled curve played back. That is
 * what makes an interrupted move feel continuous: [setTarget] keeps the
 * current velocity, so retargeting halfway through a move blends into it
 * instead of stopping and starting over. A played-back animation has no
 * velocity to carry, so every new target reads as a separate little move.
 *
 * The value drives the camera itself rather than a view transform, so the
 * recorded video carries the same ramp the preview shows.
 *
 * @param initialValue Starting value
 * @param onUpdate Called with the new value on every frame the spring moves
 */
class ZoomSpring(
    initialValue: Float,
    private val onUpdate: (Float) -> Unit
) {
    var value: Float = initialValue
        private set

    var target: Float = initialValue
        private set

    /**
     * Angular frequency of the spring. Higher tracks the target more tightly.
     */
    var stiffness: Float = STIFFNESS_SETTLE

    private var velocity = 0f
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var stopped = true

    /**
     * Aim at [newTarget], keeping the current velocity so a move interrupted
     * part way blends into the new target rather than restarting.
     */
    fun setTarget(newTarget: Float) {
        if (newTarget == target && !stopped) {
            return
        }

        target = newTarget
        stopped = false
        schedule()
    }

    /**
     * Adopt [newValue] as both the current and target value with no motion,
     * discarding anything in flight. Used to resync with a zoom the user
     * reached by some other route, such as a pinch.
     */
    fun resetTo(newValue: Float) {
        cancel()
        value = newValue
        target = newValue
        velocity = 0f
    }

    /**
     * Abandon the move in progress, leaving the value wherever it had reached
     * so the next interaction continues from what is on screen.
     */
    fun cancel() {
        if (frameScheduled) {
            choreographer?.removeFrameCallback(frameCallback)
        }

        frameScheduled = false
        lastFrameNanos = 0L
        stopped = true
    }

    fun isActive(): Boolean = !stopped &&
        (abs(velocity) > EPSILON || abs(target - value) > EPSILON)

    private fun schedule() {
        if (frameScheduled || stopped) {
            return
        }

        val choreographer = choreographer
            ?: Choreographer.getInstance().also { this.choreographer = it }
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false

        // There is no previous timestamp to difference against on the first
        // frame after waking, so step by a nominal frame length instead. A zero
        // step would leave the spring active but motionless for one frame,
        // delaying the start of every move.
        val deltaSeconds = if (lastFrameNanos == 0L) {
            NOMINAL_FRAME_SECONDS
        } else {
            (frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND
        }
        lastFrameNanos = frameTimeNanos

        val step = min(deltaSeconds, MAX_DELTA_SECONDS) / SUBSTEPS
        repeat(SUBSTEPS) {
            velocity += (stiffness * stiffness * (target - value) -
                2f * stiffness * velocity) * step
            value += velocity * step
        }

        if (isActive()) {
            onUpdate(value)
            schedule()
        } else {
            // The curve is asymptotic, so land exactly on the target instead
            // of leaving a remainder too small to see but large enough to
            // report.
            value = target
            velocity = 0f
            lastFrameNanos = 0L
            stopped = true
            onUpdate(value)
        }
    }

    companion object {
        /**
         * Angular frequency used once the target is final. Soft enough that the
         * arrival eases out rather than stopping dead, which is what a zoom
         * should do once the fingers are off the control.
         */
        const val STIFFNESS_SETTLE = 18f

        /**
         * Convergence threshold on both velocity and remaining travel. Small
         * enough that the residual crop shift is below what reads as motion,
         * so converging further only keeps driving the camera for a change
         * nobody can see.
         */
        private const val EPSILON = 0.002f

        /**
         * Integration is split into substeps for margin against the explicit
         * integrator's stability limit.
         */
        private const val SUBSTEPS = 2

        /**
         * Longest step ever integrated, so a stalled frame or a gap across a
         * pause cannot hand the integrator an enormous delta and fling the
         * spring past the target.
         */
        private const val MAX_DELTA_SECONDS = 0.033f

        private const val NOMINAL_FRAME_SECONDS = 1f / 60f
        private const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
