package com.scanclone.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.scanclone.model.DocumentPage
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class PdfPageImporter(context: Context) {
    private val contentResolver: ContentResolver = context.contentResolver
    private val importDir = File(context.filesDir, "pdf_imports").apply { mkdirs() }

    fun importPdf(
        uri: Uri,
        maxLongEdge: Int = 2400,
        titlePrefix: String = "PDF"
    ): List<DocumentPage> {
        val importedAt = System.currentTimeMillis()
        val outputDir = File(importDir, importedAt.toString()).apply { mkdirs() }
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法打开 PDF：$uri")

        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "PDF 没有可导入的页面。" }
                return List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val longEdge = max(page.width, page.height).coerceAtLeast(1)
                        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val file = File(outputDir, "page-${index + 1}.jpg")
                            file.outputStream().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                                    "无法写入 PDF 第 ${index + 1} 页。"
                                }
                            }
                            DocumentPage(
                                sourceUri = Uri.fromFile(file),
                                title = "$titlePrefix-${index + 1}"
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }
}
