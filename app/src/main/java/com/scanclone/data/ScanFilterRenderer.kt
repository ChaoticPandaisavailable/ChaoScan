package com.scanclone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.scanclone.model.ScanParameters
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageExposureFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGammaFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGrayscaleFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import kotlin.math.max
import kotlin.math.pow

class ScanFilterRenderer(context: Context) {
    private val appContext = context.applicationContext

    private data class BackgroundMap(
        val values: IntArray,
        val width: Int,
        val height: Int,
        val scale: Int
    )

    fun render(source: Bitmap, parameters: ScanParameters): Bitmap {
        if (parameters == ScanParameters.original()) {
            return source
        }
        if (parameters.invert) {
            return renderSoftInvert(source, parameters)
        }
        val gpuImage = GPUImage(appContext)
        gpuImage.setImage(source)
        gpuImage.setFilter(buildFilter(parameters))
        val rendered = gpuImage.bitmapWithFilterApplied
        check(rendered.width > 0 && rendered.height > 0) { "GPUImage returned an empty bitmap." }
        return rendered.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
            applyDocumentEnhancement(bitmap, parameters)
            applyAdvancedAdjustments(bitmap, parameters)
        }
    }

    private fun buildFilter(parameters: ScanParameters): GPUImageFilterGroup {
        val filters = ArrayList<GPUImageFilter>()
        filters += GPUImageBrightnessFilter(parameters.brightness)
        filters += GPUImageExposureFilter(parameters.exposure)
        filters += GPUImageContrastFilter(parameters.contrast)
        filters += GPUImageGammaFilter(parameters.gamma)
        filters += GPUImageSaturationFilter(parameters.saturation)
        filters += GPUImageSharpenFilter(parameters.sharpen)
        if (parameters.grayscale) {
            filters += GPUImageGrayscaleFilter()
        }
        return GPUImageFilterGroup(filters)
    }

    private fun applyDocumentEnhancement(bitmap: Bitmap, parameters: ScanParameters) {
        val paperClean = parameters.paperClean.coerceIn(0f, 1.5f)
        val inkBoost = parameters.inkBoost.coerceIn(0f, 1.5f)
        val documentStrength = max(paperClean * 0.72f, inkBoost * 0.86f)
            .coerceIn(0f, 1f)
        if (documentStrength <= 0.01f) return

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val background = estimateBackground(pixels, width, height)

        for (y in 0 until height) {
            val bgY = (y / background.scale).coerceIn(0, background.height - 1)
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]
                val alpha = Color.alpha(color)
                val r0 = Color.red(color)
                val g0 = Color.green(color)
                val b0 = Color.blue(color)
                val luminance = luminance(r0, g0, b0)
                val bgX = (x / background.scale).coerceIn(0, background.width - 1)
                val localBg = background.values[bgY * background.width + bgX]
                    .coerceIn(32, 255)

                val normalized = (luminance * 238f / localBg.toFloat())
                    .coerceIn(0f, 255f)
                var nextLum = lerp(
                    luminance.toFloat(),
                    normalized,
                    (0.30f + paperClean * 0.46f).coerceIn(0f, 0.86f)
                )

                val localThreshold = (localBg - 10f - inkBoost * 34f + paperClean * 5f)
                    .coerceIn(68f, 226f)
                val localInk = ((localThreshold - luminance) / 58f).coerceIn(0f, 1f)
                val normalizedInk = ((178f - nextLum) / 74f).coerceIn(0f, 1f)
                val inkMask = max(localInk, normalizedInk) *
                    (0.22f + inkBoost * 0.86f).coerceIn(0.22f, 1f)
                val paperMask = ((nextLum - 126f) / 88f).coerceIn(0f, 1f) *
                    (1f - inkMask * 0.82f)

                nextLum = lerp(nextLum, 255f, paperClean.coerceIn(0f, 1f) * paperMask * 0.78f)
                val inkTarget = (18f - inkBoost * 8f).coerceIn(5f, 18f)
                nextLum = lerp(
                    nextLum,
                    inkTarget,
                    inkMask.coerceIn(0f, 1f) * (0.72f + inkBoost.coerceIn(0f, 1f) * 0.18f)
                )
                if (parameters.grayscale && documentStrength > 0.38f) {
                    val binaryAmount = ((documentStrength - 0.38f) / 0.62f).coerceIn(0f, 1f)
                    val adaptiveCutoff = (localBg * 0.78f - inkBoost * 10f + paperClean * 12f)
                        .coerceIn(104f, 205f)
                    val strokeTarget = if (nextLum < adaptiveCutoff || inkMask > 0.42f) 10f else 255f
                    val selectiveAmount = binaryAmount * (0.26f + inkMask.coerceIn(0f, 1f) * 0.38f)
                    nextLum = lerp(nextLum, strokeTarget, selectiveAmount)
                }
                nextLum = nextLum.coerceIn(0f, 255f)

                if (parameters.grayscale) {
                    val gray = nextLum.roundToByte()
                    pixels[index] = Color.argb(alpha, gray, gray, gray)
                } else {
                    val ratio = (nextLum / luminance.coerceAtLeast(8).toFloat())
                        .coerceIn(0.28f, 2.85f)
                    var r = (r0 * ratio).coerceIn(0f, 255f)
                    var g = (g0 * ratio).coerceIn(0f, 255f)
                    var b = (b0 * ratio).coerceIn(0f, 255f)

                    if (paperMask > 0.15f) {
                        val whiten = paperClean.coerceIn(0f, 1f) * paperMask * 0.55f
                        r = lerp(r, nextLum, whiten)
                        g = lerp(g, nextLum, whiten)
                        b = lerp(b, nextLum, whiten)
                    }
                    pixels[index] = Color.argb(
                        alpha,
                        r.roundToByte(),
                        g.roundToByte(),
                        b.roundToByte()
                    )
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun applyAdvancedAdjustments(bitmap: Bitmap, parameters: ScanParameters) {
        val blackPoint = parameters.blackPoint.coerceIn(0f, 0.35f)
        val whitePoint = parameters.whitePoint.coerceIn(0f, 0.35f)
        val shadows = parameters.shadows.coerceIn(-0.5f, 0.5f)
        val highlights = parameters.highlights.coerceIn(-0.5f, 0.5f)
        val temperature = parameters.temperature.coerceIn(-0.35f, 0.35f)
        val tint = parameters.tint.coerceIn(-0.35f, 0.35f)
        val paperClean = parameters.paperClean.coerceIn(0f, 1f)
        val inkBoost = parameters.inkBoost.coerceIn(0f, 1f)
        val needsPost = blackPoint != 0f || whitePoint != 0f ||
            shadows != 0f || highlights != 0f ||
            temperature != 0f || tint != 0f
        if (!needsPost) return

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val denominator = (1f - blackPoint - whitePoint).coerceAtLeast(0.18f)
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = Color.alpha(color)
            var r = Color.red(color) / 255f
            var g = Color.green(color) / 255f
            var b = Color.blue(color) / 255f
            val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)

            r = remapTone(r, luminance, blackPoint, denominator, shadows, highlights, paperClean, inkBoost)
            g = remapTone(g, luminance, blackPoint, denominator, shadows, highlights, paperClean, inkBoost)
            b = remapTone(b, luminance, blackPoint, denominator, shadows, highlights, paperClean, inkBoost)

            r = (r + temperature * 0.10f + tint * 0.035f).coerceIn(0f, 1f)
            g = (g - tint * 0.055f).coerceIn(0f, 1f)
            b = (b - temperature * 0.10f + tint * 0.035f).coerceIn(0f, 1f)

            pixels[index] = Color.argb(
                alpha,
                (r * 255f + 0.5f).toInt().coerceIn(0, 255),
                (g * 255f + 0.5f).toInt().coerceIn(0, 255),
                (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun applyInvert(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val luminance = luminance(Color.red(color), Color.green(color), Color.blue(color))
            val inverted = 255f - luminance
            val inkMask = ((inverted - 74f) / 138f).coerceIn(0f, 1f)
            val paperTone = 8f + inverted * 0.22f
            val textTone = lerp(188f, 255f, inkMask)
            val tone = lerp(paperTone, textTone, inkMask * inkMask * (3f - 2f * inkMask))
                .coerceIn(0f, 255f)
            val value = tone.roundToByte()
            pixels[index] = Color.argb(
                Color.alpha(color),
                value,
                value,
                (value + 4).coerceAtMost(255)
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun renderSoftInvert(source: Bitmap, parameters: ScanParameters): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val background = estimateBackground(pixels, width, height)
        val histogram = IntArray(256)

        for (y in 0 until height) {
            val bgY = (y / background.scale).coerceIn(0, background.height - 1)
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]
                val luminance = luminance(Color.red(color), Color.green(color), Color.blue(color))
                val bgX = (x / background.scale).coerceIn(0, background.width - 1)
                val localBg = background.values[bgY * background.width + bgX].coerceIn(24, 255)
                val detail = ((localBg - luminance).coerceAtLeast(0) * 255 / localBg.coerceAtLeast(16))
                    .coerceIn(0, 255)
                histogram[detail] += 1
            }
        }

        val high = percentileFromHistogram(histogram, 0.995f).coerceAtLeast(42)
        val low = percentileFromHistogram(histogram, 0.010f).coerceAtMost(8)
        val range = (high - low).coerceAtLeast(24)
        val blackLevel = (48f + parameters.brightness * 42f + parameters.exposure * 18f)
            .coerceIn(38f, 68f)
        val whiteLevel = (
            206f +
                (parameters.contrast - 1f) * 18f +
                parameters.highlights * 24f +
                parameters.inkBoost * 8f
            ).coerceIn(188f, 222f)
        val gamma = (0.80f - parameters.inkBoost.coerceIn(0f, 1f) * 0.06f +
            (parameters.gamma - 1f) * 0.18f).coerceIn(0.68f, 0.92f)
        val lut = IntArray(256) { i ->
            val normalized = i / 255f
            val mapped = blackLevel + (whiteLevel - blackLevel) * normalized.pow(gamma)
            mapped.roundToByte()
        }

        val outputPixels = IntArray(pixels.size)
        for (y in 0 until height) {
            val bgY = (y / background.scale).coerceIn(0, background.height - 1)
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]
                val luminance = luminance(Color.red(color), Color.green(color), Color.blue(color))
                val bgX = (x / background.scale).coerceIn(0, background.width - 1)
                val localBg = background.values[bgY * background.width + bgX].coerceIn(24, 255)
                val rawDetail = ((localBg - luminance).coerceAtLeast(0) * 255 / localBg.coerceAtLeast(16))
                    .coerceIn(0, 255)
                val normalizedDetail = (((rawDetail - low).coerceAtLeast(0) * 255) / range)
                    .coerceIn(0, 255)
                val detail = (rawDetail * 0.34f + normalizedDetail * 0.66f)
                    .roundToByte()
                val value = lut[detail]
                outputPixels[index] = Color.argb(
                    Color.alpha(color),
                    value,
                    value,
                    (value + 3).coerceAtMost(255)
                )
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun percentileFromHistogram(histogram: IntArray, percentile: Float): Int {
        val total = histogram.sum().coerceAtLeast(1)
        val target = (total * percentile.coerceIn(0f, 1f)).toInt().coerceIn(0, total - 1)
        var cumulative = 0
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative > target) return i
        }
        return histogram.lastIndex
    }

    private fun estimateBackground(pixels: IntArray, width: Int, height: Int): BackgroundMap {
        val scale = max(1, max(width, height) / 360)
        val smallWidth = (width + scale - 1) / scale
        val smallHeight = (height + scale - 1) / scale
        val sums = IntArray(smallWidth * smallHeight)
        val counts = IntArray(smallWidth * smallHeight)
        for (y in 0 until height) {
            val smallY = y / scale
            for (x in 0 until width) {
                val smallX = x / scale
                val index = smallY * smallWidth + smallX
                val color = pixels[y * width + x]
                sums[index] += luminance(Color.red(color), Color.green(color), Color.blue(color))
                counts[index] += 1
            }
        }
        val small = IntArray(sums.size) { index ->
            if (counts[index] == 0) 255 else sums[index] / counts[index]
        }
        val radius = (max(smallWidth, smallHeight) / 16).coerceIn(10, 28)
        return BackgroundMap(
            values = boxBlurSmall(boxBlurSmall(small, smallWidth, smallHeight, radius), smallWidth, smallHeight, radius / 2),
            width = smallWidth,
            height = smallHeight,
            scale = scale
        )
    }

    private fun boxBlurSmall(values: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0 || width <= 1 || height <= 1) return values.copyOf()
        val horizontal = IntArray(values.size)
        for (y in 0 until height) {
            var sum = 0
            var count = 0
            for (x in -radius..radius) {
                val clamped = x.coerceIn(0, width - 1)
                sum += values[y * width + clamped]
                count += 1
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / count
                val removeX = (x - radius).coerceIn(0, width - 1)
                val addX = (x + radius + 1).coerceIn(0, width - 1)
                sum += values[y * width + addX] - values[y * width + removeX]
            }
        }
        val output = IntArray(values.size)
        for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (y in -radius..radius) {
                val clamped = y.coerceIn(0, height - 1)
                sum += horizontal[clamped * width + x]
                count += 1
            }
            for (y in 0 until height) {
                output[y * width + x] = sum / count
                val removeY = (y - radius).coerceIn(0, height - 1)
                val addY = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[addY * width + x] - horizontal[removeY * width + x]
            }
        }
        return output
    }

    private fun luminance(r: Int, g: Int, b: Int): Int {
        return (0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f)
            .toInt()
            .coerceIn(0, 255)
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float {
        return from + (to - from) * amount.coerceIn(0f, 1f)
    }

    private fun Float.roundToByte(): Int {
        return (this + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun remapTone(
        value: Float,
        luminance: Float,
        blackPoint: Float,
        denominator: Float,
        shadows: Float,
        highlights: Float,
        paperClean: Float,
        inkBoost: Float
    ): Float {
        var next = ((value - blackPoint) / denominator).coerceIn(0f, 1f)
        val shadowWeight = (1f - luminance) * (1f - luminance)
        val highlightWeight = luminance * luminance
        next = (next + shadows * 0.24f * shadowWeight + highlights * 0.20f * highlightWeight)
            .coerceIn(0f, 1f)
        if (paperClean > 0f && luminance > 0.52f) {
            val paperWeight = ((luminance - 0.52f) / 0.48f).coerceIn(0f, 1f)
            next += (1f - next) * paperClean * paperWeight * paperWeight
        }
        if (inkBoost > 0f && luminance < 0.78f) {
            val inkWeight = ((0.78f - luminance) / 0.78f).coerceIn(0f, 1f)
            next *= 1f - inkBoost * 0.42f * inkWeight
        }
        return next.coerceIn(0f, 1f)
    }
}
