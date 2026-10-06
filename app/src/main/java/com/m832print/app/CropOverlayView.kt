package com.m832print.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Minimal hand-rolled crop rectangle: drag a corner handle to resize,
 * drag inside the rect to move it. No external cropping library --
 * this project prefers a small amount of its own code over a dependency
 * for something this self-contained (same reasoning as not pulling in a
 * dependency for Bluetooth, PDF rendering, etc. elsewhere in this app).
 *
 * This view is meant to be stacked directly on top of the previewed
 * ImageView at the same size (see activity_main.xml's previewFrame) --
 * its normalized [0,1] rect then maps straight onto the bitmap's own
 * pixel space with no extra matrix math, as long as the ImageView has
 * no letterboxing (adjustViewBounds=true + wrap_content height).
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var rect = RectF(0.08f, 0.08f, 0.92f, 0.92f)

    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint().apply { color = Color.WHITE }
    private val handleRadiusPx = 16f
    private val touchSlopPx = 48f
    private val minSizeNormalized = 0.08f

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Resets to a default inset rect -- call when entering crop mode for a newly loaded page. */
    fun reset() {
        rect = RectF(0.08f, 0.08f, 0.92f, 0.92f)
        invalidate()
    }

    /** Crop rect in normalized [0,1] coordinates, relative to this view's (== the bitmap's) own bounds. */
    fun getNormalizedRect(): RectF = RectF(rect)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val px = RectF(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h)

        canvas.drawRect(0f, 0f, w, px.top, dimPaint)
        canvas.drawRect(0f, px.bottom, w, h, dimPaint)
        canvas.drawRect(0f, px.top, px.left, px.bottom, dimPaint)
        canvas.drawRect(px.right, px.top, w, px.bottom, dimPaint)

        canvas.drawRect(px, borderPaint)
        canvas.drawCircle(px.left, px.top, handleRadiusPx, handlePaint)
        canvas.drawCircle(px.right, px.top, handleRadiusPx, handlePaint)
        canvas.drawCircle(px.left, px.bottom, handleRadiusPx, handlePaint)
        canvas.drawCircle(px.right, px.bottom, handleRadiusPx, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val px = RectF(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    near(event.x, event.y, px.left, px.top) -> DragMode.TOP_LEFT
                    near(event.x, event.y, px.right, px.top) -> DragMode.TOP_RIGHT
                    near(event.x, event.y, px.left, px.bottom) -> DragMode.BOTTOM_LEFT
                    near(event.x, event.y, px.right, px.bottom) -> DragMode.BOTTOM_RIGHT
                    px.contains(event.x, event.y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                when (dragMode) {
                    DragMode.MOVE -> {
                        val moveX = (dx / w).coerceIn(-rect.left, 1f - rect.right)
                        val moveY = (dy / h).coerceIn(-rect.top, 1f - rect.bottom)
                        rect = RectF(rect.left + moveX, rect.top + moveY, rect.right + moveX, rect.bottom + moveY)
                    }
                    DragMode.TOP_LEFT -> rect = RectF(
                        (rect.left + dx / w).coerceIn(0f, rect.right - minSizeNormalized),
                        (rect.top + dy / h).coerceIn(0f, rect.bottom - minSizeNormalized),
                        rect.right,
                        rect.bottom,
                    )
                    DragMode.TOP_RIGHT -> rect = RectF(
                        rect.left,
                        (rect.top + dy / h).coerceIn(0f, rect.bottom - minSizeNormalized),
                        (rect.right + dx / w).coerceIn(rect.left + minSizeNormalized, 1f),
                        rect.bottom,
                    )
                    DragMode.BOTTOM_LEFT -> rect = RectF(
                        (rect.left + dx / w).coerceIn(0f, rect.right - minSizeNormalized),
                        rect.top,
                        rect.right,
                        (rect.bottom + dy / h).coerceIn(rect.top + minSizeNormalized, 1f),
                    )
                    DragMode.BOTTOM_RIGHT -> rect = RectF(
                        rect.left,
                        rect.top,
                        (rect.right + dx / w).coerceIn(rect.left + minSizeNormalized, 1f),
                        (rect.bottom + dy / h).coerceIn(rect.top + minSizeNormalized, 1f),
                    )
                    DragMode.NONE -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragMode != DragMode.NONE
                dragMode = DragMode.NONE
                return wasDragging
            }
        }
        return super.onTouchEvent(event)
    }

    private fun near(x: Float, y: Float, hx: Float, hy: Float): Boolean =
        abs(x - hx) <= touchSlopPx && abs(y - hy) <= touchSlopPx
}
