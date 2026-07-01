package com.scanclone.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private val mappedRect = RectF()
    private val bitmapRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    private var bitmap: Bitmap? = null
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var scaling = false
    private var hasMovedWhileZoomed = false

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        bitmap = bm
        super.setImageDrawable(null)
        resetZoom()
        invalidate()
    }

    fun setImageBitmapPreservingZoom(bm: Bitmap?) {
        val state = captureZoomState()
        setImageBitmapPreservingZoom(bm, state)
    }

    fun setImageBitmapPreservingZoom(bm: Bitmap?, state: ZoomState?) {
        bitmap = bm
        super.setImageDrawable(null)
        resetZoom()
        restoreZoomState(state)
        invalidate()
    }

    fun isZoomedIn(): Boolean {
        return currentScale > 1.01f
    }

    fun bitmapCopyForTransition(): Bitmap? {
        val current = bitmap ?: return null
        if (current.isRecycled) return null
        return current.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun captureZoomState(): ZoomState? {
        if (bitmap == null && drawable == null) return null
        val sourceWidth = bitmap?.takeUnless { it.isRecycled }?.width
            ?: drawable?.intrinsicWidth
            ?: return null
        val sourceHeight = bitmap?.takeUnless { it.isRecycled }?.height
            ?: drawable?.intrinsicHeight
            ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return null
        val inverse = Matrix()
        if (!drawMatrix.invert(inverse)) return null
        val center = floatArrayOf(contentCenterX(), contentCenterY())
        inverse.mapPoints(center)
        return ZoomState(
            centerX = (center[0] / sourceWidth.toFloat()).coerceIn(0f, 1f),
            centerY = (center[1] / sourceHeight.toFloat()).coerceIn(0f, 1f),
            scale = currentScale
        )
    }

    fun restoreZoomState(state: ZoomState?) {
        if (state == null) return
        val sourceWidth = bitmap?.takeUnless { it.isRecycled }?.width
            ?: drawable?.intrinsicWidth
            ?: return
        val sourceHeight = bitmap?.takeUnless { it.isRecycled }?.height
            ?: drawable?.intrinsicHeight
            ?: return
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        if (state.scale <= 1.01f) {
            currentScale = 1f
            fixTranslation()
            invalidate()
            return
        }
        val targetScale = state.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val pivotX = contentCenterX()
        val pivotY = contentCenterY()
        drawMatrix.postScale(targetScale / currentScale, targetScale / currentScale, pivotX, pivotY)
        currentScale = targetScale
        val mappedCenter = floatArrayOf(
            state.centerX * sourceWidth.toFloat(),
            state.centerY * sourceHeight.toFloat()
        )
        drawMatrix.mapPoints(mappedCenter)
        drawMatrix.postTranslate(pivotX - mappedCenter[0], pivotY - mappedCenter[1])
        fixTranslation()
        invalidate()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        bitmap = null
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun setImageURI(uri: Uri?) {
        bitmap = null
        super.setImageURI(uri)
        post { resetZoom() }
    }

    override fun onDraw(canvas: Canvas) {
        val current = bitmap
        if (current == null || current.isRecycled) {
            super.onDraw(canvas)
            return
        }
        if (width <= 0 || height <= 0) return
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawBitmap(current, drawMatrix, bitmapPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val state = if (isZoomedIn()) captureZoomState() else null
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
        restoreZoomState(state)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null && drawable == null) return super.onTouchEvent(event)

        if (event.pointerCount > 1 || currentScale > 1f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                scaling = false
                hasMovedWhileZoomed = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scaling = true
                lastX = averageX(event)
                lastY = averageY(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                scaling = true
                lastX = averageX(event)
                lastY = averageY(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.pointerCount >= 2) {
                    val focusX = averageX(event)
                    val focusY = averageY(event)
                    if (currentScale > 1f && !scaleDetector.isInProgress) {
                        translateBy(focusX - lastX, focusY - lastY)
                        hasMovedWhileZoomed = true
                    }
                    lastX = focusX
                    lastY = focusY
                    return true
                }

                val dx = event.x - lastX
                val dy = event.y - lastY
                if (currentScale > 1f) {
                    if (!dragging && distanceMoved(event.x, event.y) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        translateBy(dx, dy)
                        hasMovedWhileZoomed = true
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging && !scaling && !hasMovedWhileZoomed && distanceMoved(event.x, event.y) <= touchSlop) {
                    performClick()
                }
                dragging = false
                scaling = false
                hasMovedWhileZoomed = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                scaling = false
                hasMovedWhileZoomed = false
                return true
            }
        }
        return true
    }

    fun resetZoom() {
        val current = bitmap
        if (current != null && !current.isRecycled) {
            resetBitmapMatrix(current.width, current.height)
            invalidate()
            return
        }
        val currentDrawable = drawable ?: return
        if (currentDrawable.intrinsicWidth > 0 && currentDrawable.intrinsicHeight > 0) {
            resetDrawableMatrix(currentDrawable.intrinsicWidth, currentDrawable.intrinsicHeight)
        }
    }

    private fun resetBitmapMatrix(bitmapWidth: Int, bitmapHeight: Int) {
        if (width <= 0 || height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val scale = min(
            contentWidth.toFloat() / bitmapWidth.toFloat(),
            contentHeight.toFloat() / bitmapHeight.toFloat()
        )
        val dx = paddingLeft + (contentWidth - bitmapWidth * scale) / 2f
        val dy = paddingTop + (contentHeight - bitmapHeight * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        currentScale = 1f
        fixTranslation()
    }

    private fun resetDrawableMatrix(drawableWidth: Int, drawableHeight: Int) {
        if (width <= 0 || height <= 0 || drawableWidth <= 0 || drawableHeight <= 0) return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val scale = min(
            contentWidth.toFloat() / drawableWidth.toFloat(),
            contentHeight.toFloat() / drawableHeight.toFloat()
        )
        val dx = paddingLeft + (contentWidth - drawableWidth * scale) / 2f
        val dy = paddingTop + (contentHeight - drawableHeight * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        currentScale = 1f
    }

    private fun translateBy(dx: Float, dy: Float) {
        drawMatrix.postTranslate(dx, dy)
        fixTranslation()
        invalidate()
    }

    private fun fixTranslation() {
        val rect = displayRect() ?: return
        var dx = 0f
        var dy = 0f
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentRight = (width - paddingRight).toFloat()
        val contentBottom = (height - paddingBottom).toFloat()
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop

        dx = if (rect.width() <= contentWidth) {
            contentLeft + (contentWidth - rect.width()) / 2f - rect.left
        } else {
            when {
                rect.left > contentLeft -> contentLeft - rect.left
                rect.right < contentRight -> contentRight - rect.right
                else -> 0f
            }
        }
        dy = if (rect.height() <= contentHeight) {
            contentTop + (contentHeight - rect.height()) / 2f - rect.top
        } else {
            when {
                rect.top > contentTop -> contentTop - rect.top
                rect.bottom < contentBottom -> contentBottom - rect.bottom
                else -> 0f
            }
        }
        if (dx != 0f || dy != 0f) {
            drawMatrix.postTranslate(dx, dy)
        }
    }

    private fun displayRect(): RectF? {
        val current = bitmap
        if (current == null || current.isRecycled) {
            val currentDrawable = drawable ?: return null
            bitmapRect.set(
                0f,
                0f,
                currentDrawable.intrinsicWidth.toFloat(),
                currentDrawable.intrinsicHeight.toFloat()
            )
        } else {
            bitmapRect.set(0f, 0f, current.width.toFloat(), current.height.toFloat())
        }
        mappedRect.set(bitmapRect)
        drawMatrix.mapRect(mappedRect)
        return mappedRect
    }

    private fun contentCenterX(): Float {
        return (paddingLeft + width - paddingRight) / 2f
    }

    private fun contentCenterY(): Float {
        return (paddingTop + height - paddingBottom) / 2f
    }

    private fun distanceMoved(x: Float, y: Float): Float {
        return max(abs(x - downX), abs(y - downY))
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getX(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = target / currentScale
            if (factor == 1f) return true
            currentScale = target
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            scaling = true
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaling = true
            return true
        }
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 6f
    }

    data class ZoomState(
        internal val centerX: Float,
        internal val centerY: Float,
        internal val scale: Float
    )
}
