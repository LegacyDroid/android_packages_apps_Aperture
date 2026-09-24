/*
 * SPDX-FileCopyrightText: 2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.ui

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Range
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.lineageos.aperture.ext.*
import kotlin.math.abs

class HorizontalSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : Slider(context, attrs) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

    /**
     * Invoked when the slider is tapped rather than dragged, carrying the
     * progress the tap landed on. Dragging still reports through
     * [onProgressChangedByUser] so it can track the finger directly.
     */
    var onTappedAt: ((value: Float) -> Unit)? = null

    override fun track(): RectF {
        val trackHeight = height / 5

        val left = height / 2f
        val right = width - left

        val top = (height - trackHeight) / 2f
        val bottom = height - top

        return RectF(left, top, right, bottom)
    }

    override fun thumb(): Triple<Float, Float, Float> {
        val track = track()
        val trackWidth = track.width()

        val cx = if (steps > 0) {
            val progress = Int.mapToRange(Range(0, steps), progress).toFloat() / steps
            (trackWidth * progress) + track.left
        } else {
            (trackWidth * progress) + track.left
        }
        val cy = height / 2f

        return Triple(cx, cy, height / 2.15f)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        super.onTouchEvent(event)

        if (!isEnabled) {
            return false
        }

        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                }

                if (dragging) {
                    updateProgress(event.x)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    updateProgress(event.x)
                } else {
                    // A tap is a request to go somewhere, not a request to be
                    // there now, so it is reported separately and left for the
                    // caller to animate towards. Without a tap handler there is
                    // nothing to animate, so fall back to committing directly.
                    val target = progressFor(event.x)
                    if (onTappedAt != null) {
                        onTappedAt?.invoke(target)
                    } else {
                        updateProgress(event.x)
                    }
                }

                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }

        return true
    }

    private fun updateProgress(x: Float) {
        progress = progressFor(x)
        onProgressChangedByUser?.invoke(progress)
    }

    private fun progressFor(x: Float): Float = x.coerceIn(0f, width.toFloat()) / width
}
