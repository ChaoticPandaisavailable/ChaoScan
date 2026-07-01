package com.scanclone.model

import android.net.Uri

data class DocumentPage(
    val sourceUri: Uri,
    val title: String,
    var rotationDegrees: Int = 0,
    var crop: NormalizedCrop? = null,
    var scanParameters: ScanParameters = ScanParameters()
) {
    val stableId: Long = 31L * sourceUri.hashCode() + title.hashCode()
}

data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    init {
        require(x in 0f..1f) { "crop point x must be in 0..1." }
        require(y in 0f..1f) { "crop point y must be in 0..1." }
    }
}

data class NormalizedCrop(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint
) {
    val left: Float get() = minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
    val top: Float get() = minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
    val right: Float get() = maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
    val bottom: Float get() = maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)

    init {
        require(right - left >= 0.05f) { "crop width is too small." }
        require(bottom - top >= 0.05f) { "crop height is too small." }
        require(polygonArea() >= 0.01f) { "crop polygon is too small." }
    }

    fun isFullFrame(): Boolean {
        return topLeft == NormalizedPoint(0f, 0f) &&
            topRight == NormalizedPoint(1f, 0f) &&
            bottomRight == NormalizedPoint(1f, 1f) &&
            bottomLeft == NormalizedPoint(0f, 1f)
    }

    private fun polygonArea(): Float {
        val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
        var area = 0f
        points.forEachIndexed { index, point ->
            val next = points[(index + 1) % points.size]
            area += point.x * next.y - next.x * point.y
        }
        return kotlin.math.abs(area) / 2f
    }

    companion object {
        fun full(): NormalizedCrop = rectangle(0f, 0f, 1f, 1f)

        fun rectangle(left: Float, top: Float, right: Float, bottom: Float): NormalizedCrop {
            require(left in 0f..0.95f) { "left crop must be in 0..0.95." }
            require(top in 0f..0.95f) { "top crop must be in 0..0.95." }
            require(right in 0.05f..1f) { "right crop must be in 0.05..1." }
            require(bottom in 0.05f..1f) { "bottom crop must be in 0.05..1." }
            require(left < right) { "left crop must be smaller than right crop." }
            require(top < bottom) { "top crop must be smaller than bottom crop." }
            return NormalizedCrop(
                topLeft = NormalizedPoint(left, top),
                topRight = NormalizedPoint(right, top),
                bottomRight = NormalizedPoint(right, bottom),
                bottomLeft = NormalizedPoint(left, bottom)
            )
        }

        fun uniform(inset: Float): NormalizedCrop {
            require(inset in 0f..0.45f) { "uniform crop inset must be in 0..0.45." }
            return rectangle(inset, inset, 1f - inset, 1f - inset)
        }
    }
}
