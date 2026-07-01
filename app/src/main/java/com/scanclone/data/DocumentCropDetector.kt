package com.scanclone.data

import android.graphics.Bitmap
import android.graphics.Color
import com.scanclone.model.NormalizedCrop
import com.scanclone.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocumentCropDetector {
    private const val TARGET_LONG_EDGE = 640

    private data class Component(
        val id: Int,
        val area: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val score: Float
    )

    private data class Point(val x: Float, val y: Float)
    private data class XLine(val slope: Float, val intercept: Float)
    private data class YLine(val slope: Float, val intercept: Float)

    fun detect(bitmap: Bitmap): NormalizedCrop? {
        if (bitmap.width < 80 || bitmap.height < 80 || bitmap.isRecycled) return null
        val longEdge = max(bitmap.width, bitmap.height)
        val scale = if (longEdge > TARGET_LONG_EDGE) {
            TARGET_LONG_EDGE.toFloat() / longEdge.toFloat()
        } else {
            1f
        }
        val smallWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val smallHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (scale < 0.999f) {
            Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        } else {
            bitmap
        }
        return try {
            detectOnScaledBitmap(scaled)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun detectOnScaledBitmap(bitmap: Bitmap): NormalizedCrop? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scores = ByteArray(pixels.size)
        val histogram = IntArray(256)

        for (index in pixels.indices) {
            val color = pixels[index]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val maxChannel = max(r, max(g, b))
            val minChannel = min(r, min(g, b))
            val brightness = (r * 77 + g * 150 + b * 29) ushr 8
            val saturation = maxChannel - minChannel
            val score = (brightness - saturation * 0.74f + minChannel * 0.05f)
                .roundToInt()
                .coerceIn(0, 255)
            scores[index] = score.toByte()
            histogram[score] += 1
        }

        val threshold = (otsuThreshold(histogram, pixels.size) - 5).coerceIn(72, 224)
        val mask = ByteArray(pixels.size)
        for (index in scores.indices) {
            if ((scores[index].toInt() and 0xFF) >= threshold) mask[index] = 1
        }

        val closeRadius = (max(width, height) / 78).coerceIn(5, 11)
        val closed = erode(dilate(mask, width, height, closeRadius), width, height, closeRadius)
        val (labels, best) = chooseBestComponent(closed, width, height) ?: return fallbackFromRowsCols(mask, width, height)
        val crop = cropFromComponent(labels, best, width, height)
            ?: cropFromBounds(best, width, height)
            ?: return null
        return crop.takeUnless { it.isFullFrame() && best.area < width * height * 0.96f }
    }

    private fun chooseBestComponent(mask: ByteArray, width: Int, height: Int): Pair<IntArray, Component>? {
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var componentId = 1
        var best: Component? = null
        val total = width * height
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || labels[start] != 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = componentId
            var area = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var sumX = 0L
            var sumY = 0L
            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                area += 1
                sumX += x.toLong()
                sumY += y.toLong()
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                fun enqueue(next: Int) {
                    if (mask[next].toInt() != 0 && labels[next] == 0) {
                        labels[next] = componentId
                        queue[tail++] = next
                    }
                }
                if (x > 0) enqueue(current - 1)
                if (x < width - 1) enqueue(current + 1)
                if (y > 0) enqueue(current - width)
                if (y < height - 1) enqueue(current + width)
            }

            val areaRatio = area.toFloat() / total.toFloat()
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val boxWidthRatio = boxWidth.toFloat() / width.toFloat()
            val boxHeightRatio = boxHeight.toFloat() / height.toFloat()
            if (areaRatio in 0.08f..0.985f && boxWidthRatio > 0.36f && boxHeightRatio > 0.36f) {
                val centerX = sumX.toFloat() / area.toFloat() / width.toFloat()
                val centerY = sumY.toFloat() / area.toFloat() / height.toFloat()
                val centerPenalty = (abs(centerX - 0.5f) + abs(centerY - 0.5f)).coerceIn(0f, 0.9f)
                val fillRatio = area.toFloat() / (boxWidth * boxHeight).toFloat()
                val score = areaRatio * (1.15f - centerPenalty) + fillRatio * 0.10f
                val candidate = Component(componentId, area, minX, minY, maxX, maxY, score)
                if (best == null || candidate.score > best.score) best = candidate
            }
            componentId += 1
        }
        return best?.let { labels to it }
    }

    private fun cropFromComponent(labels: IntArray, component: Component, width: Int, height: Int): NormalizedCrop? {
        val left = ArrayList<Point>()
        val right = ArrayList<Point>()
        val top = ArrayList<Point>()
        val bottom = ArrayList<Point>()
        val minRunWidth = ((component.maxX - component.minX + 1) * 0.28f).roundToInt().coerceAtLeast(8)
        val minRunHeight = ((component.maxY - component.minY + 1) * 0.28f).roundToInt().coerceAtLeast(8)

        for (y in component.minY..component.maxY) {
            var first = -1
            var last = -1
            for (x in component.minX..component.maxX) {
                if (labels[y * width + x] == component.id) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0 && last - first + 1 >= minRunWidth) {
                left += Point(first.toFloat(), y.toFloat())
                right += Point(last.toFloat(), y.toFloat())
            }
        }

        for (x in component.minX..component.maxX) {
            var first = -1
            var last = -1
            for (y in component.minY..component.maxY) {
                if (labels[y * width + x] == component.id) {
                    if (first < 0) first = y
                    last = y
                }
            }
            if (first >= 0 && last - first + 1 >= minRunHeight) {
                top += Point(x.toFloat(), first.toFloat())
                bottom += Point(x.toFloat(), last.toFloat())
            }
        }

        val leftLine = fitXFromY(left) ?: return null
        val rightLine = fitXFromY(right) ?: return null
        val topLine = fitYFromX(top) ?: return null
        val bottomLine = fitYFromX(bottom) ?: return null
        val topLeft = intersect(leftLine, topLine) ?: return null
        val topRight = intersect(rightLine, topLine) ?: return null
        val bottomRight = intersect(rightLine, bottomLine) ?: return null
        val bottomLeft = intersect(leftLine, bottomLine) ?: return null
        return normalizeQuad(
            expandQuad(listOf(topLeft, topRight, bottomRight, bottomLeft), 0.018f),
            width,
            height
        )
    }

    private fun cropFromBounds(component: Component, width: Int, height: Int): NormalizedCrop? {
        val marginX = ((component.maxX - component.minX + 1) * 0.018f).roundToInt().coerceAtLeast(2)
        val marginY = ((component.maxY - component.minY + 1) * 0.018f).roundToInt().coerceAtLeast(2)
        return runCatching {
            NormalizedCrop.rectangle(
                ((component.minX - marginX).coerceAtLeast(0)).toFloat() / width,
                ((component.minY - marginY).coerceAtLeast(0)).toFloat() / height,
                ((component.maxX + marginX).coerceAtMost(width - 1)).toFloat() / width,
                ((component.maxY + marginY).coerceAtMost(height - 1)).toFloat() / height
            )
        }.getOrNull()
    }

    private fun fallbackFromRowsCols(mask: ByteArray, width: Int, height: Int): NormalizedCrop? {
        val rows = IntArray(height)
        val cols = IntArray(width)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                if (mask[offset + x].toInt() != 0) {
                    rows[y] += 1
                    cols[x] += 1
                }
            }
        }
        val rowThreshold = (width * 0.28f).roundToInt().coerceAtLeast(8)
        val colThreshold = (height * 0.28f).roundToInt().coerceAtLeast(8)
        val top = rows.indexOfFirst { it >= rowThreshold }
        val bottom = rows.indexOfLast { it >= rowThreshold }
        val left = cols.indexOfFirst { it >= colThreshold }
        val right = cols.indexOfLast { it >= colThreshold }
        if (top < 0 || bottom <= top || left < 0 || right <= left) return null
        val widthRatio = (right - left + 1).toFloat() / width.toFloat()
        val heightRatio = (bottom - top + 1).toFloat() / height.toFloat()
        if (widthRatio < 0.35f || heightRatio < 0.35f) return null
        return runCatching {
            NormalizedCrop.rectangle(
                (left - 3).coerceAtLeast(0) / width.toFloat(),
                (top - 3).coerceAtLeast(0) / height.toFloat(),
                (right + 3).coerceAtMost(width - 1) / width.toFloat(),
                (bottom + 3).coerceAtMost(height - 1) / height.toFloat()
            )
        }.getOrNull()
    }

    private fun fitXFromY(points: List<Point>): XLine? {
        if (points.size < 6) return null
        return fitXFromYRaw(trimOutliers(points, ::fitXFromYRaw) ?: points)
    }

    private fun fitYFromX(points: List<Point>): YLine? {
        if (points.size < 6) return null
        return fitYFromXRaw(trimOutliers(points, ::fitYFromXRaw) ?: points)
    }

    private fun fitXFromYRaw(points: List<Point>): XLine? {
        if (points.size < 2) return null
        val meanY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val meanX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        var numerator = 0f
        var denominator = 0f
        points.forEach { point ->
            val dy = point.y - meanY
            numerator += dy * (point.x - meanX)
            denominator += dy * dy
        }
        val slope = if (denominator <= 0.0001f) 0f else numerator / denominator
        return XLine(slope, meanX - slope * meanY)
    }

    private fun fitYFromXRaw(points: List<Point>): YLine? {
        if (points.size < 2) return null
        val meanX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val meanY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        var numerator = 0f
        var denominator = 0f
        points.forEach { point ->
            val dx = point.x - meanX
            numerator += dx * (point.y - meanY)
            denominator += dx * dx
        }
        val slope = if (denominator <= 0.0001f) 0f else numerator / denominator
        return YLine(slope, meanY - slope * meanX)
    }

    private fun <T> trimOutliers(points: List<Point>, fitter: (List<Point>) -> T?): List<Point>? {
        if (points.size < 12) return points
        val line = fitter(points) ?: return null
        val residuals = points.map { point ->
            val residual = when (line) {
                is XLine -> abs(point.x - (line.slope * point.y + line.intercept))
                is YLine -> abs(point.y - (line.slope * point.x + line.intercept))
                else -> 0f
            }
            point to residual
        }.sortedBy { it.second }
        val keep = (points.size * 0.78f).roundToInt().coerceIn(6, points.size)
        return residuals.take(keep).map { it.first }
    }

    private fun intersect(xLine: XLine, yLine: YLine): Point? {
        val denominator = 1f - yLine.slope * xLine.slope
        if (abs(denominator) < 0.001f) return null
        val y = (yLine.slope * xLine.intercept + yLine.intercept) / denominator
        val x = xLine.slope * y + xLine.intercept
        if (!x.isFinite() || !y.isFinite()) return null
        return Point(x, y)
    }

    private fun expandQuad(points: List<Point>, amount: Float): List<Point> {
        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        return points.map { point ->
            Point(
                centerX + (point.x - centerX) * (1f + amount),
                centerY + (point.y - centerY) * (1f + amount)
            )
        }
    }

    private fun normalizeQuad(points: List<Point>, width: Int, height: Int): NormalizedCrop? {
        if (points.size != 4) return null
        val margin = max(width, height) * 0.06f
        if (points.any { it.x < -margin || it.y < -margin || it.x > width + margin || it.y > height + margin }) {
            return null
        }
        val clamped = points.map { point ->
            Point(
                point.x.coerceIn(0f, (width - 1).toFloat()),
                point.y.coerceIn(0f, (height - 1).toFloat())
            )
        }
        val area = polygonArea(clamped)
        if (area < width * height * 0.08f) return null
        return runCatching {
            NormalizedCrop(
                NormalizedPoint(clamped[0].x / width.toFloat(), clamped[0].y / height.toFloat()),
                NormalizedPoint(clamped[1].x / width.toFloat(), clamped[1].y / height.toFloat()),
                NormalizedPoint(clamped[2].x / width.toFloat(), clamped[2].y / height.toFloat()),
                NormalizedPoint(clamped[3].x / width.toFloat(), clamped[3].y / height.toFloat())
            )
        }.getOrNull()
    }

    private fun polygonArea(points: List<Point>): Float {
        var area = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            area += current.x * next.y - next.x * current.y
        }
        return abs(area) / 2f
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sumAll = 0L
        for (i in histogram.indices) sumAll += i.toLong() * histogram[i].toLong()
        var backgroundWeight = 0L
        var backgroundSum = 0L
        var maxVariance = -1.0
        var threshold = 128
        for (i in histogram.indices) {
            backgroundWeight += histogram[i].toLong()
            if (backgroundWeight == 0L) continue
            val foregroundWeight = total.toLong() - backgroundWeight
            if (foregroundWeight == 0L) break
            backgroundSum += i.toLong() * histogram[i].toLong()
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight.toDouble()
            val foregroundMean = (sumAll - backgroundSum).toDouble() / foregroundWeight.toDouble()
            val variance = backgroundWeight.toDouble() *
                foregroundWeight.toDouble() *
                (backgroundMean - foregroundMean) *
                (backgroundMean - foregroundMean)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i
            }
        }
        return threshold
    }

    private fun dilate(source: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val horizontal = ByteArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = 0
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                var cursor = left
                while (cursor <= right && value == 0) {
                    value = source[row + cursor].toInt()
                    cursor += 1
                }
                horizontal[row + x] = value.toByte()
            }
        }
        val output = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = 0
                val top = (y - radius).coerceAtLeast(0)
                val bottom = (y + radius).coerceAtMost(height - 1)
                var cursor = top
                while (cursor <= bottom && value == 0) {
                    value = horizontal[cursor * width + x].toInt()
                    cursor += 1
                }
                output[y * width + x] = value.toByte()
            }
        }
        return output
    }

    private fun erode(source: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val horizontal = ByteArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = 1
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                var cursor = left
                while (cursor <= right && value == 1) {
                    if (source[row + cursor].toInt() == 0) value = 0
                    cursor += 1
                }
                horizontal[row + x] = value.toByte()
            }
        }
        val output = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = 1
                val top = (y - radius).coerceAtLeast(0)
                val bottom = (y + radius).coerceAtMost(height - 1)
                var cursor = top
                while (cursor <= bottom && value == 1) {
                    if (horizontal[cursor * width + x].toInt() == 0) value = 0
                    cursor += 1
                }
                output[y * width + x] = value.toByte()
            }
        }
        return output
    }
}
