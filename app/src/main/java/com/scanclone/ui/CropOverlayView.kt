package com.scanclone.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewParent
import com.scanclone.model.NormalizedCrop
import com.scanclone.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val baseImageRect = RectF()
    private val imageRect = RectF()
    private val cropPath = Path()
    private val shadePath = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 184, 158)
        strokeWidth = 3.dp.toFloat()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 184, 158)
        strokeWidth = 3.dp.toFloat()
        style = Paint.Style.STROKE
    }
    private val points = Array(4) { NormalizedPoint(0f, 0f) }

    private var bitmap: Bitmap? = null
    private var activeHandle = NO_HANDLE
    private var lastNormX = 0f
    private var lastNormY = 0f
    private var imageScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var pinchActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    fun setImage(bitmap: Bitmap, crop: NormalizedCrop?) {
        this.bitmap = bitmap
        imageScale = 1f
        panX = 0f
        panY = 0f
        setCrop(crop ?: NormalizedCrop.uniform(0.04f))
    }

    fun setCrop(crop: NormalizedCrop) {
        points[0] = crop.topLeft
        points[1] = crop.topRight
        points[2] = crop.bottomRight
        points[3] = crop.bottomLeft
        invalidate()
    }

    fun currentCrop(): NormalizedCrop {
        return NormalizedCrop(
            topLeft = points[0],
            topRight = points[1],
            bottomRight = points[2],
            bottomLeft = points[3]
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val current = bitmap ?: return
        updateImageRect(current)
        canvas.drawBitmap(current, null, imageRect, bitmapPaint)
        buildCropPath()

        shadePath.reset()
        shadePath.fillType = Path.FillType.EVEN_ODD
        shadePath.addRect(imageRect, Path.Direction.CW)
        shadePath.addPath(cropPath)
        canvas.drawPath(shadePath, shadePaint)
        canvas.drawPath(cropPath, strokePaint)

        drawEdgeHandle(canvas, 0, 1)
        drawEdgeHandle(canvas, 1, 2)
        drawEdgeHandle(canvas, 2, 3)
        drawEdgeHandle(canvas, 3, 0)
        for (index in 0..3) {
            val p = pointToView(index)
            canvas.drawCircle(p.first, p.second, CORNER_RADIUS_DP.dp.toFloat(), handleFillPaint)
            canvas.drawCircle(p.first, p.second, CORNER_RADIUS_DP.dp.toFloat(), handleStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false
        if (event.pointerCount > 1 || pinchActive) {
            handlePinch(event)
            return true
        }
        val norm = eventToNormalized(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = findHandle(event.x, event.y)
                if (activeHandle == NO_HANDLE) return false
                lastNormX = norm.first
                lastNormY = norm.second
                parent?.requestNoIntercept(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = norm.first - lastNormX
                val dy = norm.second - lastNormY
                when (activeHandle) {
                    in 0..3 -> moveCorner(activeHandle, norm.first, norm.second)
                    EDGE_TOP -> movePoints(intArrayOf(0, 1), 0f, dy)
                    EDGE_RIGHT -> movePoints(intArrayOf(1, 2), dx, 0f)
                    EDGE_BOTTOM -> movePoints(intArrayOf(2, 3), 0f, dy)
                    EDGE_LEFT -> movePoints(intArrayOf(3, 0), dx, 0f)
                    MOVE_FRAME -> movePoints(intArrayOf(0, 1, 2, 3), dx, dy)
                }
                lastNormX = norm.first
                lastNormY = norm.second
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = NO_HANDLE
                parent?.requestNoIntercept(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateImageRect(current: Bitmap) {
        val availableWidth = width.toFloat() - 24.dp
        val availableHeight = height.toFloat() - 24.dp
        val scale = min(availableWidth / current.width.toFloat(), availableHeight / current.height.toFloat())
        val baseWidth = current.width * scale
        val baseHeight = current.height * scale
        val baseLeft = (width - baseWidth) / 2f
        val baseTop = (height - baseHeight) / 2f
        baseImageRect.set(baseLeft, baseTop, baseLeft + baseWidth, baseTop + baseHeight)
        clampPan()

        val drawWidth = baseWidth * imageScale
        val drawHeight = baseHeight * imageScale
        val centerX = baseImageRect.centerX() + panX
        val centerY = baseImageRect.centerY() + panY
        imageRect.set(
            centerX - drawWidth / 2f,
            centerY - drawHeight / 2f,
            centerX + drawWidth / 2f,
            centerY + drawHeight / 2f
        )
    }

    private fun handlePinch(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                activeHandle = NO_HANDLE
                pinchActive = true
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                parent?.requestNoIntercept(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val focusX = focusX(event)
                val focusY = focusY(event)
                if (event.pointerCount >= 2) {
                    panX += focusX - lastFocusX
                    panY += focusY - lastFocusY
                    lastFocusX = focusX
                    lastFocusY = focusY
                    clampPan()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                pinchActive = false
                parent?.requestNoIntercept(false)
            }
        }
    }

    private fun clampPan() {
        if (baseImageRect.isEmpty) return
        val scaledWidth = baseImageRect.width() * imageScale
        val scaledHeight = baseImageRect.height() * imageScale
        val maxPanX = max(0f, (scaledWidth - width.toFloat()) / 2f)
        val maxPanY = max(0f, (scaledHeight - height.toFloat()) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        if (imageScale <= 1.001f) {
            panX = 0f
            panY = 0f
        }
    }

    private fun focusX(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getX(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun focusY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun buildCropPath() {
        cropPath.reset()
        val p0 = pointToView(0)
        cropPath.moveTo(p0.first, p0.second)
        for (index in 1..3) {
            val p = pointToView(index)
            cropPath.lineTo(p.first, p.second)
        }
        cropPath.close()
    }

    private fun drawEdgeHandle(canvas: Canvas, from: Int, to: Int) {
        val p1 = pointToView(from)
        val p2 = pointToView(to)
        val centerX = (p1.first + p2.first) / 2f
        val centerY = (p1.second + p2.second) / 2f
        val horizontal = abs(p1.first - p2.first) >= abs(p1.second - p2.second)
        val width = if (horizontal) 34.dp.toFloat() else 12.dp.toFloat()
        val height = if (horizontal) 12.dp.toFloat() else 34.dp.toFloat()
        val rect = RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f
        )
        canvas.drawRoundRect(rect, 6.dp.toFloat(), 6.dp.toFloat(), handleFillPaint)
        canvas.drawRoundRect(rect, 6.dp.toFloat(), 6.dp.toFloat(), handleStrokePaint)
    }

    private fun findHandle(x: Float, y: Float): Int {
        for (index in 0..3) {
            val p = pointToView(index)
            if (distance(x, y, p.first, p.second) <= TOUCH_RADIUS_DP.dp) return index
        }
        edgeHandleAt(x, y, 0, 1)?.let { return EDGE_TOP }
        edgeHandleAt(x, y, 1, 2)?.let { return EDGE_RIGHT }
        edgeHandleAt(x, y, 2, 3)?.let { return EDGE_BOTTOM }
        edgeHandleAt(x, y, 3, 0)?.let { return EDGE_LEFT }
        return if (pointInsideCrop(x, y)) MOVE_FRAME else NO_HANDLE
    }

    private fun edgeHandleAt(x: Float, y: Float, from: Int, to: Int): Boolean? {
        val p1 = pointToView(from)
        val p2 = pointToView(to)
        val centerX = (p1.first + p2.first) / 2f
        val centerY = (p1.second + p2.second) / 2f
        return true.takeIf { distance(x, y, centerX, centerY) <= TOUCH_RADIUS_DP.dp }
    }

    private fun moveCorner(index: Int, normX: Float, normY: Float) {
        val minGap = MIN_GAP
        val x = normX.coerceIn(0f, 1f)
        val y = normY.coerceIn(0f, 1f)
        points[index] = when (index) {
            0 -> NormalizedPoint(
                x.coerceIn(0f, min(points[1].x, points[2].x) - minGap),
                y.coerceIn(0f, min(points[3].y, points[2].y) - minGap)
            )
            1 -> NormalizedPoint(
                x.coerceIn(max(points[0].x, points[3].x) + minGap, 1f),
                y.coerceIn(0f, min(points[2].y, points[3].y) - minGap)
            )
            2 -> NormalizedPoint(
                x.coerceIn(max(points[3].x, points[0].x) + minGap, 1f),
                y.coerceIn(max(points[1].y, points[0].y) + minGap, 1f)
            )
            else -> NormalizedPoint(
                x.coerceIn(0f, min(points[2].x, points[1].x) - minGap),
                y.coerceIn(max(points[0].y, points[1].y) + minGap, 1f)
            )
        }
    }

    private fun movePoints(indices: IntArray, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        var allowedDx = dx
        var allowedDy = dy
        indices.forEach { index ->
            val point = points[index]
            allowedDx = allowedDx.coerceAtLeast(-point.x).coerceAtMost(1f - point.x)
            allowedDy = allowedDy.coerceAtLeast(-point.y).coerceAtMost(1f - point.y)
        }
        val updated = points.copyOf()
        indices.forEach { index ->
            val point = points[index]
            updated[index] = NormalizedPoint(
                (point.x + allowedDx).coerceIn(0f, 1f),
                (point.y + allowedDy).coerceIn(0f, 1f)
            )
        }
        runCatching {
            NormalizedCrop(updated[0], updated[1], updated[2], updated[3])
        }.onSuccess {
            indices.forEach { index -> points[index] = updated[index] }
        }
    }

    private fun eventToNormalized(x: Float, y: Float): Pair<Float, Float> {
        val nx = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        return nx to ny
    }

    private fun pointToView(index: Int): Pair<Float, Float> {
        val point = points[index]
        return imageRect.left + point.x * imageRect.width() to
            imageRect.top + point.y * imageRect.height()
    }

    private fun pointInsideCrop(x: Float, y: Float): Boolean {
        var inside = false
        var previous = points.last()
        points.forEach { current ->
            val currentX = imageRect.left + current.x * imageRect.width()
            val currentY = imageRect.top + current.y * imageRect.height()
            val previousX = imageRect.left + previous.x * imageRect.width()
            val previousY = imageRect.top + previous.y * imageRect.height()
            if ((currentY > y) != (previousY > y)) {
                val intersectX = (previousX - currentX) * (y - currentY) /
                    (previousY - currentY) + currentX
                if (x < intersectX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    private fun ViewParent.requestNoIntercept(disallow: Boolean) {
        requestDisallowInterceptTouchEvent(disallow)
    }

    companion object {
        private const val NO_HANDLE = -1
        private const val EDGE_TOP = 4
        private const val EDGE_RIGHT = 5
        private const val EDGE_BOTTOM = 6
        private const val EDGE_LEFT = 7
        private const val MOVE_FRAME = 8
        private const val CORNER_RADIUS_DP = 8
        private const val TOUCH_RADIUS_DP = 32
        private const val MIN_GAP = 0.04f
        private const val MAX_IMAGE_SCALE = 5f
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextScale = (imageScale * detector.scaleFactor).coerceIn(1f, MAX_IMAGE_SCALE)
            imageScale = nextScale
            clampPan()
            invalidate()
            return true
        }
    }
}
