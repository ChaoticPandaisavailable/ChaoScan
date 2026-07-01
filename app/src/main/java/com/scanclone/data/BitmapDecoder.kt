package com.scanclone.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.scanclone.model.NormalizedCrop
import com.scanclone.model.NormalizedPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapDecoder {
    data class Bounds(
        val width: Int,
        val height: Int
    )

    fun decode(contentResolver: ContentResolver, uri: Uri, maxLongEdge: Int): Bitmap {
        require(maxLongEdge > 0) { "maxLongEdge must be positive." }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(contentResolver, uri, maxLongEdge)
        } else {
            decodeLegacy(contentResolver, uri, maxLongEdge)
        }
    }

    fun readBounds(contentResolver: ContentResolver, uri: Uri): Bounds {
        queryMediaBounds(contentResolver, uri)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: error("Cannot open image stream: $uri")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Cannot read image bounds: $uri"
        }
        return Bounds(bounds.outWidth, bounds.outHeight)
    }

    private fun queryMediaBounds(contentResolver: ContentResolver, uri: Uri): Bounds? {
        if (uri.scheme != "content") return null
        val projection = arrayOf(
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT
        )
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val widthIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.HEIGHT)
            if (widthIndex < 0 || heightIndex < 0) return@use null
            val width = cursor.getInt(widthIndex)
            val height = cursor.getInt(heightIndex)
            if (width > 0 && height > 0) Bounds(width, height) else null
        }
    }

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun crop(source: Bitmap, crop: NormalizedCrop?): Bitmap {
        if (crop == null) return source
        if (crop.isFullFrame()) return source

        val topLeft = crop.topLeft.toPixel(source)
        val topRight = crop.topRight.toPixel(source)
        val bottomRight = crop.bottomRight.toPixel(source)
        val bottomLeft = crop.bottomLeft.toPixel(source)

        val width = max(
            distance(topLeft[0], topLeft[1], topRight[0], topRight[1]),
            distance(bottomLeft[0], bottomLeft[1], bottomRight[0], bottomRight[1])
        ).roundToInt()
        val height = max(
            distance(topLeft[0], topLeft[1], bottomLeft[0], bottomLeft[1]),
            distance(topRight[0], topRight[1], bottomRight[0], bottomRight[1])
        ).roundToInt()
        require(width >= 16 && height >= 16) { "Crop rectangle is too small." }

        val src = floatArrayOf(
            topLeft[0], topLeft[1],
            topRight[0], topRight[1],
            bottomRight[0], bottomRight[1],
            bottomLeft[0], bottomLeft[1]
        )
        val dst = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        val matrix = Matrix()
        check(matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            "Cannot build perspective crop transform."
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }

    private fun NormalizedPoint.toPixel(source: Bitmap): FloatArray {
        return floatArrayOf(x * source.width.toFloat(), y * source.height.toFloat())
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    private fun decodeModern(contentResolver: ContentResolver, uri: Uri, maxLongEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longEdge = max(width, height)
            if (longEdge > maxLongEdge) {
                val scale = maxLongEdge.toFloat() / longEdge.toFloat()
                decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
            }
        }
    }

    private fun decodeLegacy(contentResolver: ContentResolver, uri: Uri, maxLongEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: error("Cannot open image stream: $uri")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("Cannot read image bounds: $uri")
        }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: error("Cannot decode bitmap: $uri")

        val longEdge = max(decoded.width, decoded.height)
        if (longEdge <= maxLongEdge) return decoded

        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt(),
            (decoded.height * scale).roundToInt(),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun calculateSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > maxLongEdge * 2) {
            sample *= 2
        }
        return sample
    }
}
