package com.scanclone.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.scanclone.model.DocumentPage
import com.scanclone.model.PdfExportOptions
import com.scanclone.model.PdfExportResult
import com.scanclone.model.PdfPageSize
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.min

class PdfExporter(context: Context) {
    private val contentResolver: ContentResolver = context.contentResolver
    private val renderer = ScanFilterRenderer(context)

    suspend fun export(
        pages: List<DocumentPage>,
        destination: Uri,
        options: PdfExportOptions = PdfExportOptions(),
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> }
    ): PdfExportResult = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty()) { "At least one page is required to export a PDF." }
        require(options.jpegQuality in 1..100) { "JPEG quality must be in 1..100." }

        val pdf = PdfDocument()
        try {
            renderAndAppendPages(pdf, pages, options, onProgress)

            contentResolver.openOutputStream(destination, "w")?.use { output ->
                pdf.writeTo(output)
            } ?: error("Cannot open PDF destination: $destination")
        } finally {
            pdf.close()
        }

        PdfExportResult(pageCount = pages.size)
    }

    private suspend fun renderAndAppendPages(
        pdf: PdfDocument,
        pages: List<DocumentPage>,
        options: PdfExportOptions,
        onProgress: (completedPages: Int, totalPages: Int) -> Unit
    ) = coroutineScope {
        val renderWindow = exportRenderWindow(pages.size)
        val queue = ArrayDeque<Pair<Int, Deferred<Bitmap>>>()
        var nextIndex = 0

        fun enqueueNext() {
            if (nextIndex >= pages.size) return
            val index = nextIndex
            val page = pages[index]
            queue.addLast(index to async(Dispatchers.IO) {
                renderPageBitmap(page, options)
            })
            nextIndex += 1
        }

        repeat(renderWindow) { enqueueNext() }
        while (queue.isNotEmpty()) {
            val (index, pendingBitmap) = queue.removeFirst()
            val pageBitmap = pendingBitmap.await()
            try {
                appendBitmapPage(pdf, pageBitmap, index + 1, options)
            } finally {
                pageBitmap.recycle()
            }
            onProgress(index + 1, pages.size)
            enqueueNext()
        }
    }

    private fun exportRenderWindow(pageCount: Int): Int {
        if (pageCount <= 1) return 1
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        return min(2, cores).coerceAtMost(pageCount)
    }

    fun measurePagePdfBytes(
        page: DocumentPage,
        options: PdfExportOptions = PdfExportOptions()
    ): Long {
        require(options.jpegQuality in 1..100) { "JPEG quality must be in 1..100." }

        val pdf = PdfDocument()
        try {
            val pageBitmap = renderPageBitmap(page, options)
            try {
                appendBitmapPage(pdf, pageBitmap, 1, options)
            } finally {
                pageBitmap.recycle()
            }
            return writePdfToBytes(pdf)
        } finally {
            pdf.close()
        }
    }

    fun measureBlankPdfBytes(
        pageCount: Int,
        options: PdfExportOptions = PdfExportOptions()
    ): Long {
        require(pageCount > 0) { "pageCount must be positive." }

        val pdf = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val (pageWidth, pageHeight) = pageDimensions(null, options)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    index + 1
                ).create()
                val pdfPage = pdf.startPage(pageInfo)
                pdfPage.canvas.drawColor(Color.WHITE)
                pdf.finishPage(pdfPage)
            }
            return writePdfToBytes(pdf)
        } finally {
            pdf.close()
        }
    }

    private fun renderPageBitmap(page: DocumentPage, options: PdfExportOptions): Bitmap {
        val original = BitmapDecoder.decode(contentResolver, page.sourceUri, options.maxLongEdge)
        val rotated = BitmapDecoder.rotate(original, page.rotationDegrees)
        if (rotated !== original) original.recycle()
        val cropped = BitmapDecoder.crop(rotated, page.crop)
        if (cropped !== rotated) rotated.recycle()
        val filtered = renderer.render(cropped, page.scanParameters)
        if (filtered !== cropped) cropped.recycle()
        return filtered
    }

    private fun appendBitmapPage(
        pdf: PdfDocument,
        pageBitmap: Bitmap,
        pageNumber: Int,
        options: PdfExportOptions
    ) {
        val (pageWidth, pageHeight) = pageDimensions(pageBitmap, options)
        val pageInfo = PdfDocument.PageInfo.Builder(
            pageWidth,
            pageHeight,
            pageNumber
        ).create()
        val pdfPage = pdf.startPage(pageInfo)
        pdfPage.canvas.drawColor(Color.WHITE)
        val dest = if (options.pageSize == PdfPageSize.IMAGE) {
            RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        } else {
            fitCenter(
                pageBitmap.width.toFloat(),
                pageBitmap.height.toFloat(),
                pageWidth.toFloat(),
                pageHeight.toFloat()
            )
        }
        pdfPage.canvas.drawBitmap(pageBitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        pdf.finishPage(pdfPage)
    }

    private fun pageDimensions(bitmap: Bitmap?, options: PdfExportOptions): Pair<Int, Int> {
        return when (options.pageSize) {
            PdfPageSize.IMAGE -> {
                val current = bitmap
                if (current != null) {
                    current.width.coerceAtLeast(1) to current.height.coerceAtLeast(1)
                } else {
                    options.pageWidthPt to options.pageHeightPt
                }
            }
            PdfPageSize.CUSTOM -> options.pageWidthPt.coerceAtLeast(72) to
                options.pageHeightPt.coerceAtLeast(72)
            else -> options.pageSize.widthPt to options.pageSize.heightPt
        }
    }

    private fun writePdfToBytes(pdf: PdfDocument): Long {
        val buffer = java.io.ByteArrayOutputStream()
        pdf.writeTo(buffer)
        return buffer.size().toLong()
    }

    private fun fitCenter(srcWidth: Float, srcHeight: Float, maxWidth: Float, maxHeight: Float): RectF {
        val scale = min(maxWidth / srcWidth, maxHeight / srcHeight)
        val width = srcWidth * scale
        val height = srcHeight * scale
        val left = (maxWidth - width) / 2f
        val top = (maxHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }
}
