package com.scanclone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.R as PictureSelectorR
import com.luck.picture.lib.adapter.PictureImageGridAdapter
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.style.BottomNavBarStyle
import com.luck.picture.lib.style.PictureSelectorStyle
import com.luck.picture.lib.style.SelectMainStyle
import com.luck.picture.lib.style.TitleBarStyle
import com.scanclone.data.BitmapDecoder
import com.scanclone.data.DraftRepository
import com.scanclone.data.DocumentCropDetector
import com.scanclone.data.FilterPresetRepository
import com.scanclone.data.GalleryRepository
import com.scanclone.data.GlideImageEngine
import com.scanclone.data.PdfExporter
import com.scanclone.data.PdfPageImporter
import com.scanclone.data.ScanFilterRenderer
import com.scanclone.model.DocumentPage
import com.scanclone.model.GalleryImage
import com.scanclone.model.NormalizedCrop
import com.scanclone.model.PdfExportOptions
import com.scanclone.model.PdfPageSize
import com.scanclone.model.ScanParameters
import com.scanclone.ui.CropOverlayView
import com.scanclone.ui.GalleryAdapter
import com.scanclone.ui.PageStripAdapter
import com.scanclone.ui.ZoomableImageView
import com.scanclone.ui.dp
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val surface = Color.rgb(247, 248, 250)
    private val card = Color.WHITE
    private val textPrimary = Color.rgb(28, 32, 38)
    private val textSecondary = Color.rgb(105, 113, 126)
    private val googleBlue = Color.rgb(66, 112, 162)
    private val googleGreen = Color.rgb(36, 163, 140)
    private val applyGreen = Color.rgb(1, 194, 96)
    private val border = Color.rgb(231, 235, 241)
    private val mainPreviewLongEdge = 1500
    private val fullPreviewLongEdge = 4096
    private val exportSinglePreviewLongEdge = 4096

    private lateinit var galleryRepository: GalleryRepository
    private lateinit var filterRenderer: ScanFilterRenderer
    private lateinit var pdfExporter: PdfExporter
    private lateinit var draftRepository: DraftRepository
    private lateinit var pdfPageImporter: PdfPageImporter
    private lateinit var filterPresetRepository: FilterPresetRepository

    private val selectedImages = LinkedHashMap<Long, GalleryImage>()
    private val pages = mutableListOf<DocumentPage>()

    private var selectedPageIndex = 0
    private var currentDraftId: String? = null
    private var currentDraftName: String? = null
    private var previewJob: Job? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreviewRunnable: Runnable? = null
    private var previewBitmap: Bitmap? = null
    private var previewRenderGeneration = 0
    private val pageThumbnailCache = linkedMapOf<String, Bitmap>()
    private val pageThumbnailInflight = mutableSetOf<String>()
    private val pagePreviewCache = linkedMapOf<String, Bitmap>()
    private val pagePreviewInflight = mutableSetOf<String>()
    private var previewPrefetchJob: Job? = null
    private var activePageStripAdapter: PageStripAdapter? = null
    private var activePreviewView: ImageView? = null
    private var activePreviewProgress: ProgressBar? = null
    private var deleteSelectionGeneration = 0
    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingExportOptions = PdfExportOptions()
    private var pendingImageExportOptions = PdfExportOptions()
    private var pendingImageFormat = ImageOutputFormat.JPG
    private var pendingSingleImageIndex = 0
    private var pendingPdfPages: List<DocumentPage> = emptyList()
    private var pendingImageExportItems: List<ExportItem> = emptyList()
    private var stripAnchorIndex = 0
    private var stripAnchorOffset = 0
    private val exportBoundsCache = mutableMapOf<String, BitmapDecoder.Bounds>()
    private val exportSampleBytesCache = mutableMapOf<String, Long>()
    private val exportBlankBytesCache = mutableMapOf<String, Long>()
    private val exportSourceBytesCache = mutableMapOf<String, Long?>()
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val pickerRectangleRankPrefix = "scanclone_rect_rank:"
    private val defaultFilterOrderKey = "default_filter_preset_order_v1"

    private data class ExportEstimate(
        val pdfMb: Double,
        val peakMemoryMb: Double,
        val totalPixels: Long,
        val sampledPages: Int
    )

    private data class PageEstimateStats(
        val index: Int,
        val renderedWidth: Int,
        val renderedHeight: Int,
        val renderedPixels: Long,
        val decodedPixels: Long
    )

    private data class EditorSnapshot(
        val pages: List<DocumentPage>,
        val selectedPageIndex: Int,
        val currentDraftId: String?,
        val currentDraftName: String?,
        val filterPresets: List<FilterPresetRepository.FilterPreset>
    )

    private data class BatchUiState(
        val selectedPages: List<Int>,
        val firstGridPosition: Int,
        val firstGridOffset: Int,
        val toolSheetHeight: Int,
        val filterPanelOpen: Boolean
    )

    private data class DefaultFilterPresetSpec(
        val id: String,
        val label: String,
        val onDelete: (() -> Unit)? = null,
        val create: () -> ScanParameters
    )

    private enum class ExportFormat(val label: String) {
        PDF("PDF"),
        JPG("JPG"),
        PNG("PNG")
    }

    private enum class ExportScope(val label: String) {
        ALL("全部页面"),
        CURRENT("当前页面"),
        RANGE("指定页码")
    }

    private data class ExportItem(
        val originalIndex: Int,
        val page: DocumentPage
    )

    private data class ExportProgressUi(
        val dialog: AlertDialog,
        val progressBar: ProgressBar,
        val status: TextView,
        val percent: TextView,
        val detail: TextView
    )

    private enum class ImageOutputFormat(
        val mimeType: String,
        val extension: String,
        val compressFormat: Bitmap.CompressFormat
    ) {
        JPG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
        PNG("image/png", "png", Bitmap.CompressFormat.PNG)
    }

    private inner class BulkPageAdapter(
        private val selected: MutableSet<Int>,
        private val onTapped: (Int) -> Unit,
        private val onPreviewTapped: (Int) -> Unit
    ) : RecyclerView.Adapter<BulkPageHolder>() {
        private val thumbnailCache = linkedMapOf<Long, Bitmap>()
        private val thumbnailJobs = mutableMapOf<Long, Job>()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BulkPageHolder {
            val root = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    78.dp,
                    92.dp
                ).apply {
                    setMargins(4.dp, 5.dp, 4.dp, 7.dp)
                }
                setPadding(2.dp, 2.dp, 2.dp, 2.dp)
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val order = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                includeFontPadding = false
                background = rounded(Color.rgb(36, 163, 140), 99.dp, Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.TOP or Gravity.END).apply {
                    topMargin = 5.dp
                    rightMargin = 5.dp
                }
            }
            val label = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    24.dp,
                    Gravity.BOTTOM
                )
            }
            val preview = ImageButton(parent.context).apply {
                setImageResource(R.drawable.ic_tool_magnifier)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = null
                scaleType = ImageView.ScaleType.CENTER
                setPadding(1.dp, 1.dp, 1.dp, 1.dp)
                layoutParams = FrameLayout.LayoutParams(18.dp, 18.dp, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = 4.dp
                    bottomMargin = 3.dp
                }
            }
            root.addView(image)
            root.addView(label)
            root.addView(order)
            root.addView(preview)
            return BulkPageHolder(root, image, label, order, preview)
        }

        override fun onBindViewHolder(holder: BulkPageHolder, position: Int) {
            val page = pages[position]
            val stableId = page.stableId
            holder.boundStableId = stableId
            val selectedIndex = selected.toList().indexOf(position)
            holder.itemView.background = rounded(
                if (selectedIndex >= 0) Color.rgb(27, 110, 243) else Color.rgb(231, 235, 241),
                12.dp,
                Color.TRANSPARENT
            )
            holder.image.background = rounded(Color.rgb(247, 249, 252), 10.dp, Color.TRANSPARENT)
            holder.image.clipToOutline = true
            holder.image.rotation = 0f
            bindFilteredThumbnail(holder.image, page, 420)
            holder.label.text = (position + 1).toString()
            holder.order.visibility = if (selectedIndex >= 0) View.VISIBLE else View.GONE
            holder.order.text = (selectedIndex + 1).toString()
            holder.itemView.setOnClickListener { onTapped(position) }
            holder.preview.setOnClickListener { onPreviewTapped(position) }
        }

        override fun getItemId(position: Int): Long = pages[position].stableId

        override fun getItemCount(): Int = pages.size

        fun release() {
            thumbnailJobs.values.forEach { it.cancel() }
            thumbnailJobs.clear()
            thumbnailCache.clear()
        }

        private fun loadThumbnail(holder: BulkPageHolder, page: DocumentPage) {
            val stableId = page.stableId
            if (thumbnailJobs.containsKey(stableId)) return
            thumbnailJobs[stableId] = lifecycleScope.launch {
                val decoded = runCatching {
                    withContext(Dispatchers.IO) {
                        BitmapDecoder.decode(contentResolver, page.sourceUri, 260)
                    }
                }.getOrNull()
                thumbnailJobs.remove(stableId)
                val bitmap = decoded ?: return@launch
                thumbnailCache[stableId] = bitmap
                if (holder.boundStableId == stableId) {
                    holder.image.setImageBitmap(bitmap)
                }
            }
        }

    }

    private class BulkPageHolder(
        itemView: View,
        val image: ImageView,
        val label: TextView,
        val order: TextView,
        val preview: ImageButton
    ) : RecyclerView.ViewHolder(itemView) {
        var boundStableId: Long = Long.MIN_VALUE
    }

    private inner class DefaultFilterPresetAdapter(
        val presets: MutableList<DefaultFilterPresetSpec>,
        private val onApply: (ScanParameters) -> Unit,
        private val onOrderChanged: (List<String>) -> Unit,
        private val showDelete: Boolean,
        activePresetId: String?
    ) : RecyclerView.Adapter<DefaultFilterPresetHolder>() {
        var activePresetId: String? = activePresetId
            set(value) {
                if (field == value) return
                val oldIndex = presets.indexOfFirst { it.id == field }
                val nextIndex = presets.indexOfFirst { it.id == value }
                field = value
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                if (nextIndex >= 0) notifyItemChanged(nextIndex)
            }

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DefaultFilterPresetHolder {
            val chip = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = 38.dp
                setPadding(15.dp, 0, 15.dp, 0)
                background = rounded(Color.rgb(241, 246, 252), 15.dp, Color.TRANSPARENT)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    40.dp
                ).apply {
                    setMargins(0, 2.dp, 10.dp, 2.dp)
                }
            }
            val label = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                includeFontPadding = false
                setTextColor(googleBlue)
            }
            val delete = TextView(parent.context).apply {
                text = "×"
                textSize = 17f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(textSecondary)
                setPadding(9.dp, 0, 0, 1.dp)
            }
            chip.addView(label)
            chip.addView(delete)
            return DefaultFilterPresetHolder(chip, label, delete)
        }

        override fun onBindViewHolder(holder: DefaultFilterPresetHolder, position: Int) {
            val preset = presets[position]
            val selected = preset.id == activePresetId
            holder.itemView.background = rounded(
                if (selected) googleBlue else Color.rgb(241, 246, 252),
                15.dp,
                Color.TRANSPARENT
            )
            holder.label.text = preset.label
            holder.label.setTextColor(if (selected) Color.WHITE else googleBlue)
            holder.delete.visibility = if (showDelete && preset.onDelete != null) View.VISIBLE else View.GONE
            holder.delete.setTextColor(if (selected) Color.argb(220, 255, 255, 255) else textSecondary)
            holder.delete.setOnClickListener { preset.onDelete?.invoke() }
            holder.itemView.setOnClickListener {
                activePresetId = preset.id
                onApply(preset.create())
            }
        }

        override fun getItemCount(): Int = presets.size

        override fun getItemId(position: Int): Long = presets[position].id.hashCode().toLong()

        fun move(from: Int, to: Int): Boolean {
            if (from !in presets.indices || to !in presets.indices || from == to) return false
            val item = presets.removeAt(from)
            presets.add(to, item)
            notifyItemMoved(from, to)
            onOrderChanged(presets.map { it.id })
            return true
        }
    }

    private class DefaultFilterPresetHolder(
        itemView: View,
        val label: TextView,
        val delete: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) next?.invoke() ?: showHome()
        else showPermissionBlocked()
    }

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val snapshot = pendingPdfPages.ifEmpty { pages.toList() }
            exportPdf(uri, pendingExportOptions, snapshot)
        }
    }

    private val createJpgLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        if (uri != null) exportSingleImage(
            uri,
            pendingSingleImageIndex,
            pendingImageExportOptions,
            ImageOutputFormat.JPG
        )
    }

    private val createPngLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) exportSingleImage(
            uri,
            pendingSingleImageIndex,
            pendingImageExportOptions,
            ImageOutputFormat.PNG
        )
    }

    private val exportImagesDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val snapshot = pendingImageExportItems.ifEmpty {
                pages.mapIndexed { index, page -> ExportItem(index, page) }
            }
            exportAllImages(uri, pendingImageExportOptions, pendingImageFormat, snapshot)
        }
    }

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importPdf(uri)
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val scannedPages = scanResult?.pages.orEmpty()
        if (scannedPages.isEmpty()) {
            showError(IllegalStateException("拍照扫描没有返回图片。"))
            return@registerForActivityResult
        }
        rememberUndo("拍照扫描")
        scannedPages.forEachIndexed { index, page ->
            pages += DocumentPage(
                sourceUri = page.imageUri,
                title = "拍照扫描-${pages.size + index + 1}"
            )
        }
        selectedPageIndex = pages.lastIndex.coerceAtLeast(0)
        exportBoundsCache.clear()
        invalidateExportEstimateCache()
        anchorStripNearSelected()
        showEditor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = surface
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        galleryRepository = GalleryRepository(contentResolver)
        filterRenderer = ScanFilterRenderer(this)
        pdfExporter = PdfExporter(this)
        draftRepository = DraftRepository(this)
        pdfPageImporter = PdfPageImporter(this)
        filterPresetRepository = FilterPresetRepository(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pages.isNotEmpty()) confirmExitProject() else finish()
            }
        })
        showHome()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
        previewBitmap?.recycle()
        super.onDestroy()
    }

    private fun showHome() {
        selectedImages.clear()
        activePageStripAdapter = null
        activePreviewView = null
        activePreviewProgress = null
        val root = baseColumn().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22.dp, safeTopPadding() + 24.dp, 22.dp, 64.dp)
        }
        root.addView(homeWordmark())
        root.addView(TextView(this).apply {
            text = "把相册图片或PDF整理成清晰扫描件\n支持批量裁剪、滤镜、排序和导出"
            textSize = 14f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            setLineSpacing(3.dp.toFloat(), 1f)
            setPadding(4.dp, 10.dp, 4.dp, 28.dp)
        })
        root.addView(primaryButton("从相册选择", "选择已有照片，支持长按拖动框选") {
            openPictureSelector()
        })
        root.addView(primaryButton("导入 PDF", "把 PDF 拆成页面后继续裁剪、旋转、调滤镜") {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        })
        root.addView(primaryButton("拍照扫描", "调用谷歌扫描，实时拍照编辑") {
            startMlKitScanner()
        })
        if (pages.isNotEmpty()) {
            root.addView(primaryButton("继续编辑 ${pages.size} 页", "回到当前文档") {
                showEditor()
            })
        }
        addDraftList(root)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(surface)
            addView(root)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(surface)
            addView(scroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(TextView(this@MainActivity).apply {
                text = "by Chaotic Panda"
                textSize = 13f
                setTextColor(Color.rgb(132, 140, 152))
                typeface = Typeface.create("cursive", Typeface.ITALIC)
                includeFontPadding = false
                alpha = 0.78f
                gravity = Gravity.END
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = 20.dp
                bottomMargin = 18.dp
            })
        })
    }

    private fun addDraftList(root: LinearLayout) {
        val drafts = draftRepository.listDrafts()
        if (drafts.isEmpty()) return

        root.addView(TextView(this).apply {
            text = "草稿"
            textSize = 17f
            setTextColor(textPrimary)
            includeFontPadding = false
            setPadding(2.dp, 10.dp, 0, 10.dp)
        })
        drafts.forEach { draft ->
            root.addView(draftRow(draft))
        }
    }

    private fun draftRow(draft: DraftRepository.DraftSummary): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 78.dp
            setPadding(16.dp, 12.dp, 12.dp, 12.dp)
            background = rounded(card, 18.dp, border)
            elevation = 1.dp.toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = draft.name
                    textSize = 16f
                    setTextColor(textPrimary)
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "${draft.pageCount} 页 · ${formatDraftTime(draft.updatedAt)}"
                    textSize = 12f
                    setTextColor(textSecondary)
                    setPadding(0, 6.dp, 0, 0)
                })
            })
            addView(compactButton("打开") { openDraft(draft) }, LinearLayout.LayoutParams(
                52.dp,
                34.dp
            ).apply {
                marginStart = 10.dp
                marginEnd = 8.dp
            })
            addView(compactButton("删除") { confirmDeleteDraft(draft) }, LinearLayout.LayoutParams(
                52.dp,
                34.dp
            ).apply {
                marginStart = 2.dp
            })
        }
    }

    private fun formatDraftTime(updatedAt: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updatedAt))
    }

    private fun confirmExitProject() {
        if (pages.isEmpty()) {
            showHome()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出当前项目？")
            .setMessage("可以把当前 ${pages.size} 页保存为草稿，稍后从首页继续编辑。")
            .setPositiveButton("保存草稿") { _, _ ->
                showSaveDraftDialog {
                    clearCurrentProject()
                    showHome()
                }
            }
            .setNegativeButton("不保存") { _, _ ->
                clearCurrentProject()
                showHome()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun showSaveDraftDialog(afterSave: () -> Unit = {}) {
        if (pages.isEmpty()) {
            Toast.makeText(this, "当前没有可保存的页面。", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(currentDraftName ?: "扫描草稿 ${formatDraftTime(System.currentTimeMillis())}")
            selectAll()
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        AlertDialog.Builder(this)
            .setTitle("保存草稿")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val summary = draftRepository.saveDraft(
                    name = input.text.toString().trim().ifBlank { "未命名草稿" },
                    pages = pages.toList(),
                    draftId = currentDraftId
                )
                currentDraftId = summary.id
                currentDraftName = summary.name
                Toast.makeText(this, "草稿已保存：${summary.name}", Toast.LENGTH_SHORT).show()
                afterSave()
            }
            .show()
    }

    private fun openDraft(draft: DraftRepository.DraftSummary) {
        val openAction = {
            runCatching { draftRepository.loadDraft(draft.id) }
                .onSuccess { loaded ->
                    pages.clear()
                    pages.addAll(loaded.pages)
                    selectedPageIndex = 0
                    currentDraftId = loaded.summary.id
                    currentDraftName = loaded.summary.name
                    anchorStripNearSelected()
                    showEditor()
                }
                .onFailure(::showError)
        }
        if (pages.isEmpty()) {
            openAction()
        } else {
            AlertDialog.Builder(this)
                .setTitle("打开草稿？")
                .setMessage("当前项目会被这个草稿替换。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开") { _, _ -> openAction() }
                .show()
        }
    }

    private fun confirmDeleteDraft(draft: DraftRepository.DraftSummary) {
        AlertDialog.Builder(this)
            .setTitle("删除草稿？")
            .setMessage("“${draft.name}” 删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                draftRepository.deleteDraft(draft.id)
                if (currentDraftId == draft.id) {
                    currentDraftId = null
                    currentDraftName = null
                }
                Toast.makeText(this, "草稿已删除。", Toast.LENGTH_SHORT).show()
                showHome()
            }
            .show()
    }

    private fun clearCurrentProject() {
        pages.clear()
        selectedImages.clear()
        selectedPageIndex = 0
        currentDraftId = null
        currentDraftName = null
        undoStack.clear()
        exportBoundsCache.clear()
        exportSampleBytesCache.clear()
        exportBlankBytesCache.clear()
        exportSourceBytesCache.clear()
    }

    private fun rememberUndo(label: String) {
        if (pages.isEmpty()) return
        undoStack.addLast(
            EditorSnapshot(
                pages = clonePages(pages),
                selectedPageIndex = selectedPageIndex,
                currentDraftId = currentDraftId,
                currentDraftName = currentDraftName,
                filterPresets = cloneFilterPresets(filterPresetRepository.listPresets())
            )
        )
        while (undoStack.size > 80) {
            undoStack.removeFirst()
        }
    }

    private fun undoLastEdit() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "没有可撤回的操作。", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = undoStack.removeLast()
        pages.clear()
        pages.addAll(clonePages(snapshot.pages))
        selectedPageIndex = snapshot.selectedPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        currentDraftId = snapshot.currentDraftId
        currentDraftName = snapshot.currentDraftName
        filterPresetRepository.replacePresets(snapshot.filterPresets)
        exportBoundsCache.clear()
        exportSampleBytesCache.clear()
        exportBlankBytesCache.clear()
        pageThumbnailCache.clear()
        clearPreviewCache()
        anchorStripNearSelected()
        showEditor()
    }

    private fun clonePages(source: List<DocumentPage>): List<DocumentPage> {
        return source.map { page ->
            page.copy(
                crop = page.crop,
                scanParameters = page.scanParameters.copy()
            )
        }
    }

    private fun cloneFilterPresets(
        source: List<FilterPresetRepository.FilterPreset>
    ): List<FilterPresetRepository.FilterPreset> {
        return source.map { preset ->
            preset.copy(parameters = preset.parameters.copy())
        }
    }

    private fun invalidateExportEstimateCache(clearThumbnails: Boolean = true) {
        exportSampleBytesCache.clear()
        if (clearThumbnails) {
            pageThumbnailCache.clear()
            pageThumbnailInflight.clear()
            clearPreviewCache()
        }
    }

    private fun invalidatePageThumbnailCache(stableIds: Collection<Long>) {
        if (stableIds.isEmpty()) return
        val prefixes = stableIds.map { "$it|" }
        pageThumbnailCache.keys
            .filter { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
            .forEach(pageThumbnailCache::remove)
        pageThumbnailInflight
            .filter { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
            .forEach(pageThumbnailInflight::remove)
        removePreviewCacheByPrefixes(prefixes)
    }

    private fun clearPreviewCache() {
        previewPrefetchJob?.cancel()
        previewPrefetchJob = null
        pagePreviewInflight.clear()
        pagePreviewCache.values.forEach { if (!it.isRecycled) it.recycle() }
        pagePreviewCache.clear()
    }

    private fun thumbnailKey(page: DocumentPage, maxLongEdge: Int): String {
        return listOf(
            page.stableId,
            maxLongEdge,
            page.rotationDegrees,
            page.crop,
            page.scanParameters
        ).joinToString("|")
    }

    private fun previewKey(page: DocumentPage, maxLongEdge: Int): String {
        return "preview|${thumbnailKey(page, maxLongEdge)}"
    }

    private fun cachedPreviewCopy(page: DocumentPage, maxLongEdge: Int = mainPreviewLongEdge): Bitmap? {
        val key = previewKey(page, maxLongEdge)
        val cached = pagePreviewCache[key]?.takeIf { !it.isRecycled } ?: return null
        return cached.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun rememberPreviewBitmap(key: String, bitmap: Bitmap) {
        pagePreviewCache.remove(key)?.takeIf { !it.isRecycled }?.recycle()
        pagePreviewCache[key] = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        while (pagePreviewCache.size > 5) {
            val firstKey = pagePreviewCache.keys.firstOrNull() ?: break
            pagePreviewCache.remove(firstKey)?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun removePreviewCacheByPrefixes(prefixes: List<String>) {
        pagePreviewCache.keys
            .filter { key -> prefixes.any { prefix -> key.startsWith("preview|$prefix") } }
            .forEach { key -> pagePreviewCache.remove(key)?.takeIf { !it.isRecycled }?.recycle() }
        pagePreviewInflight
            .filter { key -> prefixes.any { prefix -> key.startsWith("preview|$prefix") } }
            .forEach(pagePreviewInflight::remove)
    }

    private fun cachedThumbnailBitmap(page: DocumentPage): Bitmap? {
        val prefix = "${page.stableId}|"
        return pageThumbnailCache.entries
            .lastOrNull { (key, bitmap) -> key.startsWith(prefix) && !bitmap.isRecycled }
            ?.value
    }

    private fun cachedThumbnailCopy(page: DocumentPage): Bitmap? {
        val cached = cachedThumbnailBitmap(page) ?: return null
        return cached.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun bindFilteredThumbnail(imageView: ImageView, page: DocumentPage, maxLongEdge: Int) {
        val snapshot = page.copy(
            crop = page.crop,
            scanParameters = page.scanParameters.copy()
        )
        val key = thumbnailKey(snapshot, maxLongEdge)
        imageView.tag = key
        imageView.rotation = 0f
        pageThumbnailCache[key]?.takeIf { !it.isRecycled }?.let { cached ->
            imageView.alpha = 1f
            imageView.setImageBitmap(cached)
            return
        }
        val fallback = cachedThumbnailBitmap(snapshot)
        if (fallback != null) {
            imageView.alpha = 0.78f
            imageView.setImageBitmap(fallback)
        } else {
            imageView.setImageDrawable(null)
            imageView.alpha = 0.55f
        }
        if (!pageThumbnailInflight.add(key)) return
        lifecycleScope.launch {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    renderPageBitmap(snapshot, maxLongEdge, true)
                }
            }.getOrNull() ?: run {
                pageThumbnailInflight.remove(key)
                if (imageView.tag == key) imageView.alpha = 1f
                return@launch
            }
            pageThumbnailInflight.remove(key)
            pageThumbnailCache[key] = bitmap
            while (pageThumbnailCache.size > 120) {
                val firstKey = pageThumbnailCache.keys.firstOrNull() ?: break
                pageThumbnailCache.remove(firstKey)
            }
            if (imageView.tag == key) {
                imageView.setImageBitmap(bitmap)
                imageView.animate().alpha(1f).setDuration(140L).start()
            }
        }
    }

    private fun showDragGallery(insertIndex: Int = pages.size) {
        selectedImages.clear()
        val root = baseColumn()
        val status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(32, 38, 46))
            text = "已选择 0 张"
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                56.dp
            )
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, safeTopPadding(), 8.dp, 0)
            addView(smallButton("返回") { showHome() })
            addView(TextView(this@MainActivity).apply {
                text = "从相册选择"
                textSize = 18f
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(status)
            addView(smallButton("完成") { importSelectedImages(insertIndex) })
        }
        val hint = TextView(this).apply {
            text = "点按单选；长按一张图后拖动批量选择。普通上下滑动不会触发批选。"
            textSize = 13f
            setTextColor(Color.rgb(88, 97, 110))
            setPadding(12.dp, 4.dp, 12.dp, 8.dp)
        }
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        lateinit var adapter: GalleryAdapter
        adapter = GalleryAdapter { image ->
            toggleImageSelection(image)
            updateGallerySelection(adapter, status)
        }
        recycler.adapter = adapter
        attachLongPressDragSelection(recycler, adapter, status)

        root.addView(toolbar)
        root.addView(hint)
        root.addView(recycler)
        setContentView(root)

        lifecycleScope.launch {
            runCatching { galleryRepository.loadImages() }
                .onSuccess { images ->
                    adapter.images = images
                    updateGallerySelection(adapter, status)
                }
                .onFailure(::showError)
        }
    }

    private fun showPermissionBlocked() {
        val root = baseColumn().apply {
            gravity = Gravity.CENTER
            setPadding(24.dp, safeTopPadding(), 24.dp, 24.dp)
        }
        root.addView(TextView(this).apply {
            text = "需要相册权限，才能使用拖动批量选择。"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 38, 46))
        })
        root.addView(primaryButton("重新授权", "打开系统权限申请") {
            openPictureSelector()
        })
        root.addView(primaryButton("返回首页", "回到首页重新选择导入方式") {
            showHome()
        })
        setContentView(root)
    }

    private fun showEditor() {
        if (pages.isEmpty()) {
            showHome()
            return
        }
        selectedPageIndex = selectedPageIndex.coerceIn(0, pages.lastIndex)

        val root = baseColumn()
        val title = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(78.dp, 56.dp)
            text = pageTitle()
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, safeTopPadding() + 8.dp, 12.dp, 8.dp)
            setBackgroundColor(surface)
            addView(smallButton("首页") { confirmExitProject() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 10.dp
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(title, LinearLayout.LayoutParams(78.dp, 56.dp).apply {
                marginEnd = 18.dp
            })
            addView(iconButton(R.drawable.ic_tool_add_photo, "加图") {
                openPictureSelector(selectedPageIndex + 1)
            }, LinearLayout.LayoutParams(54.dp, 42.dp).apply {
                marginEnd = 8.dp
            })
            addView(smallButton("导出") {
                showExportOptions()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val previewFrame = FrameLayout(this).apply {
            background = rounded(card, 18.dp, border)
            elevation = 1.dp.toFloat()
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                leftMargin = 14.dp
                rightMargin = 14.dp
                bottomMargin = 6.dp
            }
        }
        val preview = ZoomableImageView(this).apply {
            adjustViewBounds = true
            background = rounded(Color.rgb(242, 244, 248), 16.dp, Color.TRANSPARENT)
            setOnClickListener { showFullPreview() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val incomingPreview = ZoomableImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            background = rounded(Color.rgb(242, 244, 248), 16.dp, Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progress = modernProgress().apply {
            visibility = View.GONE
        }
        activePreviewView = preview
        activePreviewProgress = progress
        previewFrame.addView(incomingPreview)
        previewFrame.addView(preview)
        previewFrame.addView(progress)

        lateinit var strip: RecyclerView
        lateinit var controls: LinearLayout
        lateinit var rebuildParameterPanel: () -> Unit
        lateinit var stripAdapter: PageStripAdapter
        var lowerScroll: ScrollView? = null
        var incomingRenderJob: Job? = null
        var incomingTargetIndex = RecyclerView.NO_POSITION
        var incomingTargetStableId = Long.MIN_VALUE
        var incomingBitmap: Bitmap? = null
        var incomingBitmapIsFullQuality = false

        fun clearIncomingPreview(recycleBitmap: Boolean = true) {
            incomingRenderJob?.cancel()
            incomingRenderJob = null
            incomingPreview.animate().cancel()
            incomingPreview.tag = null
            incomingPreview.setImageBitmap(null)
            incomingPreview.visibility = View.GONE
            incomingPreview.translationX = 0f
            incomingPreview.alpha = 1f
            if (recycleBitmap) incomingBitmap?.recycle()
            incomingBitmap = null
            incomingBitmapIsFullQuality = false
            incomingTargetIndex = RecyclerView.NO_POSITION
            incomingTargetStableId = Long.MIN_VALUE
        }

        fun prepareIncomingPreview(targetIndex: Int, direction: Int) {
            if (targetIndex !in pages.indices) return
            val width = previewFrame.width.takeIf { it > 0 } ?: preview.width
            incomingPreview.visibility = View.VISIBLE
            incomingPreview.translationX = direction * width.toFloat()
            incomingPreview.alpha = 1f
            if (incomingTargetIndex == targetIndex && incomingBitmap != null) return
            clearIncomingPreview(recycleBitmap = true)
            incomingPreview.visibility = View.VISIBLE
            incomingPreview.translationX = direction * width.toFloat()
            val page = pages[targetIndex]
            bindFilteredThumbnail(incomingPreview, page, 720)
            val snapshot = page.copy(
                crop = page.crop,
                scanParameters = page.scanParameters.copy()
            )
            incomingTargetIndex = targetIndex
            incomingTargetStableId = page.stableId
            cachedPreviewCopy(snapshot)?.let { cached ->
                incomingBitmap?.recycle()
                incomingBitmap = cached
                incomingBitmapIsFullQuality = true
                incomingPreview.tag = "incoming:${incomingTargetStableId}:${mainPreviewLongEdge}:cached"
                incomingPreview.setImageBitmap(cached)
                return
            }
            incomingRenderJob = lifecycleScope.launch {
                val key = previewKey(snapshot, mainPreviewLongEdge)
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderPageBitmap(snapshot, mainPreviewLongEdge, true)
                    }
                }.onSuccess { renderedBitmap ->
                    val stillTarget = incomingTargetIndex == targetIndex &&
                        pages.getOrNull(targetIndex)?.stableId == incomingTargetStableId
                    if (!isActive || !stillTarget) {
                        renderedBitmap.recycle()
                        return@onSuccess
                    }
                    rememberPreviewBitmap(key, renderedBitmap)
                    incomingBitmap?.recycle()
                    incomingBitmap = renderedBitmap
                    incomingBitmapIsFullQuality = true
                    incomingPreview.tag = "incoming:${incomingTargetStableId}:${mainPreviewLongEdge}"
                    incomingPreview.setImageBitmap(renderedBitmap)
                    incomingPreview.alpha = 1f
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    showError(error)
                }
            }
        }

        fun takeIncomingBitmap(targetIndex: Int): Pair<Bitmap?, Boolean> {
            if (incomingTargetIndex != targetIndex) return null to false
            val bitmap = incomingBitmap
                ?: incomingPreview.bitmapCopyForTransition()
                ?: return null to false
            val fullQuality = incomingBitmap != null && incomingBitmapIsFullQuality
            incomingRenderJob?.cancel()
            incomingRenderJob = null
            incomingBitmap = null
            incomingBitmapIsFullQuality = false
            incomingPreview.setImageBitmap(null)
            return bitmap to fullQuality
        }

        fun selectPage(index: Int, animateDirection: Int = 0, readyBitmap: Bitmap? = null) {
            val target = index.coerceIn(0, pages.lastIndex)
            if (target == selectedPageIndex) return
            rememberStripPosition(strip)
            selectedPageIndex = target
            title.text = pageTitle()
            stripAdapter.selectedIndex = selectedPageIndex
            rebuildParameterPanel()
            if (readyBitmap != null) {
                previewJob?.cancel()
                pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
                pendingPreviewRunnable = null
                previewBitmap?.recycle()
                previewBitmap = readyBitmap
                preview.setImageBitmap(readyBitmap)
                preview.translationX = 0f
                preview.alpha = 1f
                progress.animate().cancel()
                progress.visibility = View.GONE
                return
            }
            if (animateDirection == 0) {
                refreshPreview(preview, progress, maxLongEdge = mainPreviewLongEdge)
                return
            }
            val outX = (-animateDirection * 26).dp.toFloat()
            val inX = (animateDirection * 26).dp.toFloat()
            preview.animate().cancel()
            preview.animate()
                .translationX(outX)
                .alpha(0.55f)
                .setDuration(70L)
                .withEndAction {
                    refreshPreview(preview, progress, maxLongEdge = mainPreviewLongEdge)
                    preview.translationX = inX
                    preview.alpha = 0.55f
                    preview.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(150L)
                        .start()
                }
                .start()
        }

        fun deleteCurrentPageInEditor() {
            if (pages.isEmpty()) return
            val removedIndex = selectedPageIndex
            rememberUndo("删除图片")
            pages.removeAt(removedIndex)
            exportBoundsCache.clear()
            invalidateExportEstimateCache()
            if (pages.isEmpty()) {
                showHome()
                return
            }
            selectedPageIndex = removedIndex.coerceAtMost(pages.lastIndex).coerceAtLeast(0)
            title.text = pageTitle()
            val quickBitmap = pages.getOrNull(selectedPageIndex)?.let { nextPage ->
                cachedPreviewCopy(nextPage) ?: cachedThumbnailCopy(nextPage)
            }
            val deleteGeneration = ++deleteSelectionGeneration
            stripAdapter.selectedIndex = RecyclerView.NO_POSITION
            stripAdapter.notifyItemRemoved(removedIndex)
            stripAdapter.notifyItemRangeChanged(
                removedIndex,
                (pages.size + 1 - removedIndex).coerceAtLeast(0)
            )
            previewHandler.postDelayed({
                if (deleteGeneration == deleteSelectionGeneration && pages.isNotEmpty()) {
                    stripAdapter.selectedIndex = selectedPageIndex.coerceIn(0, pages.lastIndex)
                }
            }, 90L)
            rebuildParameterPanel()
            val incomingDirection = if (removedIndex <= pages.lastIndex) 1 else -1
            preview.animate().cancel()
            preview.animate()
                .translationX((-incomingDirection * 20).dp.toFloat())
                .alpha(0.42f)
                .setDuration(80L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
                .withEndAction {
                    if (quickBitmap != null) {
                        previewJob?.cancel()
                        pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
                        pendingPreviewRunnable = null
                        previewBitmap?.recycle()
                        previewBitmap = quickBitmap
                        preview.setImageBitmap(quickBitmap)
                        progress.animate().cancel()
                        progress.visibility = View.GONE
                        schedulePreview(preview, progress, preserveZoom = false, maxLongEdge = mainPreviewLongEdge)
                    } else {
                        refreshPreview(preview, progress, preserveZoom = false, maxLongEdge = mainPreviewLongEdge)
                    }
                    preview.translationX = (incomingDirection * 22).dp.toFloat()
                    preview.alpha = 0.58f
                    preview.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(170L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
                        .start()
                }
                .start()
        }

        stripAdapter = PageStripAdapter(
            pages = pages,
            onPageTapped = { index ->
                if (index == selectedPageIndex) {
                    showFullPreview()
                } else {
                    selectPage(index)
                }
            },
            onAddTapped = {
                rememberStripPosition(strip)
                openPictureSelector(pages.size)
            },
            onBindPageThumbnail = { imageView, page, _ ->
                bindFilteredThumbnail(imageView, page, 360)
            }
        )
        stripAdapter.selectedIndex = selectedPageIndex
        activePageStripAdapter = stripAdapter
        attachPreviewSwipe(
            preview = preview,
            incomingPreview = incomingPreview,
            targetForDirection = { direction ->
                (selectedPageIndex + direction).takeIf { it in pages.indices }
            },
            onPrepareTarget = { targetIndex, direction ->
                prepareIncomingPreview(targetIndex, direction)
            },
            onCommitTarget = { targetIndex ->
                val (bitmap, fullQuality) = takeIncomingBitmap(targetIndex)
                selectPage(
                    index = targetIndex,
                    animateDirection = 0,
                    readyBitmap = bitmap
                )
                if (!fullQuality) {
                    refreshPreview(preview, progress, maxLongEdge = mainPreviewLongEdge)
                }
                clearIncomingPreview(recycleBitmap = false)
            },
            onCancel = {
                clearIncomingPreview(recycleBitmap = true)
            }
        )
        strip = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = stripAdapter
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                removeDuration = 135L
                moveDuration = 185L
                addDuration = 130L
            }
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            setBackgroundColor(surface)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                84.dp
            )
        }
        attachPageReorder(strip, stripAdapter) { movedIndex, selectedStableId ->
            val retainedIndex = selectedStableId
                ?.let { stableId -> pages.indexOfFirst { it.stableId == stableId } }
            selectedPageIndex = if (retainedIndex != null && retainedIndex >= 0) {
                retainedIndex
            } else {
                movedIndex.coerceIn(0, pages.lastIndex)
            }
            stripAdapter.selectedIndex = selectedPageIndex
            refreshPreview(preview, progress, maxLongEdge = mainPreviewLongEdge)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2.dp, 0, 2.dp)
            background = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 14.dp
                rightMargin = 14.dp
                topMargin = 4.dp
                bottomMargin = 8.dp
            }
            addView(scrollRow(
                iconButton(R.drawable.ic_tool_undo, "撤回") { undoLastEdit() },
                iconButton(R.drawable.ic_tool_crop, "裁剪") { showCropEditor() },
                iconButton(R.drawable.ic_tool_rotate_left, "左转") {
                    rotateCurrentPage(-90, preview, progress)
                    stripAdapter.notifyItemChanged(selectedPageIndex)
                },
                iconButton(R.drawable.ic_tool_rotate_right, "右转") {
                    rotateCurrentPage(90, preview, progress)
                    stripAdapter.notifyItemChanged(selectedPageIndex)
                },
                iconButton(R.drawable.ic_tool_more, "更多") { showMorePageActions() },
                iconButton(R.drawable.ic_tool_delete, "删除") {
                    rememberStripPosition(strip)
                    deleteCurrentPageInEditor()
                }
            ))
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 15.dp, border)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 14.dp
                rightMargin = 14.dp
                bottomMargin = 12.dp
            }
        }
        rebuildParameterPanel = {
            val scrollY = lowerScroll?.scrollY ?: 0
            controls.removeAllViews()
            controls.addView(parameterPanel(preview, progress, rebuildParameterPanel) {
                stripAdapter.notifyItemChanged(selectedPageIndex)
            })
            lowerScroll?.post {
                lowerScroll?.scrollTo(0, scrollY)
                lowerScroll?.postOnAnimation {
                    lowerScroll?.scrollTo(0, scrollY)
                }
            }
        }
        rebuildParameterPanel()
        val lowerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip)
            addView(actions)
            addView(controls)
        }
        lowerScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.62f
            )
            addView(lowerContent)
        }

        root.addView(toolbar)
        root.addView(previewFrame)
        root.addView(requireNotNull(lowerScroll))
        setContentView(root)
        strip.post {
            val layoutManager = strip.layoutManager as? LinearLayoutManager
            val target = stripAnchorIndex.coerceIn(0, pages.lastIndex)
            layoutManager?.scrollToPositionWithOffset(target, stripAnchorOffset)
        }
        refreshPreview(preview, progress, maxLongEdge = mainPreviewLongEdge)
    }

    private fun defaultFilterPresets(): List<DefaultFilterPresetSpec> {
        val presets = mutableListOf(
            DefaultFilterPresetSpec("original", "原图") { ScanParameters.original() },
            DefaultFilterPresetSpec("inkmax", "黑白扫描") { ScanParameters.inkMaxScan() },
            DefaultFilterPresetSpec("sharp", "增强锐化") { ScanParameters.sharpenedNotes() },
            DefaultFilterPresetSpec("natural", "原色增强") { ScanParameters.natural() },
            DefaultFilterPresetSpec("notes", "笔记彩色") { ScanParameters.bluePenNotes() },
            DefaultFilterPresetSpec("invert", "反色") { ScanParameters.invertedDocument() }
        )
        return presets
    }

    private fun orderedFilterPresets(
        refreshPanel: (() -> Unit)? = null
    ): MutableList<DefaultFilterPresetSpec> {
        val savedPresets = filterPresetRepository.listPresets()
        val all = defaultFilterPresets() + savedPresets.map { preset ->
            DefaultFilterPresetSpec(
                id = "saved:${preset.id}",
                label = preset.name,
                create = { preset.parameters.copy() },
                onDelete = { confirmDeleteFilterPreset(preset, refreshPanel) }
            )
        }
        val byId = all.associateBy { it.id }
        val stored = getSharedPreferences("scanclone_ui", Context.MODE_PRIVATE)
            .getString(defaultFilterOrderKey, null)
            ?.split(',')
            .orEmpty()
            .filter { it in byId }
        val orderedIds = (stored + all.map { it.id }).distinct()
        return orderedIds.mapNotNull(byId::get).toMutableList()
    }

    private fun saveFilterPresetOrder(ids: List<String>) {
        getSharedPreferences("scanclone_ui", Context.MODE_PRIVATE)
            .edit()
            .putString(defaultFilterOrderKey, ids.joinToString(","))
            .apply()
        val saved = filterPresetRepository.listPresets()
        val bySavedId = saved.associateBy { "saved:${it.id}" }
        val orderedSaved = (ids.mapNotNull(bySavedId::get) +
            saved.filter { "saved:${it.id}" !in ids }).distinctBy { it.id }
        if (orderedSaved.map { it.id } != saved.map { it.id }) {
            filterPresetRepository.replacePresets(orderedSaved)
        }
    }

    private fun matchingFilterPresetId(parameters: ScanParameters): String? {
        return orderedFilterPresets(null)
            .firstOrNull { preset -> preset.create() == parameters }
            ?.id
    }

    private fun defaultFilterPresetRow(
        onApply: (ScanParameters) -> Unit,
        refreshPanel: (() -> Unit)? = null,
        showDelete: Boolean = true,
        allowReorder: Boolean = true,
        activePresetId: String? = null,
        onAdapterReady: ((DefaultFilterPresetAdapter) -> Unit)? = null
    ): RecyclerView {
        val adapter = DefaultFilterPresetAdapter(
            presets = orderedFilterPresets(refreshPanel),
            onApply = onApply,
            onOrderChanged = ::saveFilterPresetOrder,
            showDelete = showDelete,
            activePresetId = activePresetId
        )
        onAdapterReady?.invoke(adapter)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            setPadding(0, 2.dp, 0, 4.dp)
            clipToPadding = false
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp
            )
        }
        if (allowReorder) {
            val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return adapter.move(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition
                    )
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = true
            })
            touchHelper.attachToRecyclerView(recycler)
        }
        return recycler
    }

    private fun parameterPanel(
        preview: ImageView,
        progress: ProgressBar,
        refreshPanel: (() -> Unit)? = null,
        refreshThumbnails: (() -> Unit)? = null
    ): LinearLayout {
        val page = pages[selectedPageIndex]
        val parameters = page.scanParameters
        var presetAdapter: DefaultFilterPresetAdapter? = null
        var grayscaleCheckBox: CheckBox? = null
        var invertCheckBox: CheckBox? = null
        var syncingFilterToggles = false
        val sliderRefreshers = mutableListOf<() -> Unit>()

        fun invalidateCurrentPageRenderCaches() {
            invalidateExportEstimateCache(clearThumbnails = false)
            invalidatePageThumbnailCache(listOf(page.stableId))
        }

        fun syncFilterToggles() {
            val grayscaleToggle = grayscaleCheckBox ?: return
            val invertToggle = invertCheckBox ?: return
            syncingFilterToggles = true
            grayscaleToggle.isChecked = parameters.grayscale
            invertToggle.isChecked = parameters.invert
            syncingFilterToggles = false
            presetAdapter?.activePresetId = matchingFilterPresetId(parameters)
        }

        fun refreshSliders() {
            sliderRefreshers.forEach { it() }
        }

        fun applyFilter(next: ScanParameters) {
            rememberUndo("修改滤镜")
            parameters.copyFrom(next)
            syncFilterToggles()
            refreshSliders()
            invalidateCurrentPageRenderCaches()
            schedulePreview(preview, progress)
            refreshThumbnails?.invoke()
        }

        fun updatePreviewValue(assign: () -> Unit) {
            assign()
            syncFilterToggles()
            invalidateCurrentPageRenderCaches()
            schedulePreview(preview, progress)
        }

        fun LinearLayout.addBoundSlider(
            label: String,
            lower: Float,
            upper: Float,
            current: () -> Float,
            manualLower: Float = lower,
            manualUpper: Float = upper,
            assign: (Float) -> Unit
        ) {
            addView(slider(
                label,
                lower,
                upper,
                current(),
                manualLower,
                manualUpper,
                registerUpdater = { updateValue ->
                    sliderRefreshers += { updateValue(current()) }
                }
            ) {
                updatePreviewValue { assign(it) }
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 16.dp)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 2.dp, 0, 8.dp)
                addView(TextView(this@MainActivity).apply {
                    text = "滤镜：当前页单独调整"
                    textSize = 15f
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val grayscaleToggle = CheckBox(this@MainActivity).apply {
                    text = "黑白"
                    textSize = 13f
                    isChecked = parameters.grayscale
                    setOnCheckedChangeListener { _, checked ->
                        if (syncingFilterToggles) return@setOnCheckedChangeListener
                        rememberUndo("修改黑白")
                        updatePreviewValue { parameters.grayscale = checked }
                    }
                }
                grayscaleCheckBox = grayscaleToggle
                addView(grayscaleToggle)
                val invertToggle = CheckBox(this@MainActivity).apply {
                    text = "反色"
                    textSize = 13f
                    isChecked = parameters.invert
                    setOnCheckedChangeListener { _, checked ->
                        if (syncingFilterToggles) return@setOnCheckedChangeListener
                        rememberUndo("修改反色")
                        updatePreviewValue { parameters.invert = checked }
                    }
                }
                invertCheckBox = invertToggle
                addView(invertToggle)
            })
            addView(defaultFilterPresetRow(
                onApply = { applyFilter(it) },
                refreshPanel = refreshPanel,
                activePresetId = matchingFilterPresetId(parameters),
                onAdapterReady = { presetAdapter = it }
            ))
            addView(scrollRow(
                compactButton("应用全部") {
                    rememberUndo("滤镜应用到全部")
                    val affectedStableIds = pages.map { it.stableId }
                    pages.forEach { it.scanParameters = parameters.copy() }
                    invalidateExportEstimateCache(clearThumbnails = false)
                    invalidatePageThumbnailCache(affectedStableIds)
                    activePageStripAdapter?.notifyItemRangeChanged(0, pages.size)
                    refreshActiveEditorPreview(preserveZoom = true, maxLongEdge = mainPreviewLongEdge)
                    refreshThumbnails?.invoke()
                    Toast.makeText(this@MainActivity, "已把当前滤镜应用到全部图片", Toast.LENGTH_SHORT).show()
                },
                compactButton("指定页") {
                    showMorePageActions(parameters.copy(), true)
                },
                compactButton("保存预设") {
                    showSaveFilterPresetDialog(parameters, refreshPanel)
                }
            ))
            addBoundSlider("亮度", -0.25f, 0.35f, { parameters.brightness }, -1.0f, 1.0f) {
                parameters.brightness = it
            }
            addBoundSlider("曝光", -0.45f, 0.55f, { parameters.exposure }, -1.5f, 1.5f) {
                parameters.exposure = it
            }
            addBoundSlider("对比度", 0.85f, 1.85f, { parameters.contrast }, 0.1f, 4.0f) {
                parameters.contrast = it
            }
            addBoundSlider("Gamma", 0.75f, 1.25f, { parameters.gamma }, 0.2f, 3.0f) {
                parameters.gamma = it
            }
            addBoundSlider("饱和度", 0f, 1.5f, { parameters.saturation }, 0f, 4.0f) {
                parameters.saturation = it
            }
            addBoundSlider("锐化", 0f, 1.25f, { parameters.sharpen }, 0f, 3.0f) {
                parameters.sharpen = it
            }
            addView(TextView(this@MainActivity).apply {
                text = "高级扫描参数"
                textSize = 14f
                setTextColor(textSecondary)
                setPadding(0, 10.dp, 0, 4.dp)
            })
            addBoundSlider("黑场", 0f, 0.18f, { parameters.blackPoint }, 0f, 0.6f) {
                parameters.blackPoint = it
            }
            addBoundSlider("白场", 0f, 0.22f, { parameters.whitePoint }, 0f, 0.6f) {
                parameters.whitePoint = it
            }
            addBoundSlider("阴影", -0.28f, 0.28f, { parameters.shadows }, -1.0f, 1.0f) {
                parameters.shadows = it
            }
            addBoundSlider("高光", -0.28f, 0.28f, { parameters.highlights }, -1.0f, 1.0f) {
                parameters.highlights = it
            }
            addBoundSlider("色温", -0.25f, 0.25f, { parameters.temperature }, -1.0f, 1.0f) {
                parameters.temperature = it
            }
            addBoundSlider("色调", -0.25f, 0.25f, { parameters.tint }, -1.0f, 1.0f) {
                parameters.tint = it
            }
            addBoundSlider("纸面清洁", 0f, 0.7f, { parameters.paperClean }, 0f, 1.5f) {
                parameters.paperClean = it
            }
            addBoundSlider("笔迹增强", 0f, 1.2f, { parameters.inkBoost }, 0f, 1.8f) {
                parameters.inkBoost = it
            }
        }
    }

    private fun showSaveFilterPresetDialog(
        parameters: ScanParameters,
        refreshPanel: (() -> Unit)? = null
    ) {
        val input = EditText(this).apply {
            setText("我的滤镜")
            selectAll()
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        AlertDialog.Builder(this)
            .setTitle("保存滤镜预设")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "未命名预设" }
                rememberUndo("保存滤镜预设")
                filterPresetRepository.savePreset(name, parameters)
                Toast.makeText(this, "已保存滤镜预设：$name", Toast.LENGTH_SHORT).show()
                refreshPanel?.invoke()
            }
            .show()
    }

    private fun confirmDeleteFilterPreset(
        preset: FilterPresetRepository.FilterPreset,
        refreshPanel: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(this)
            .setTitle("删除滤镜预设？")
            .setMessage("“${preset.name}” 删除后可以用撤回恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                rememberUndo("删除滤镜预设")
                filterPresetRepository.deletePreset(preset.id)
                Toast.makeText(this, "滤镜预设已删除。", Toast.LENGTH_SHORT).show()
                refreshPanel?.invoke()
            }
            .show()
    }

    private fun filterPresetChip(
        preset: FilterPresetRepository.FilterPreset,
        onApply: () -> Unit,
        onDelete: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(241, 246, 252), 14.dp, Color.TRANSPARENT)
            setPadding(13.dp, 0, 8.dp, 0)
            minimumHeight = 38.dp
            addView(TextView(this@MainActivity).apply {
                text = preset.name
                textSize = 13f
                setTextColor(googleBlue)
                includeFontPadding = false
            })
            addView(TextView(this@MainActivity).apply {
                text = "×"
                textSize = 18f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(10.dp, 0, 2.dp, 1.dp)
                setOnClickListener { onDelete() }
            })
            setOnClickListener { onApply() }
        }
    }

    private fun showMorePageActions(
        initialFilterParameters: ScanParameters? = null,
        openFilterPanel: Boolean = false,
        initialSelection: Collection<Int>? = null,
        initialBatchState: BatchUiState? = null
    ) {
        if (pages.isEmpty()) return
        val selected = linkedSetOf<Int>().apply {
            addAll(
                (initialBatchState?.selectedPages ?: initialSelection)
                    ?.filter { it in pages.indices }
                    ?.ifEmpty { null }
                    ?: listOf(selectedPageIndex.coerceIn(0, pages.lastIndex))
            )
        }
        lateinit var bulkAdapter: BulkPageAdapter
        lateinit var status: TextView
        lateinit var dialog: AlertDialog
        lateinit var filterButton: View
        lateinit var toolSheet: LinearLayout
        lateinit var grid: RecyclerView
        var filterPanelOpen = false

        fun selectedList(): List<Int> = selected.filter { it in pages.indices }

        fun refreshSelection() {
            status.text = "已选择 ${selectedList().size} 页"
            bulkAdapter.notifyDataSetChanged()
        }

        fun requireSelection(): List<Int>? {
            val targets = selectedList()
            if (targets.isEmpty()) {
                Toast.makeText(this, "请先选择页面。", Toast.LENGTH_SHORT).show()
                return null
            }
            return targets
        }

        fun captureBatchUiState(): BatchUiState {
            val layoutManager = grid.layoutManager as? GridLayoutManager
            val first = layoutManager
                ?.findFirstVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
                ?: 0
            val child = layoutManager?.findViewByPosition(first)
            val offset = (child?.top ?: grid.paddingTop) - grid.paddingTop
            return BatchUiState(
                selectedPages = selectedList(),
                firstGridPosition = first.coerceAtLeast(0),
                firstGridOffset = offset,
                toolSheetHeight = toolSheet.layoutParams?.height ?: 206.dp,
                filterPanelOpen = filterPanelOpen
            )
        }

        fun applyBulk(label: String, action: (Int) -> Unit) {
            val targets = requireSelection() ?: return
            rememberUndo(label)
            targets.forEach(action)
            selectedPageIndex = targets.first().coerceIn(0, pages.lastIndex)
            invalidateExportEstimateCache()
            anchorStripNearSelected()
            dialog.dismiss()
            showEditor()
        }

        fun applyBulkInPlace(label: String, action: (Int) -> Unit) {
            val targets = requireSelection() ?: return
            rememberUndo(label)
            val activeStableId = pages.getOrNull(selectedPageIndex)?.stableId
            val affectedStableIds = targets.mapNotNull { pages.getOrNull(it)?.stableId }
            targets.forEach(action)
            selectedPageIndex = activeStableId
                ?.let { stableId -> pages.indexOfFirst { it.stableId == stableId } }
                ?.takeIf { it >= 0 }
                ?: selectedPageIndex.coerceIn(0, pages.lastIndex)
            invalidateExportEstimateCache(clearThumbnails = false)
            invalidatePageThumbnailCache(affectedStableIds)
            targets.forEach { index ->
                bulkAdapter.notifyItemChanged(index)
                activePageStripAdapter?.notifyItemChanged(index)
            }
            if (activeStableId != null && affectedStableIds.contains(activeStableId)) {
                refreshActiveEditorPreview(preserveZoom = true, maxLongEdge = mainPreviewLongEdge)
            }
            Toast.makeText(this, "已处理 ${targets.size} 页", Toast.LENGTH_SHORT).show()
        }

        fun updateToolSheetHeight(height: Int) {
            val availableHeight = (
                resources.displayMetrics.heightPixels -
                    safeTopPadding() -
                    220.dp
                ).coerceIn(560.dp, 820.dp)
            val minGrid = if (filterPanelOpen) 104.dp else 320.dp
            val minSheet = 34.dp
            val maxSheet = if (filterPanelOpen) {
                (availableHeight - minGrid).coerceIn(360.dp, 700.dp)
            } else {
                216.dp
            }
            val nextSheet = height.coerceIn(minSheet, maxSheet)
            val nextGrid = (availableHeight - nextSheet).coerceIn(minGrid, 560.dp)
            toolSheet.layoutParams = toolSheet.layoutParams.apply {
                this.height = nextSheet
            }
            grid.layoutParams = grid.layoutParams.apply {
                this.height = nextGrid
            }
            toolSheet.requestLayout()
            grid.requestLayout()
        }

        fun animateToolSheetHeight(targetHeight: Int) {
            val startHeight = (toolSheet.layoutParams?.height ?: targetHeight)
            android.animation.ValueAnimator.ofInt(startHeight, targetHeight).apply {
                duration = 180L
                interpolator = android.view.animation.DecelerateInterpolator(1.35f)
                addUpdateListener { animator ->
                    updateToolSheetHeight(animator.animatedValue as Int)
                }
                start()
            }
        }

        fun attachToolSheetDrag(handle: View) {
            val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
            var startRawY = 0f
            var startHeight = 0
            var dragging = false
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = event.rawY
                        startHeight = (toolSheet.layoutParams?.height ?: 250.dp)
                        dragging = false
                        handle.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - startRawY
                        if (!dragging && abs(delta) < touchSlop) return@setOnTouchListener true
                        dragging = true
                        updateToolSheetHeight(startHeight - delta.roundToInt())
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        handle.parent?.requestDisallowInterceptTouchEvent(false)
                        dragging = false
                        true
                    }
                    else -> false
                }
            }
        }

        var bulkFilterParameters = initialFilterParameters?.copy()
            ?: pages.getOrNull(selectedPageIndex)?.scanParameters?.copy()
            ?: ScanParameters.original()
        val filterPanelHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 10.dp, 10.dp, 24.dp)
        }
        val filterPanelScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = rounded(Color.rgb(247, 249, 252), 16.dp, border)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 10.dp
            }
            addView(filterPanelHost)
        }

        fun applyBatchFilter() {
            val targets = requireSelection() ?: return
            rememberUndo("批量应用滤镜")
            val activeStableId = pages.getOrNull(selectedPageIndex)?.stableId
            val affectedStableIds = targets.mapNotNull { pages.getOrNull(it)?.stableId }
            targets.forEach { index ->
                pages[index].scanParameters = bulkFilterParameters.copy()
            }
            selectedPageIndex = activeStableId
                ?.let { stableId -> pages.indexOfFirst { it.stableId == stableId } }
                ?.takeIf { it >= 0 }
                ?: selectedPageIndex.coerceIn(0, pages.lastIndex)
            invalidateExportEstimateCache(clearThumbnails = false)
            invalidatePageThumbnailCache(affectedStableIds)
            targets.forEach { index ->
                bulkAdapter.notifyItemChanged(index)
                activePageStripAdapter?.notifyItemChanged(index)
            }
            if (activeStableId != null && affectedStableIds.contains(activeStableId)) {
                refreshActiveEditorPreview(preserveZoom = true, maxLongEdge = mainPreviewLongEdge)
            }
            Toast.makeText(this, "已把滤镜应用到 ${targets.size} 页", Toast.LENGTH_SHORT).show()
        }

        fun showBatchFilterPanel(targetHeight: Int = 500.dp, animate: Boolean = true) {
            filterPanelScroll.visibility = View.VISIBLE
            filterPanelOpen = true
            setBulkActionButtonState(filterButton, R.drawable.ic_tool_check, "应用", true)
            rebuildBatchFilterPanel(filterPanelHost, bulkFilterParameters, ::applyBatchFilter)
            if (animate) {
                animateToolSheetHeight(targetHeight)
            } else {
                updateToolSheetHeight(targetHeight)
            }
            filterPanelScroll.post {
                filterPanelScroll.requestLayout()
            }
        }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(2.dp, 0, 2.dp, 10.dp)
        }
        bulkAdapter = BulkPageAdapter(
            selected = selected,
            onTapped = { index ->
                if (selected.contains(index)) selected.remove(index) else selected.add(index)
                refreshSelection()
            },
            onPreviewTapped = { index ->
                showFullPreview(index)
            }
        )
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            this.adapter = bulkAdapter
            setPadding(6.dp, 0, 10.dp, 6.dp)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addOnItemTouchListener(BulkPageRectangleTouchListener(selected) { refreshSelection() })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                390.dp
            )
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 16.dp, 18.dp, 12.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 6.dp)
                addView(TextView(this@MainActivity).apply {
                    text = "批量操作"
                    textSize = 24f
                    includeFontPadding = false
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, 44.dp, 1f)
                    gravity = Gravity.CENTER_VERTICAL
                })
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_tool_close_custom)
                    imageTintList = ColorStateList.valueOf(textPrimary)
                    background = rounded(Color.rgb(242, 246, 251), 15.dp, border)
                    backgroundTintList = ColorStateList.valueOf(Color.rgb(242, 246, 251))
                    contentDescription = "关闭"
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                    setOnClickListener { dialog.dismiss() }
                    layoutParams = LinearLayout.LayoutParams(42.dp, 42.dp)
                })
            })
            addView(status)
            addView(grid)
            toolSheet = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                clipToPadding = true
                clipChildren = true
                background = rounded(Color.rgb(250, 252, 255), 18.dp, Color.rgb(214, 226, 239))
                setPadding(10.dp, 8.dp, 10.dp, 10.dp)
                attachToolSheetDrag(this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    206.dp
                ).apply {
                    topMargin = 8.dp
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    attachToolSheetDrag(this)
                    addView(View(this@MainActivity).apply {
                        background = rounded(Color.rgb(202, 210, 222), 99.dp, Color.TRANSPARENT)
                        layoutParams = LinearLayout.LayoutParams(72.dp, 5.dp)
                    })
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        22.dp
                    ).apply {
                        bottomMargin = 6.dp
                    }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 2.dp, 0, 0)
                    addView(smallButton("全选") {
                        selected.clear()
                        selected.addAll(pages.indices)
                        refreshSelection()
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 8.dp
                    })
                    addView(smallButton("清空") {
                        selected.clear()
                        status.text = "已选择 0 页"
                        bulkAdapter.notifyDataSetChanged()
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 8.dp
                    })
                    addView(bulkActionButton(R.drawable.ic_tool_crop, "统一裁剪") {
                        requireSelection()?.let { targets ->
                            val first = targets.first().coerceIn(0, pages.lastIndex)
                            val batchState = captureBatchUiState()
                            val editorStableId = pages.getOrNull(selectedPageIndex)?.stableId
                            dialog.dismiss()
                            showCropEditor(first, targets) {
                                selectedPageIndex = editorStableId
                                    ?.let { stableId -> pages.indexOfFirst { it.stableId == stableId } }
                                    ?.takeIf { it >= 0 }
                                    ?: selectedPageIndex.coerceIn(0, pages.lastIndex)
                                showEditor()
                                previewHandler.post {
                                    showMorePageActions(initialBatchState = batchState)
                                }
                            }
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8.dp, 0, 0)
                    addView(bulkActionButton(R.drawable.ic_tool_rotate_left, "左转") {
                        applyBulkInPlace("批量左转") { index ->
                            pages[index].rotationDegrees = ((pages[index].rotationDegrees - 90) % 360 + 360) % 360
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 8.dp
                    })
                    addView(bulkActionButton(R.drawable.ic_tool_rotate_right, "右转") {
                        applyBulkInPlace("批量右转") { index ->
                            pages[index].rotationDegrees = ((pages[index].rotationDegrees + 90) % 360 + 360) % 360
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 8.dp
                    })
                    filterButton = bulkActionButton(R.drawable.ic_tool_filter, "应用滤镜") {
                        if (filterPanelOpen) applyBatchFilter() else showBatchFilterPanel()
                    }
                    addView(filterButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(filterPanelScroll)
            }
            addView(toolSheet)
        }
        dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, 22.dp, Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).roundToInt(),
                (resources.displayMetrics.heightPixels * 0.92f).roundToInt()
            )
            refreshSelection()
            val restoreState = initialBatchState
            if (restoreState?.filterPanelOpen == true || openFilterPanel) {
                showBatchFilterPanel(
                    targetHeight = restoreState?.toolSheetHeight ?: 500.dp,
                    animate = false
                )
            } else {
                updateToolSheetHeight(restoreState?.toolSheetHeight ?: toolSheet.layoutParams.height)
            }
            restoreState?.let { state ->
                grid.post {
                    (grid.layoutManager as? GridLayoutManager)
                        ?.scrollToPositionWithOffset(state.firstGridPosition, state.firstGridOffset)
                }
            }
        }
        dialog.setOnDismissListener { bulkAdapter.release() }
        dialog.show()
    }

    private fun rebuildBatchFilterPanel(
        host: LinearLayout,
        parameters: ScanParameters,
        onApply: () -> Unit
    ) {
        host.removeAllViews()
        var presetAdapter: DefaultFilterPresetAdapter? = null
        var grayscaleCheckBox: CheckBox? = null
        var invertCheckBox: CheckBox? = null
        var syncingFilterToggles = false
        val sliderRefreshers = mutableListOf<() -> Unit>()

        fun syncFilterToggles() {
            val grayscaleToggle = grayscaleCheckBox ?: return
            val invertToggle = invertCheckBox ?: return
            syncingFilterToggles = true
            grayscaleToggle.isChecked = parameters.grayscale
            invertToggle.isChecked = parameters.invert
            syncingFilterToggles = false
            presetAdapter?.activePresetId = matchingFilterPresetId(parameters)
        }

        fun refreshSliders() {
            sliderRefreshers.forEach { it() }
        }

        fun applyPreset(next: ScanParameters) {
            parameters.copyFrom(next)
            syncFilterToggles()
            refreshSliders()
        }

        fun updateValue(assign: () -> Unit) {
            assign()
            syncFilterToggles()
        }

        fun addBatchSlider(
            label: String,
            lower: Float,
            upper: Float,
            current: () -> Float,
            manualLower: Float = lower,
            manualUpper: Float = upper,
            assign: (Float) -> Unit
        ) {
            host.addView(slider(
                label,
                lower,
                upper,
                current(),
                manualLower,
                manualUpper,
                captureUndo = false,
                registerUpdater = { updateValue ->
                    sliderRefreshers += { updateValue(current()) }
                }
            ) {
                updateValue { assign(it) }
            })
        }

        host.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8.dp)
            addView(TextView(this@MainActivity).apply {
                text = "批量滤镜"
                textSize = 15f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val grayscaleToggle = CheckBox(this@MainActivity).apply {
                text = "黑白"
                textSize = 13f
                isChecked = parameters.grayscale
                setOnCheckedChangeListener { _, checked ->
                    if (syncingFilterToggles) return@setOnCheckedChangeListener
                    updateValue { parameters.grayscale = checked }
                }
            }
            grayscaleCheckBox = grayscaleToggle
            addView(grayscaleToggle)
            val invertToggle = CheckBox(this@MainActivity).apply {
                text = "反色"
                textSize = 13f
                isChecked = parameters.invert
                setOnCheckedChangeListener { _, checked ->
                    if (syncingFilterToggles) return@setOnCheckedChangeListener
                    updateValue { parameters.invert = checked }
                }
            }
            invertCheckBox = invertToggle
            addView(invertToggle)
        })
        host.addView(defaultFilterPresetRow(
            onApply = { applyPreset(it) },
            refreshPanel = null,
            showDelete = false,
            allowReorder = false,
            activePresetId = matchingFilterPresetId(parameters),
            onAdapterReady = { presetAdapter = it }
        ))
        addBatchSlider("亮度", -0.25f, 0.35f, { parameters.brightness }, -1.0f, 1.0f) {
            parameters.brightness = it
        }
        addBatchSlider("曝光", -0.45f, 0.55f, { parameters.exposure }, -1.5f, 1.5f) {
            parameters.exposure = it
        }
        addBatchSlider("对比度", 0.85f, 1.85f, { parameters.contrast }, 0.1f, 4.0f) {
            parameters.contrast = it
        }
        addBatchSlider("Gamma", 0.75f, 1.25f, { parameters.gamma }, 0.2f, 3.0f) {
            parameters.gamma = it
        }
        addBatchSlider("饱和度", 0f, 1.5f, { parameters.saturation }, 0f, 4.0f) {
            parameters.saturation = it
        }
        addBatchSlider("锐化", 0f, 1.25f, { parameters.sharpen }, 0f, 3.0f) {
            parameters.sharpen = it
        }
        host.addView(TextView(this).apply {
            text = "高级扫描参数"
            textSize = 14f
            setTextColor(textSecondary)
            setPadding(0, 10.dp, 0, 4.dp)
        })
        addBatchSlider("黑场", 0f, 0.18f, { parameters.blackPoint }, 0f, 0.6f) {
            parameters.blackPoint = it
        }
        addBatchSlider("白场", 0f, 0.22f, { parameters.whitePoint }, 0f, 0.6f) {
            parameters.whitePoint = it
        }
        addBatchSlider("阴影", -0.28f, 0.28f, { parameters.shadows }, -1.0f, 1.0f) {
            parameters.shadows = it
        }
        addBatchSlider("高光", -0.28f, 0.28f, { parameters.highlights }, -1.0f, 1.0f) {
            parameters.highlights = it
        }
        addBatchSlider("色温", -0.25f, 0.25f, { parameters.temperature }, -1.0f, 1.0f) {
            parameters.temperature = it
        }
        addBatchSlider("色调", -0.25f, 0.25f, { parameters.tint }, -1.0f, 1.0f) {
            parameters.tint = it
        }
        addBatchSlider("纸面清洁", 0f, 0.7f, { parameters.paperClean }, 0f, 1.5f) {
            parameters.paperClean = it
        }
        addBatchSlider("笔迹增强", 0f, 1.2f, { parameters.inkBoost }, 0f, 1.8f) {
            parameters.inkBoost = it
        }
        host.addView(smallButton("应用到已选") { onApply() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 8.dp
        })
    }

    private inner class BulkPageRectangleTouchListener(
        private val selected: MutableSet<Int>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.SimpleOnItemTouchListener() {
        private val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private val longPressDelayMs = max(180, ViewConfiguration.getLongPressTimeout() - 70).toLong()
        private val autoScrollEdge = 88.dp
        private val autoScroller = EdgeAutoScroller(autoScrollEdge)
        private var downX = 0f
        private var downY = 0f
        private var startPosition = RecyclerView.NO_POSITION
        private var currentPosition = RecyclerView.NO_POSITION
        private var dragActive = false
        private var dragSelect = true
        private var beforeDrag = linkedSetOf<Int>()
        private var longPressRunnable: Runnable? = null

        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reset(rv)
                    downX = event.x
                    downY = event.y
                    startPosition = positionAt(rv, event.x, event.y)
                    currentPosition = startPosition
                    if (isPagePosition(rv, startPosition) && !isPreviewTapZone(rv, startPosition, event.x, event.y)) {
                        longPressRunnable = Runnable { beginDrag(rv) }.also {
                            previewHandler.postDelayed(it, longPressDelayMs)
                        }
                    }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragActive) {
                        if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                            cancelLongPress()
                        }
                        return false
                    }
                    updateDrag(rv, event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (dragActive) {
                        reset(rv)
                        return true
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        isPagePosition(rv, startPosition) &&
                        abs(event.x - downX) <= touchSlop &&
                        abs(event.y - downY) <= touchSlop
                    ) {
                        if (isPreviewTapZone(rv, startPosition, downX, downY)) {
                            cancelLongPress()
                            return false
                        }
                        if (selected.contains(startPosition)) selected.remove(startPosition) else selected.add(startPosition)
                        onSelectionChanged()
                        cancelLongPress()
                        return true
                    }
                    cancelLongPress()
                }
            }
            return dragActive
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            if (!dragActive) return
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDrag(rv, event.x, event.y)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> reset(rv)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        private fun beginDrag(rv: RecyclerView) {
            if (dragActive || !isPagePosition(rv, startPosition)) return
            dragActive = true
            dragSelect = startPosition !in selected
            beforeDrag = LinkedHashSet(selected)
            rv.parent?.requestDisallowInterceptTouchEvent(true)
            updateDrag(rv, downX, downY)
        }

        private fun updateDrag(rv: RecyclerView, x: Float, y: Float) {
            currentPosition = positionAtOrEdge(rv, x, y).takeIf { isPagePosition(rv, it) } ?: currentPosition
            if (!isPagePosition(rv, currentPosition)) return
            val rectangle = rectanglePagePositions(rv, startPosition, currentPosition)
            selected.clear()
            if (dragSelect) {
                selected.addAll(beforeDrag)
                selected.addAll(rectangle)
            } else {
                selected.addAll(beforeDrag.filter { it !in rectangle })
            }
            onSelectionChanged()
            autoScroll(rv, y)
        }

        private fun reset(rv: RecyclerView) {
            cancelLongPress()
            autoScroller.stop()
            rv.parent?.requestDisallowInterceptTouchEvent(false)
            startPosition = RecyclerView.NO_POSITION
            currentPosition = RecyclerView.NO_POSITION
            dragActive = false
            beforeDrag.clear()
        }

        private fun cancelLongPress() {
            longPressRunnable?.let(previewHandler::removeCallbacks)
            longPressRunnable = null
        }

        private fun positionAt(rv: RecyclerView, x: Float, y: Float): Int {
            val child = rv.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
            return rv.getChildAdapterPosition(child)
        }

        private fun isPreviewTapZone(rv: RecyclerView, position: Int, x: Float, y: Float): Boolean {
            val child = rv.layoutManager?.findViewByPosition(position) ?: return false
            val localX = x - child.left
            val localY = y - child.top
            val zoneWidth = 34.dp
            val zoneHeight = 28.dp
            return localX in 0f..zoneWidth.toFloat() &&
                localY in (child.height - zoneHeight).toFloat()..child.height.toFloat()
        }

        private fun positionAtOrEdge(rv: RecyclerView, x: Float, y: Float): Int {
            val clampedX = x.coerceIn(1f, (rv.width - 1).coerceAtLeast(1).toFloat())
            val clampedY = y.coerceIn(1f, (rv.height - 1).coerceAtLeast(1).toFloat())
            positionAt(rv, clampedX, clampedY).takeIf { it != RecyclerView.NO_POSITION }?.let {
                return it
            }
            val layoutManager = rv.layoutManager as? GridLayoutManager ?: return RecyclerView.NO_POSITION
            return when {
                y < 0f -> layoutManager.findFirstVisibleItemPosition()
                y > rv.height -> layoutManager.findLastVisibleItemPosition()
                else -> RecyclerView.NO_POSITION
            }
        }

        private fun rectanglePagePositions(rv: RecyclerView, start: Int, current: Int): List<Int> {
            val layoutManager = rv.layoutManager as? GridLayoutManager ?: return emptyList()
            val span = layoutManager.spanCount.coerceAtLeast(1)
            val startRow = start / span
            val currentRow = current / span
            val startColumn = start % span
            val currentColumn = current % span
            val minRow = min(startRow, currentRow)
            val maxRow = max(startRow, currentRow)
            val minColumn = min(startColumn, currentColumn)
            val maxColumn = max(startColumn, currentColumn)
            val rows = if (currentRow >= startRow) {
                minRow..maxRow
            } else {
                maxRow downTo minRow
            }
            val columns = if (currentColumn >= startColumn) {
                minColumn..maxColumn
            } else {
                maxColumn downTo minColumn
            }
            val positions = ArrayList<Int>()
            for (row in rows) {
                for (column in columns) {
                    val position = row * span + column
                    if (isPagePosition(rv, position)) positions += position
                }
            }
            return positions
        }

        private fun autoScroll(rv: RecyclerView, y: Float) {
            autoScroller.update(rv, y)
        }

        private fun isPagePosition(rv: RecyclerView, position: Int): Boolean {
            val count = rv.adapter?.itemCount ?: return false
            return position in 0 until count
        }
    }

    private fun showFullPreview(pageIndex: Int = selectedPageIndex) {
        val normalizedIndex = pageIndex.coerceIn(0, pages.lastIndex)
        val page = pages.getOrNull(normalizedIndex) ?: return
        var renderJob: Job? = null
        var originalJob: Job? = null
        var filteredBitmap: Bitmap? = null
        var originalBitmap: Bitmap? = null
        var showingOriginal = false
        val image = ZoomableImageView(this).apply {
            adjustViewBounds = true
            background = rounded(Color.rgb(244, 247, 251), 18.dp, Color.TRANSPARENT)
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progress = modernProgress().apply {
            visibility = View.VISIBLE
        }
        val previewArea = FrameLayout(this).apply {
            val previewHeight = (resources.displayMetrics.heightPixels * 0.62f)
                .roundToInt()
                .coerceIn(360.dp, 660.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                previewHeight
            )
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            background = rounded(Color.rgb(248, 250, 253), 20.dp, border)
            addView(image)
            addView(progress)
        }
        fun startOriginalPreload() {
            if (originalBitmap != null || originalJob != null) return
            originalJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderPageBitmap(page, fullPreviewLongEdge, false)
                    }
                }.onSuccess { original ->
                    originalJob = null
                    originalBitmap = original
                    if (showingOriginal) {
                        image.setImageBitmapPreservingZoom(original)
                        progress.visibility = View.GONE
                    }
                }.onFailure { error ->
                    originalJob = null
                    if (error is CancellationException) return@onFailure
                    if (showingOriginal) progress.visibility = View.GONE
                    showError(error)
                }
            }
        }
        val originalButton = smallButton("按住看原图") {}
        originalButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showingOriginal = true
                    originalBitmap?.let {
                        image.setImageBitmapPreservingZoom(it)
                    } ?: run {
                        progress.visibility = View.VISIBLE
                        startOriginalPreload()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showingOriginal = false
                    filteredBitmap?.let(image::setImageBitmapPreservingZoom)
                    true
                }
                else -> true
            }
        }
        val saveButton = smallButton("保存当前图") {
            pendingImageExportOptions = imageExportOptions()
            pendingImageFormat = ImageOutputFormat.JPG
            pendingSingleImageIndex = normalizedIndex
            launchSingleImageExport(ImageOutputFormat.JPG, normalizedIndex)
        }
        lateinit var dialog: AlertDialog
        val closeButton = smallButton("关闭") {
            dialog.dismiss()
        }.apply {
            setTextColor(googleBlue)
            background = rounded(Color.rgb(241, 246, 252), 16.dp, border)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(241, 246, 252))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(previewArea)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp, 0, 0)
                addView(View(this@MainActivity), LinearLayout.LayoutParams(
                    0,
                    1,
                    1f
                ))
                addView(saveButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8.dp
                })
                addView(originalButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8.dp
                })
                addView(View(this@MainActivity), LinearLayout.LayoutParams(
                    0,
                    1,
                    1f
                ))
                addView(closeButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
        }
        dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT))
            val fullCached = cachedPreviewCopy(page, fullPreviewLongEdge)
            val quick = fullCached ?: cachedPreviewCopy(page, mainPreviewLongEdge)
            if (quick != null) {
                filteredBitmap = quick
                image.setImageBitmap(quick)
                progress.visibility = if (fullCached != null) View.GONE else View.VISIBLE
                if (fullCached != null) startOriginalPreload()
            }
            renderJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderPageBitmap(page, fullPreviewLongEdge, true)
                    }
                }.onSuccess { filtered ->
                    if (!dialog.isShowing) {
                        filtered.recycle()
                        return@onSuccess
                    }
                    filteredBitmap?.takeIf { it !== filtered && !it.isRecycled }?.recycle()
                    filteredBitmap = filtered
                    if (!showingOriginal) {
                        if (image.isZoomedIn()) {
                            image.setImageBitmapPreservingZoom(filtered)
                        } else {
                            image.setImageBitmap(filtered)
                        }
                    }
                    progress.visibility = View.GONE
                    startOriginalPreload()
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    progress.visibility = View.GONE
                    showError(error)
                }
            }
        }
        dialog.setOnDismissListener {
            renderJob?.cancel()
            originalJob?.cancel()
            filteredBitmap?.recycle()
            originalBitmap?.recycle()
            filteredBitmap = null
            originalBitmap = null
        }
        dialog.show()
    }

    private fun detectDocumentCrop(bitmap: Bitmap): NormalizedCrop? {
        return DocumentCropDetector.detect(bitmap)
    }

    private fun showCropEditor(
        pageIndex: Int = selectedPageIndex,
        applyTargets: List<Int> = listOf(pageIndex),
        afterFinish: (() -> Unit)? = null
    ) {
        val normalizedPageIndex = pageIndex.coerceIn(0, pages.lastIndex)
        selectedPageIndex = normalizedPageIndex
        val targetIndices = applyTargets
            .distinct()
            .filter { it in pages.indices }
            .ifEmpty { listOf(normalizedPageIndex) }
        val page = pages.getOrNull(normalizedPageIndex) ?: return
        previewJob?.cancel()
        pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
        pendingPreviewRunnable = null

        var cropBitmap: Bitmap? = null
        var loadJob: Job? = null
        var smartCropMode = false
        val cropView = CropOverlayView(this)
        val loading = modernProgress().apply {
            visibility = View.VISIBLE
        }
        fun finishCropEditor() {
            loadJob?.cancel()
            cropBitmap?.recycle()
            cropBitmap = null
            afterFinish?.invoke() ?: showEditor()
        }

        fun applyCropTo(indices: List<Int>, label: String) {
            if (cropBitmap == null) return
            val nextCrop = cropView.currentCrop()
            rememberUndo(label)
            val storedCrop = nextCrop.takeUnless { it.isFullFrame() }
            indices.distinct().filter { it in pages.indices }.forEach { index ->
                pages[index].crop = storedCrop
            }
            invalidateExportEstimateCache()
            finishCropEditor()
        }

        fun applySmartCropTo(indices: List<Int>) {
            val targets = indices.distinct().filter { it in pages.indices }
            if (targets.isEmpty()) return
            loading.visibility = View.VISIBLE
            loadJob?.cancel()
            loadJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        targets.mapNotNull { index ->
                            val targetPage = pages.getOrNull(index) ?: return@mapNotNull null
                            val decoded = BitmapDecoder.decode(contentResolver, targetPage.sourceUri, 1400)
                            val rotated = BitmapDecoder.rotate(decoded, targetPage.rotationDegrees)
                            if (rotated !== decoded) decoded.recycle()
                            val detected = detectDocumentCrop(rotated)
                            if (rotated !== decoded && !rotated.isRecycled) rotated.recycle()
                            detected?.let { index to it }
                        }
                    }
                }.onSuccess { detectedCrops ->
                    loading.visibility = View.GONE
                    if (detectedCrops.isEmpty()) {
                        Toast.makeText(this@MainActivity, "没有识别到足够清晰的纸张边缘。", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    rememberUndo("智能裁剪应用到全部")
                    val affectedStableIds = detectedCrops.mapNotNull { (index, _) -> pages.getOrNull(index)?.stableId }
                    detectedCrops.forEach { (index, crop) ->
                        pages[index].crop = crop.takeUnless { it.isFullFrame() }
                    }
                    invalidateExportEstimateCache(clearThumbnails = false)
                    invalidatePageThumbnailCache(affectedStableIds)
                    detectedCrops.forEach { (index, _) -> activePageStripAdapter?.notifyItemChanged(index) }
                    Toast.makeText(
                        this@MainActivity,
                        "已智能裁剪 ${detectedCrops.size} 页",
                        Toast.LENGTH_SHORT
                    ).show()
                    finishCropEditor()
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    loading.visibility = View.GONE
                    showError(error)
                }
            }
        }

        fun smartCropCurrentPage() {
            val source = cropBitmap ?: return
            smartCropMode = true
            loading.visibility = View.VISIBLE
            loadJob?.cancel()
            loadJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        detectDocumentCrop(source)
                    }
                }.onSuccess { detected ->
                    loading.visibility = View.GONE
                    if (detected == null) {
                        Toast.makeText(this@MainActivity, "没有识别到足够清晰的纸张边缘。", Toast.LENGTH_SHORT).show()
                    } else {
                        cropView.setCrop(detected)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    loading.visibility = View.GONE
                    showError(error)
                }
            }
        }

        fun leaveCropEditor(apply: Boolean) {
            if (apply) {
                applyCropTo(targetIndices, "裁剪")
            } else {
                finishCropEditor()
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, safeTopPadding() + 10.dp, 14.dp, 10.dp)
            setBackgroundColor(Color.rgb(28, 30, 33))
            addView(TextView(this@MainActivity).apply {
                text = "‹"
                textSize = 40f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "返回"
                layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp)
                setOnClickListener { leaveCropEditor(false) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "裁剪"
                textSize = 22f
                setTextColor(Color.WHITE)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f)
            })
        }
        val cropArea = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(cropView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(loading)
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            setBackgroundColor(Color.rgb(38, 40, 44))
            addView(cropToolButton(R.drawable.ic_tool_rotate_left, "左转") {
                loadJob?.cancel()
                rememberUndo("裁剪旋转")
                page.rotationDegrees = ((page.rotationDegrees - 90) % 360 + 360) % 360
                page.crop = null
                invalidateExportEstimateCache()
                cropBitmap?.recycle()
                cropBitmap = null
                showCropEditor(normalizedPageIndex, targetIndices, afterFinish)
            })
            addView(cropToolButton(R.drawable.ic_tool_rotate_right, "右转") {
                loadJob?.cancel()
                rememberUndo("裁剪旋转")
                page.rotationDegrees = ((page.rotationDegrees + 90) % 360 + 360) % 360
                page.crop = null
                invalidateExportEstimateCache()
                cropBitmap?.recycle()
                cropBitmap = null
                showCropEditor(normalizedPageIndex, targetIndices, afterFinish)
            })
            addView(cropToolButton(R.drawable.ic_tool_crop, "智能裁剪") {
                smartCropCurrentPage()
            })
            addView(cropToolButton(R.drawable.ic_tool_crop, "全图") {
                smartCropMode = false
                cropView.setCrop(NormalizedCrop.full())
            })
            addView(cropToolButton(R.drawable.ic_tool_crop, "应用全部") {
                if (smartCropMode) {
                    applySmartCropTo(pages.indices.toList())
                } else {
                    applyCropTo(pages.indices.toList(), "裁剪应用到全部")
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "✓"
                textSize = 34f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(20, 184, 166), 8.dp, Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, 58.dp, 1.25f).apply {
                    marginStart = 8.dp
                }
                contentDescription = "完成"
                setOnClickListener { leaveCropEditor(true) }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(topBar)
            addView(cropArea)
            addView(bottomBar)
        }
        setContentView(root)

        loadJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val decoded = BitmapDecoder.decode(contentResolver, page.sourceUri, 2200)
                    val rotated = BitmapDecoder.rotate(decoded, page.rotationDegrees)
                    if (rotated !== decoded) decoded.recycle()
                    rotated to (page.crop ?: detectDocumentCrop(rotated))
                }
            }.onSuccess { (bitmap, suggestedCrop) ->
                cropBitmap = bitmap
                cropView.setImage(bitmap, suggestedCrop)
                loading.visibility = View.GONE
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                showEditor()
                showError(error)
            }
        }
    }

    private fun slider(
        label: String,
        lower: Float,
        upper: Float,
        initial: Float,
        manualLower: Float = lower,
        manualUpper: Float = upper,
        captureUndo: Boolean = true,
        registerUpdater: (((Float) -> Unit) -> Unit)? = null,
        onChange: (Float) -> Unit
    ): LinearLayout {
        var currentValue = initial
        var undoCaptured = false
        val value = TextView(this).apply {
            textSize = 12f
            setTextColor(textSecondary)
            gravity = Gravity.END
            text = formatFloat(currentValue)
            layoutParams = LinearLayout.LayoutParams(72.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val rowLabel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
        }
        fun progressFor(number: Float): Int {
            return (((number.coerceIn(lower, upper) - lower) / (upper - lower)) * 1_000f)
                .roundToInt()
                .coerceIn(0, 1_000)
        }
        fun valueFor(progress: Int): Float {
            return lower + (progress / 1_000f) * (upper - lower)
        }
        val seek = SeekBar(this).apply {
            max = 1_000
            progress = progressFor(initial)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = valueFor(progress)
                    currentValue = next
                    value.text = formatFloat(next)
                    onChange(next)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    if (captureUndo && !undoCaptured) {
                        rememberUndo("修改$label")
                        undoCaptured = true
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    undoCaptured = false
                }
            })
        }
        fun updateDisplayedValue(next: Float) {
            currentValue = next
            seek.progress = progressFor(next)
            value.text = formatFloat(next)
        }
        registerUpdater?.invoke(::updateDisplayedValue)
        value.setOnClickListener {
            showNumberInput(label, manualLower, manualUpper, currentValue) { next ->
                if (captureUndo) rememberUndo("修改$label")
                val bounded = next.coerceIn(manualLower, manualUpper)
                currentValue = bounded
                seek.progress = progressFor(bounded)
                value.text = formatFloat(bounded)
                onChange(bounded)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dp, 0, 6.dp)
            addView(rowLabel)
            addView(seek)
        }
    }

    private fun showNumberInput(
        label: String,
        lower: Float,
        upper: Float,
        initial: Float,
        onApply: (Float) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(formatFloat(initial.coerceIn(lower, upper)))
            selectAll()
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        AlertDialog.Builder(this)
            .setTitle("$label (${formatFloat(lower)} ~ ${formatFloat(upper)})")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val next = input.text.toString().trim().toFloatOrNull()
                if (next == null) {
                    Toast.makeText(this, "请输入数字。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onApply(next.coerceIn(lower, upper))
            }
            .show()
    }

    private fun schedulePreview(
        preview: ImageView,
        progress: ProgressBar,
        preserveZoom: Boolean = true,
        maxLongEdge: Int = mainPreviewLongEdge
    ) {
        pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
        pendingPreviewRunnable = Runnable {
            refreshPreview(preview, progress, preserveZoom, maxLongEdge)
        }.also { previewHandler.postDelayed(it, 36L) }
    }

    private fun refreshActiveEditorPreview(
        preserveZoom: Boolean = true,
        maxLongEdge: Int = mainPreviewLongEdge
    ) {
        val preview = activePreviewView ?: return
        val progress = activePreviewProgress ?: return
        if (pages.isEmpty()) return
        refreshPreview(preview, progress, preserveZoom, maxLongEdge)
    }

    private fun prefetchNeighborPreviews(centerIndex: Int, maxLongEdge: Int = mainPreviewLongEdge) {
        previewPrefetchJob?.cancel()
        val targets = listOf(centerIndex + 1, centerIndex - 1)
            .filter { it in pages.indices }
            .map { index ->
                pages[index].copy(
                    crop = pages[index].crop,
                    scanParameters = pages[index].scanParameters.copy()
                )
            }
            .filter { page ->
                val key = previewKey(page, maxLongEdge)
                pagePreviewCache[key]?.isRecycled != false && pagePreviewInflight.add(key)
            }
        if (targets.isEmpty()) return
        previewPrefetchJob = lifecycleScope.launch {
            targets.forEach { page ->
                val key = previewKey(page, maxLongEdge)
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderPageBitmap(page, maxLongEdge, true)
                    }
                }.onSuccess { bitmap ->
                    pagePreviewInflight.remove(key)
                    rememberPreviewBitmap(key, bitmap)
                    bitmap.recycle()
                }.onFailure {
                    pagePreviewInflight.remove(key)
                }
            }
        }
    }

    private fun attachPreviewSwipe(
        preview: View,
        incomingPreview: View,
        targetForDirection: (Int) -> Int?,
        onPrepareTarget: (Int, Int) -> Unit,
        onCommitTarget: (Int) -> Unit,
        onCancel: () -> Unit
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var swiping = false
        var ignoreSwipe = false
        var direction = 0
        var targetIndex: Int? = null
        preview.setOnTouchListener { view, event ->
            val zoomed = (preview as? ZoomableImageView)?.isZoomedIn() == true
            if (event.pointerCount > 1 || (zoomed && !swiping)) {
                ignoreSwipe = true
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    swiping = false
                    ignoreSwipe = zoomed
                    direction = 0
                    targetIndex = null
                    view.animate().cancel()
                    incomingPreview.animate().cancel()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignoreSwipe) return@setOnTouchListener false
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!swiping) {
                        if (abs(dx) < touchSlop || abs(dx) <= abs(dy) * 1.15f) {
                            return@setOnTouchListener false
                        }
                        swiping = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        direction = if (dx < 0f) 1 else -1
                        targetIndex = targetForDirection(direction)
                        targetIndex?.let { onPrepareTarget(it, direction) }
                    }
                    val width = view.width.takeIf { it > 0 }?.toFloat() ?: 1f
                    if (targetIndex == null) {
                        view.translationX = (dx * 0.18f).coerceIn(-42.dp.toFloat(), 42.dp.toFloat())
                        incomingPreview.visibility = View.GONE
                        return@setOnTouchListener true
                    }
                    val drag = dx.coerceIn(-width, width)
                    view.translationX = drag
                    incomingPreview.visibility = View.VISIBLE
                    incomingPreview.translationX = drag + direction * width
                    view.alpha = 1f
                    incomingPreview.alpha = 1f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (ignoreSwipe) {
                        ignoreSwipe = false
                        return@setOnTouchListener false
                    }
                    if (!swiping) return@setOnTouchListener false
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val width = view.width.takeIf { it > 0 }?.toFloat() ?: 1f
                    val threshold = min(width * 0.18f, 96.dp.toFloat()).coerceAtLeast(48.dp.toFloat())
                    val shouldSwitch = targetIndex != null &&
                        abs(dx) > threshold &&
                        abs(dx) > abs(dy) * 1.08f
                    val committedTarget = targetIndex
                    swiping = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    targetIndex = null
                    if (shouldSwitch && committedTarget != null) {
                        val endX = -direction * width
                        view.animate()
                            .translationX(endX)
                            .alpha(1f)
                            .setDuration(190L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.25f))
                            .start()
                        incomingPreview.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(190L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.25f))
                            .withEndAction {
                                onCommitTarget(committedTarget)
                                view.translationX = 0f
                                view.alpha = 1f
                                incomingPreview.translationX = 0f
                                incomingPreview.alpha = 1f
                                incomingPreview.visibility = View.GONE
                            }
                            .start()
                    } else {
                        view.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(160L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
                            .start()
                        incomingPreview.animate()
                            .translationX(direction * width)
                            .alpha(1f)
                            .setDuration(160L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
                            .withEndAction {
                                incomingPreview.visibility = View.GONE
                                onCancel()
                            }
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ignoreSwipe = false
                    swiping = false
                    targetIndex = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(120L)
                        .start()
                    incomingPreview.visibility = View.GONE
                    onCancel()
                    false
                }
                else -> false
            }
        }
    }

    private fun refreshPreview(
        preview: ImageView,
        progress: ProgressBar,
        preserveZoom: Boolean = false,
        maxLongEdge: Int = 1500
    ) {
        val page = pages.getOrNull(selectedPageIndex) ?: return
        val snapshot = page.copy(
            crop = page.crop,
            scanParameters = page.scanParameters.copy()
        )
        val stableId = page.stableId
        val key = previewKey(snapshot, maxLongEdge)
        val generation = ++previewRenderGeneration
        val zoomState = if (preserveZoom && preview is ZoomableImageView) {
            preview.captureZoomState()
        } else {
            null
        }
        pendingPreviewRunnable?.let(previewHandler::removeCallbacks)
        pendingPreviewRunnable = null
        previewJob?.cancel()
        cachedPreviewCopy(snapshot, maxLongEdge)?.let { cached ->
            previewBitmap?.recycle()
            previewBitmap = cached
            if (preserveZoom && preview is ZoomableImageView) {
                preview.setImageBitmapPreservingZoom(cached, zoomState)
            } else {
                preview.setImageBitmap(cached)
            }
            progress.animate().cancel()
            progress.visibility = View.GONE
            prefetchNeighborPreviews(selectedPageIndex, maxLongEdge)
            return
        }
        progress.animate().cancel()
        progress.alpha = 0f
        progress.visibility = View.VISIBLE
        progress.animate().alpha(1f).setStartDelay(160L).setDuration(120L).start()
        previewJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    renderPageBitmap(snapshot, maxLongEdge, true)
                }
            }.onSuccess { bitmap ->
                val samePage = pages.getOrNull(selectedPageIndex)?.stableId == stableId
                if (!isActive || generation != previewRenderGeneration || !samePage) {
                    bitmap.recycle()
                    return@onSuccess
                }
                rememberPreviewBitmap(key, bitmap)
                previewBitmap?.recycle()
                previewBitmap = bitmap
                if (preserveZoom && preview is ZoomableImageView) {
                    preview.setImageBitmapPreservingZoom(bitmap, zoomState)
                } else {
                    preview.setImageBitmap(bitmap)
                }
                preview.animate().alpha(1f).setDuration(90L).start()
                progress.animate().cancel()
                progress.alpha = 1f
                progress.visibility = View.GONE
                prefetchNeighborPreviews(selectedPageIndex, maxLongEdge)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                progress.animate().cancel()
                progress.visibility = View.GONE
                showError(error)
            }
        }
    }

    private fun renderPageBitmap(page: DocumentPage, maxLongEdge: Int, applyFilter: Boolean): Bitmap {
        val decoded = BitmapDecoder.decode(contentResolver, page.sourceUri, maxLongEdge)
        val rotated = BitmapDecoder.rotate(decoded, page.rotationDegrees)
        if (rotated !== decoded) decoded.recycle()
        val cropped = BitmapDecoder.crop(rotated, page.crop)
        if (cropped !== rotated) rotated.recycle()
        if (!applyFilter) return cropped
        val rendered = filterRenderer.render(cropped, page.scanParameters)
        if (rendered !== cropped) cropped.recycle()
        return rendered
    }

    private fun openPictureSelector(insertIndex: Int = pages.size) {
        installPictureSelectorRectangleSelectHook()
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setSelectionMode(SelectModeConfig.MULTIPLE)
            .setLanguage(LanguageConfig.CHINESE)
            .setMaxSelectNum(500)
            .setSelectorUIStyle(selectionOrderPickerStyle())
            .setImageEngine(GlideImageEngine.create())
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    importPictureSelectorMedia(result, insertIndex)
                }

                override fun onCancel() = Unit
            })
    }

    private fun installPictureSelectorRectangleSelectHook() {
        var callbacks: Application.ActivityLifecycleCallbacks? = null
        callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (!isPictureSelectorActivity(activity)) return
                activity.window.decorView.post {
                    attachPictureSelectorRectangleSelect(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (!isPictureSelectorActivity(activity)) return
                callbacks?.let(application::unregisterActivityLifecycleCallbacks)
                callbacks = null
            }
        }
        application.registerActivityLifecycleCallbacks(callbacks!!)
    }

    private fun isPictureSelectorActivity(activity: Activity): Boolean {
        return activity.javaClass.name.startsWith("com.luck.picture.lib.basic.PictureSelector")
    }

    private fun attachPictureSelectorRectangleSelect(activity: Activity) {
        val recycler = activity.findViewById<RecyclerView>(PictureSelectorR.id.recycler)
            ?: findFirstRecyclerView(activity.window.decorView)
            ?: return
        if (recycler.getTag(R.id.tag_rect_select_attached) == true) return
        recycler.setTag(R.id.tag_rect_select_attached, true)
        recycler.addOnItemTouchListener(PictureSelectorRectangleTouchListener())
    }

    private fun findFirstRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findFirstRecyclerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private inner class PictureSelectorRectangleTouchListener : RecyclerView.SimpleOnItemTouchListener() {
        private val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private val longPressDelayMs = max(180, ViewConfiguration.getLongPressTimeout() - 70).toLong()
        private val autoScrollEdge = 92.dp
        private val autoScroller = EdgeAutoScroller(autoScrollEdge)
        private var downX = 0f
        private var downY = 0f
        private var startPosition = RecyclerView.NO_POSITION
        private var currentPosition = RecyclerView.NO_POSITION
        private var dragActive = false
        private var longPressRunnable: Runnable? = null
        private val dragSelectedPositions = linkedSetOf<Int>()
        private val rankedPositions = linkedSetOf<Int>()
        private var lastRectanglePositions: List<Int> = emptyList()

        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetDragState(rv)
                    downX = event.x
                    downY = event.y
                    startPosition = positionAt(rv, event.x, event.y)
                    currentPosition = startPosition
                    if (isSelectablePickerPosition(rv, startPosition)) {
                        longPressRunnable = Runnable { beginRectangleDrag(rv) }.also {
                            previewHandler.postDelayed(it, longPressDelayMs)
                        }
                    }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragActive) {
                        if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                            cancelRectangleLongPress()
                        }
                        return false
                    }
                    updateRectangleSelection(rv, event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (dragActive) {
                        finishRectangleDrag(rv)
                        return true
                    } else {
                        if (event.actionMasked == MotionEvent.ACTION_UP && shouldSelectByTap(rv, event)) {
                            cancelRectangleLongPress()
                            togglePickerPosition(rv, startPosition)
                            return true
                        }
                        cancelRectangleLongPress()
                    }
                    return false
                }
            }
            return dragActive
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            if (!dragActive) return
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateRectangleSelection(rv, event.x, event.y)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> finishRectangleDrag(rv)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        private fun beginRectangleDrag(rv: RecyclerView) {
            if (dragActive || !isSelectablePickerPosition(rv, startPosition)) return
            dragActive = true
            rv.cancelLongPress()
            rv.parent?.requestDisallowInterceptTouchEvent(true)
            updateRectangleSelection(rv, downX, downY)
        }

        private fun updateRectangleSelection(rv: RecyclerView, x: Float, y: Float) {
            currentPosition = positionAtOrEdge(rv, x, y).takeIf {
                isSelectablePickerPosition(rv, it)
            } ?: currentPosition
            if (!isSelectablePickerPosition(rv, currentPosition)) return

            val targetPositions = rectanglePickerPositions(rv, startPosition, currentPosition)
            val targetSet = targetPositions.toSet()
            dragSelectedPositions.filter { it !in targetSet }.toList().forEach { position ->
                if (isPickerPositionSelected(rv, position)) {
                    togglePickerPosition(rv, position)
                }
                dragSelectedPositions.remove(position)
            }
            rankedPositions.filter { it !in targetSet }.toList().forEach { position ->
                clearRectangleRank(rv, position)
                rankedPositions.remove(position)
            }
            targetPositions.forEach { position ->
                if (position !in dragSelectedPositions && !isPickerPositionSelected(rv, position)) {
                    if (togglePickerPosition(rv, position)) {
                        dragSelectedPositions += position
                    }
                }
            }
            applyRectangleRanks(rv, targetPositions)
            lastRectanglePositions = targetPositions
            autoScrollPicker(rv, y)
        }

        private fun finishRectangleDrag(rv: RecyclerView) {
            resetDragState(rv)
        }

        private fun cancelRectangleLongPress() {
            longPressRunnable?.let(previewHandler::removeCallbacks)
            longPressRunnable = null
        }

        private fun resetDragState(rv: RecyclerView) {
            cancelRectangleLongPress()
            autoScroller.stop()
            rv.parent?.requestDisallowInterceptTouchEvent(false)
            startPosition = RecyclerView.NO_POSITION
            currentPosition = RecyclerView.NO_POSITION
            dragActive = false
            dragSelectedPositions.clear()
            rankedPositions.clear()
            lastRectanglePositions = emptyList()
        }

        private fun shouldSelectByTap(rv: RecyclerView, event: MotionEvent): Boolean {
            if (!isSelectablePickerPosition(rv, startPosition)) return false
            if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) return false
            return !isPreviewTapZone(rv, startPosition, downX, downY)
        }

        private fun isPreviewTapZone(rv: RecyclerView, position: Int, x: Float, y: Float): Boolean {
            val child = rv.layoutManager?.findViewByPosition(position) ?: return false
            val localX = x - child.left
            val localY = y - child.top
            val previewZone = min(62.dp, min(child.width, child.height) / 3)
            return localX in 0f..previewZone.toFloat() &&
                localY in (child.height - previewZone).toFloat()..child.height.toFloat()
        }

        private fun positionAt(rv: RecyclerView, x: Float, y: Float): Int {
            val child = rv.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
            return rv.getChildAdapterPosition(child)
        }

        private fun positionAtOrEdge(rv: RecyclerView, x: Float, y: Float): Int {
            val clampedX = x.coerceIn(1f, (rv.width - 1).coerceAtLeast(1).toFloat())
            val clampedY = y.coerceIn(1f, (rv.height - 1).coerceAtLeast(1).toFloat())
            positionAt(rv, clampedX, clampedY).takeIf { it != RecyclerView.NO_POSITION }?.let {
                return it
            }
            val layoutManager = rv.layoutManager as? GridLayoutManager ?: return RecyclerView.NO_POSITION
            return when {
                y < 0f -> layoutManager.findFirstVisibleItemPosition()
                y > rv.height -> layoutManager.findLastVisibleItemPosition()
                else -> RecyclerView.NO_POSITION
            }
        }

        private fun rectanglePickerPositions(rv: RecyclerView, start: Int, current: Int): List<Int> {
            val layoutManager = rv.layoutManager as? GridLayoutManager ?: return emptyList()
            val span = layoutManager.spanCount.coerceAtLeast(1)
            val startRow = start / span
            val currentRow = current / span
            val startColumn = start % span
            val currentColumn = current % span
            val minRow = min(startRow, currentRow)
            val maxRow = max(startRow, currentRow)
            val minColumn = min(startColumn, currentColumn)
            val maxColumn = max(startColumn, currentColumn)
            val positions = ArrayList<Int>()
            val rows = if (currentRow >= startRow) {
                minRow..maxRow
            } else {
                maxRow downTo minRow
            }
            val columns = if (currentColumn >= startColumn) {
                minColumn..maxColumn
            } else {
                maxColumn downTo minColumn
            }
            for (row in rows) {
                for (column in columns) {
                    val position = row * span + column
                    if (isSelectablePickerPosition(rv, position)) {
                        positions += position
                    }
                }
            }
            return positions
        }

        private fun autoScrollPicker(rv: RecyclerView, y: Float) {
            autoScroller.update(rv, y)
        }

        private fun isSelectablePickerPosition(rv: RecyclerView, position: Int): Boolean {
            if (position == RecyclerView.NO_POSITION) return false
            val adapter = rv.adapter as? PictureImageGridAdapter ?: return false
            if (position !in 0 until adapter.itemCount) return false
            if (adapter.isDisplayCamera && position == 0) return false
            return pickerMediaAt(adapter, position) != null
        }

        private fun pickerMediaAt(adapter: PictureImageGridAdapter, position: Int): LocalMedia? {
            val dataIndex = position - if (adapter.isDisplayCamera) 1 else 0
            return adapter.getData().getOrNull(dataIndex)
        }

        private fun isPickerPositionSelected(rv: RecyclerView, position: Int): Boolean {
            val adapter = rv.adapter as? PictureImageGridAdapter ?: return false
            return pickerMediaAt(adapter, position)?.isChecked == true
        }

        private fun applyRectangleRanks(rv: RecyclerView, positions: List<Int>) {
            val adapter = rv.adapter as? PictureImageGridAdapter ?: return
            positions.forEachIndexed { index, position ->
                pickerMediaAt(adapter, position)?.customData = "$pickerRectangleRankPrefix${index + 1}"
                rankedPositions += position
            }
        }

        private fun clearRectangleRank(rv: RecyclerView, position: Int) {
            val adapter = rv.adapter as? PictureImageGridAdapter ?: return
            val media = pickerMediaAt(adapter, position) ?: return
            if (media.customData?.startsWith(pickerRectangleRankPrefix) == true) {
                media.customData = null
            }
        }

        private fun togglePickerPosition(rv: RecyclerView, position: Int): Boolean {
            val child = rv.layoutManager?.findViewByPosition(position) ?: return false
            val checkTarget = child.findViewById<View>(PictureSelectorR.id.btnCheck)
                ?: child.findViewById(PictureSelectorR.id.tvCheck)
                ?: child
            return checkTarget.performClick()
        }
    }

    private fun importPictureSelectorMedia(result: List<LocalMedia>, insertIndex: Int = pages.size) {
        if (result.isEmpty()) return
        rememberUndo("导入图片")
        val ordered = result.mapIndexed { index, media -> index to media }
            .sortedWith(
                compareBy<Pair<Int, LocalMedia>> { (_, media) ->
                    pickerRectangleRank(media) ?: media.num.takeIf { it > 0 } ?: Int.MAX_VALUE
                }.thenBy { (index, _) ->
                    index
                }
            )
            .map { (_, media) -> media }
        ordered.forEachIndexed { index, media ->
            val path = firstNonBlank(media.availablePath, media.realPath, media.path)
            require(path.isNotBlank()) { "相册选择器返回了空路径。" }
            pages += DocumentPage(
                sourceUri = uriFromPickerPath(path),
                title = "相册-${pages.size + index + 1}"
            )
        }
        val addedCount = ordered.size
        val appendedStart = pages.size - addedCount
        val insertedStart = insertIndex.coerceIn(0, appendedStart)
        if (insertedStart < appendedStart) {
            val addedPages = pages.subList(appendedStart, pages.size).toList()
            repeat(addedCount) { pages.removeAt(pages.lastIndex) }
            pages.addAll(insertedStart, addedPages)
        }
        selectedPageIndex = insertedStart.coerceIn(0, pages.lastIndex)
        exportBoundsCache.clear()
        invalidateExportEstimateCache()
        anchorStripNearSelected()
        showEditor()
    }

    private fun pickerRectangleRank(media: LocalMedia): Int? {
        val customData = media.customData ?: return null
        if (!customData.startsWith(pickerRectangleRankPrefix)) return null
        return customData.removePrefix(pickerRectangleRankPrefix).toIntOrNull()
    }

    private fun importPdf(uri: Uri, insertIndex: Int = pages.size) {
        lifecycleScope.launch {
            val status = TextView(this@MainActivity).apply {
                text = "正在导入 PDF..."
                textSize = 14f
                setTextColor(textPrimary)
                setPadding(18.dp, 12.dp, 18.dp, 0)
            }
            val progress = ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            }
            val panel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(progress)
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("导入 PDF")
                .setView(panel)
                .setCancelable(false)
                .create()
            dialog.show()
            runCatching {
                withContext(Dispatchers.IO) {
                    pdfPageImporter.importPdf(uri)
                }
            }.onSuccess { importedPages ->
                dialog.dismiss()
                rememberUndo("导入 PDF")
                val startIndex = insertIndex.coerceIn(0, pages.size)
                pages.addAll(startIndex, importedPages)
                selectedPageIndex = startIndex.coerceIn(0, pages.lastIndex)
                exportBoundsCache.clear()
                exportSampleBytesCache.clear()
                anchorStripNearSelected()
                showEditor()
                Toast.makeText(this@MainActivity, "已导入 ${importedPages.size} 页 PDF。", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                dialog.dismiss()
                showError(error)
            }
        }
    }

    private fun selectionOrderPickerStyle(): PictureSelectorStyle {
        val mainStyle = SelectMainStyle().apply {
            setSelectNumberStyle(true)
            setPreviewSelectNumberStyle(true)
            setSelectBackground(R.drawable.ps_checkbox_selector)
            setSelectBackgroundResources(R.drawable.ps_select_complete_bg)
            setPreviewSelectBackground(R.drawable.bg_picker_number_selected)
            setSelectNormalBackgroundResources(R.drawable.ps_select_complete_normal_bg)
            setSelectTextColor(Color.WHITE)
            setPreviewSelectTextColor(Color.WHITE)
            setAdapterSelectTextColor(Color.WHITE)
            setAdapterSelectTextSize(13)
            setAdapterSelectClickArea(44.dp)
        }
        val bottomStyle = BottomNavBarStyle().apply {
            setBottomNarBarBackgroundColor(Color.rgb(45, 48, 54))
            setBottomNarBarHeight(58.dp)
            setCompleteCountTips(true)
            setBottomSelectNumResources(R.drawable.bg_picker_number_selected)
            setBottomSelectNumTextColor(Color.WHITE)
            setBottomSelectNumTextSize(12)
        }
        val titleStyle = TitleBarStyle().apply {
            setTitleDefaultText("相机胶卷")
            setTitleCancelText("取消")
            setTitleBackgroundColor(Color.rgb(45, 48, 54))
            setTitleTextColor(Color.WHITE)
            setTitleCancelTextColor(Color.WHITE)
            setDisplayTitleBarLine(false)
        }
        return PictureSelectorStyle().apply {
            setSelectMainStyle(mainStyle)
            setBottomBarStyle(bottomStyle)
            setTitleBarStyle(titleStyle)
        }
    }

    private fun importSelectedImages(insertIndex: Int = pages.size) {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "请至少选择一张图片。", Toast.LENGTH_SHORT).show()
            return
        }
        rememberUndo("导入图片")
        val startIndex = insertIndex.coerceIn(0, pages.size)
        val addedPages = selectedImages.values.mapIndexed { index, image ->
            DocumentPage(
                sourceUri = image.uri,
                title = image.displayName.ifBlank { "相册-${index + 1}" }
            )
        }
        pages.addAll(startIndex, addedPages)
        selectedImages.clear()
        selectedPageIndex = startIndex.coerceIn(0, pages.lastIndex)
        exportBoundsCache.clear()
        invalidateExportEstimateCache()
        anchorStripNearSelected()
        showEditor()
    }

    private fun rotateCurrentPage(delta: Int, preview: ImageView, progress: ProgressBar) {
        val page = pages.getOrNull(selectedPageIndex) ?: return
        rememberUndo("旋转图片")
        page.rotationDegrees = ((page.rotationDegrees + delta) % 360 + 360) % 360
        invalidateExportEstimateCache()
        refreshPreview(preview, progress)
    }

    private fun deleteCurrentPage(preserveStripAnchor: Boolean = false) {
        if (pages.isEmpty()) return
        rememberUndo("删除图片")
        pages.removeAt(selectedPageIndex)
        selectedPageIndex = selectedPageIndex.coerceAtMost(pages.lastIndex).coerceAtLeast(0)
        exportBoundsCache.clear()
        invalidateExportEstimateCache()
        if (preserveStripAnchor) {
            stripAnchorIndex = stripAnchorIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        } else {
            anchorStripNearSelected()
        }
        showEditor()
    }

    private fun showExportOptions() {
        if (pages.isEmpty()) return
        var clarity = 52
        var compress = false
        var exportFormat = ExportFormat.PDF
        var exportScope = ExportScope.ALL
        var rangeStart = 1
        var rangeEnd = pages.size
        var pdfPageSize = PdfPageSize.A4
        var customPageWidthPt = PdfPageSize.A4.widthPt
        var customPageHeightPt = PdfPageSize.A4.heightPt
        var estimateJob: Job? = null
        var estimateRunnable: Runnable? = null
        lateinit var dialog: AlertDialog

        val formatValue = menuValueText()
        val pageSizeValue = menuValueText()
        val scopeValue = menuValueText()
        val sizeValue = TextView(this).apply {
            text = "--"
            textSize = 24f
            includeFontPadding = false
            setTextColor(textPrimary)
        }
        val memoryValue = TextView(this).apply {
            text = "--"
            textSize = 15f
            includeFontPadding = false
            setTextColor(textPrimary)
        }
        val statusLine = TextView(this).apply {
            textSize = 12f
            setTextColor(textSecondary)
        }
        val clarityTitle = TextView(this).apply {
            textSize = 17f
            includeFontPadding = false
            setTextColor(textPrimary)
        }
        val optionLine = TextView(this).apply {
            textSize = 12f
            setTextColor(textSecondary)
        }

        fun currentOptions(): PdfExportOptions = optionsForClarity(clarity, compress).copy(
            pageSize = pdfPageSize,
            pageWidthPt = customPageWidthPt,
            pageHeightPt = customPageHeightPt
        )

        fun imageFormatFor(format: ExportFormat): ImageOutputFormat {
            return if (format == ExportFormat.PNG) ImageOutputFormat.PNG else ImageOutputFormat.JPG
        }

        fun selectedItems(): List<ExportItem> {
            val start: Int
            val end: Int
            when (exportScope) {
                ExportScope.ALL -> {
                    start = 1
                    end = pages.size
                }
                ExportScope.CURRENT -> {
                    start = selectedPageIndex + 1
                    end = selectedPageIndex + 1
                }
                ExportScope.RANGE -> {
                    start = rangeStart.coerceIn(1, pages.size)
                    end = rangeEnd.coerceIn(start, pages.size)
                }
            }
            return pages.subList(start - 1, end).mapIndexed { offset, page ->
                ExportItem(start - 1 + offset, page)
            }
        }

        fun scopeSummary(): String {
            return when (exportScope) {
                ExportScope.ALL -> "全部页面 1-${pages.size}"
                ExportScope.CURRENT -> "当前页面 ${selectedPageIndex + 1}"
                ExportScope.RANGE -> "${rangeStart.coerceIn(1, pages.size)}-${rangeEnd.coerceIn(rangeStart, pages.size)} 页"
            }
        }

        fun refreshStaticLabels() {
            val options = currentOptions()
            val items = selectedItems()
            formatValue.text = exportFormat.label
            pageSizeValue.text = pageSizeSummary(options)
            scopeValue.text = scopeSummary()
            clarityTitle.text = clarityName(clarity)
            optionLine.text = "长边 ${options.maxLongEdge}px · ${if (options.preCompressImages) "体积优先" else "速度优先"}"
            statusLine.text = "${items.size} 页 · 等待估算"
        }

        fun runEstimate() {
            val options = currentOptions()
            val items = selectedItems()
            if (items.isEmpty()) return
            estimateJob?.cancel()
            statusLine.text = "正在估算 ${items.size} 页..."
            estimateJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { estimateExport(options, exportFormat, items) }
                }.onSuccess { estimate ->
                    sizeValue.text = formatSize(estimate.pdfMb)
                    memoryValue.text = formatSize(estimate.peakMemoryMb)
                    statusLine.text = "${items.size} 页 · 快速估算 · ${formatPixels(estimate.totalPixels)}"
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    sizeValue.text = "无法估算"
                    memoryValue.text = "--"
                    statusLine.text = error.message ?: error::class.java.simpleName
                }
            }
        }

        fun scheduleEstimate() {
            refreshStaticLabels()
            estimateRunnable?.let(previewHandler::removeCallbacks)
            estimateRunnable = Runnable { runEstimate() }.also {
                previewHandler.postDelayed(it, 140L)
            }
        }

        val slider = SeekBar(this).apply {
            max = 100
            progress = clarity
            splitTrack = false
            progressTintList = ColorStateList.valueOf(googleBlue)
            thumbTintList = ColorStateList.valueOf(googleBlue)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(218, 224, 232))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    clarity = progress.coerceIn(0, 100)
                    scheduleEstimate()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = runEstimate()
            })
        }

        val compressToggle = CheckBox(this).apply {
            text = "体积优先"
            textSize = 14f
            setTextColor(textPrimary)
            isChecked = compress
            setOnCheckedChangeListener { _, checked ->
                compress = checked
                scheduleEstimate()
            }
        }

        fun chooseFormat() {
            val values = ExportFormat.values()
            AlertDialog.Builder(this)
                .setTitle("导出格式")
                .setItems(values.map { it.label }.toTypedArray()) { _, which ->
                    exportFormat = values[which]
                    scheduleEstimate()
                }
                .show()
        }

        fun choosePageSize() {
            val values = arrayOf(
                PdfPageSize.IMAGE,
                PdfPageSize.A4,
                PdfPageSize.A3,
                PdfPageSize.LETTER,
                PdfPageSize.CUSTOM
            )
            AlertDialog.Builder(this)
                .setTitle("页面大小")
                .setItems(values.map { it.label }.toTypedArray()) { _, which ->
                    val next = values[which]
                    if (next == PdfPageSize.CUSTOM) {
                        showCustomPageSizeDialog(customPageWidthPt, customPageHeightPt) { widthPt, heightPt ->
                            customPageWidthPt = widthPt
                            customPageHeightPt = heightPt
                            pdfPageSize = PdfPageSize.CUSTOM
                            scheduleEstimate()
                        }
                    } else {
                        pdfPageSize = next
                        scheduleEstimate()
                    }
                }
                .show()
        }

        fun chooseScope() {
            val values = ExportScope.values()
            AlertDialog.Builder(this)
                .setTitle("导出范围")
                .setItems(values.map { it.label }.toTypedArray()) { _, which ->
                    val next = values[which]
                    if (next == ExportScope.RANGE) {
                        showPageRangeDialog(pages.size, rangeStart, rangeEnd) { start, end ->
                            rangeStart = start
                            rangeEnd = end
                            exportScope = ExportScope.RANGE
                            scheduleEstimate()
                        }
                    } else {
                        exportScope = next
                        scheduleEstimate()
                    }
                }
                .show()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 18.dp, 22.dp, 8.dp)
            addView(TextView(this@MainActivity).apply {
                text = "导出"
                textSize = 28f
                includeFontPadding = false
                setTextColor(textPrimary)
                setPadding(0, 0, 0, 14.dp)
            })
            addView(menuRow("格式", formatValue) { chooseFormat() })
            addView(menuRow("页面大小", pageSizeValue) { choosePageSize() })
            addView(menuRow("范围", scopeValue) { chooseScope() })
            addView(smallButton("预览") {
                showExportPreviewAllPages(currentOptions(), exportFormat, selectedItems())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dp
                bottomMargin = 12.dp
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(Color.rgb(247, 249, 252), 16.dp, Color.TRANSPARENT)
                setPadding(15.dp, 13.dp, 15.dp, 13.dp)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = "预计大小"
                        textSize = 12f
                        setTextColor(textSecondary)
                    })
                    addView(sizeValue)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = "峰值内存"
                        textSize = 12f
                        setTextColor(textSecondary)
                    })
                    addView(memoryValue)
                })
            })
            addView(statusLine.apply { setPadding(2.dp, 8.dp, 2.dp, 12.dp) })
            addView(clarityTitle)
            addView(slider)
            addView(compressToggle)
            addView(optionLine.apply { setPadding(0, 6.dp, 0, 0) })
        }

        dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ ->
                val options = currentOptions()
                val items = selectedItems()
                if (items.isEmpty()) {
                    Toast.makeText(this, "没有可导出的页面。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (exportFormat) {
                    ExportFormat.PDF -> {
                        pendingExportOptions = options
                        pendingPdfPages = items.map { it.page }
                        createPdfLauncher.launch("扫描-${System.currentTimeMillis()}.pdf")
                    }
                    ExportFormat.JPG, ExportFormat.PNG -> {
                        val imageFormat = imageFormatFor(exportFormat)
                        pendingImageExportOptions = options
                        pendingImageFormat = imageFormat
                        pendingImageExportItems = items
                        if (items.size == 1) {
                            pendingSingleImageIndex = items.first().originalIndex
                            launchSingleImageExport(imageFormat, pendingSingleImageIndex)
                        } else {
                            exportImagesDirectoryLauncher.launch(null)
                        }
                    }
                }
            }
            .create()
        dialog.setOnDismissListener {
            estimateJob?.cancel()
            estimateRunnable?.let(previewHandler::removeCallbacks)
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, 24.dp, Color.TRANSPARENT))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(googleBlue)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(textSecondary)
            refreshStaticLabels()
            runEstimate()
        }
        dialog.show()
    }

    private fun menuValueText(): TextView {
        return TextView(this).apply {
            textSize = 15f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
        }
    }

    private fun menuRow(label: String, valueView: TextView, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dp
            setPadding(14.dp, 0, 12.dp, 0)
            background = rounded(Color.rgb(247, 249, 252), 14.dp, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp
            }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(textSecondary)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "⌄"
                textSize = 18f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(8.dp, 0, 0, 2.dp)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun showPageRangeDialog(
        totalPages: Int,
        initialStart: Int,
        initialEnd: Int,
        onApply: (start: Int, end: Int) -> Unit
    ) {
        val startInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialStart.coerceIn(1, totalPages).toString())
            selectAll()
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        val endInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialEnd.coerceIn(1, totalPages).toString())
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 4.dp, 8.dp, 0)
            addView(TextView(this@MainActivity).apply {
                text = "开始页"
                textSize = 13f
                setTextColor(textSecondary)
            })
            addView(startInput)
            addView(TextView(this@MainActivity).apply {
                text = "结束页"
                textSize = 13f
                setTextColor(textSecondary)
                setPadding(0, 10.dp, 0, 0)
            })
            addView(endInput)
        }
        AlertDialog.Builder(this)
            .setTitle("指定页码")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val rawStart = startInput.text.toString().trim().toIntOrNull()
                val rawEnd = endInput.text.toString().trim().toIntOrNull()
                if (rawStart == null || rawEnd == null) {
                    Toast.makeText(this, "请输入有效页码。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val start = rawStart.coerceIn(1, totalPages)
                val end = rawEnd.coerceIn(start, totalPages)
                onApply(start, end)
            }
            .show()
    }

    private fun showExportPreviewAllPages(
        options: PdfExportOptions,
        format: ExportFormat,
        items: List<ExportItem>
    ) {
        if (items.isEmpty()) return
        var renderJob: Job? = null
        val previews = mutableListOf<Bitmap>()
        val pageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 10.dp, 10.dp, 16.dp)
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        val status = TextView(this).apply {
            text = "渲染 0/${items.size} 页"
            textSize = 12f
            setTextColor(textSecondary)
            includeFontPadding = false
        }
        val progress = modernProgress().apply {
            visibility = View.VISIBLE
        }
        val scroll = ScrollView(this).apply {
            addView(pageList)
            background = rounded(Color.rgb(246, 247, 249), 0, border)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                660.dp
            )
        }
        lateinit var dialog: AlertDialog
        val closeButton = compactButton("关闭") {
            dialog.dismiss()
        }.apply {
            setTextColor(googleBlue)
            background = rounded(Color.rgb(241, 246, 252), 14.dp, border)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(241, 246, 252))
        }
        val frame = FrameLayout(this).apply {
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            background = rounded(card, 0, Color.TRANSPARENT)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8.dp, 10.dp, 4.dp, 0)
                    addView(status, LinearLayout.LayoutParams(0, 36.dp, 1f))
                    addView(closeButton, LinearLayout.LayoutParams(
                        76.dp,
                        36.dp
                    ))
                })
            })
            addView(progress)
        }
        dialog = AlertDialog.Builder(this)
            .setView(frame)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT))
            renderJob = lifecycleScope.launch {
                val previewLongEdge = adaptiveOverallPreviewLongEdge(options.maxLongEdge, items.size)
                runCatching {
                    items.forEachIndexed { index, item ->
                        val bitmap = withContext(Dispatchers.IO) {
                            renderExportPreviewPageBitmap(item.page, options, format, previewLongEdge)
                        }
                        if (!dialog.isShowing || !isActive) {
                            bitmap.recycle()
                            throw CancellationException()
                        }
                        previews += bitmap
                        pageList.addView(TextView(this@MainActivity).apply {
                            text = "第 ${item.originalIndex + 1} 页"
                            textSize = 12f
                            setTextColor(textSecondary)
                            includeFontPadding = false
                            setPadding(4.dp, if (index == 0) 0 else 14.dp, 4.dp, 6.dp)
                        })
                        pageList.addView(ImageView(this@MainActivity).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            isClickable = true
                            minimumHeight = 120.dp
                            background = rounded(Color.WHITE, 0, Color.rgb(208, 213, 221))
                            setPadding(1.dp, 1.dp, 1.dp, 1.dp)
                            setImageBitmap(bitmap)
                            setOnClickListener {
                                showExportPreviewPageDetail(options, format, item)
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        })
                        status.text = "渲染 ${index + 1}/${items.size} 页 · ${format.label}"
                    }
                }.onSuccess {
                    progress.visibility = View.GONE
                    status.text = "${items.size} 页 · 点开单页查看完整清晰度"
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    progress.visibility = View.GONE
                    showError(error)
                }
            }
        }
        dialog.setOnDismissListener {
            renderJob?.cancel()
            previews.forEach(Bitmap::recycle)
        }
        dialog.show()
    }

    private fun adaptiveOverallPreviewLongEdge(exportLongEdge: Int, pageCount: Int): Int {
        val cap = when {
            pageCount >= 30 -> 980
            pageCount >= 16 -> 1150
            pageCount >= 8 -> 1400
            else -> 1800
        }
        return min(exportLongEdge, cap).coerceAtLeast(900)
    }

    private fun showExportPreviewPageDetail(
        options: PdfExportOptions,
        format: ExportFormat,
        item: ExportItem
    ) {
        var renderJob: Job? = null
        var preview: Bitmap? = null
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val image = ZoomableImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.BLACK)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progress = ProgressBar(this).apply {
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(image)
            addView(progress)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, safeTopPadding() + 8.dp, 14.dp, 8.dp)
                setBackgroundColor(Color.rgb(20, 22, 26))
                addView(TextView(this@MainActivity).apply {
                    text = "第 ${item.originalIndex + 1} 页 · ${format.label} 预览"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, 44.dp, 1f)
                })
                addView(compactButton("关闭") { dialog.dismiss() }.apply {
                    setTextColor(googleBlue)
                    background = rounded(Color.rgb(241, 246, 252), 14.dp, border)
                    backgroundTintList = ColorStateList.valueOf(Color.rgb(241, 246, 252))
                })
            })
            addView(frame)
        }
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.BLACK, 0, Color.TRANSPARENT))
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            renderJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderExportPreviewBitmap(item.page, options, format)
                    }
                }.onSuccess { bitmap ->
                    if (!dialog.isShowing) {
                        bitmap.recycle()
                        return@onSuccess
                    }
                    preview = bitmap
                    image.setImageBitmap(bitmap)
                    progress.visibility = View.GONE
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    progress.visibility = View.GONE
                    showError(error)
                }
            }
        }
        dialog.setOnDismissListener {
            renderJob?.cancel()
            preview?.recycle()
        }
        dialog.show()
    }

    private fun showExportPreview(options: PdfExportOptions, items: List<ExportItem>) {
        val first = items.firstOrNull() ?: return
        var renderJob: Job? = null
        var preview: Bitmap? = null
        val image = ZoomableImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(35, 38, 44))
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                560.dp
            )
        }
        val progress = ProgressBar(this).apply {
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER)
        }
        val frame = FrameLayout(this).apply {
            addView(image)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("导出预览 · 第 ${first.originalIndex + 1} 页")
            .setView(frame)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            renderJob = lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        renderExportPreviewBitmap(first.page, options, ExportFormat.PDF)
                    }
                }.onSuccess { bitmap ->
                    if (!dialog.isShowing) {
                        bitmap.recycle()
                        return@onSuccess
                    }
                    preview = bitmap
                    image.setImageBitmap(bitmap)
                    progress.visibility = View.GONE
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    progress.visibility = View.GONE
                    showError(error)
                }
            }
        }
        dialog.setOnDismissListener {
            renderJob?.cancel()
            preview?.recycle()
        }
        dialog.show()
    }

    private fun renderExportPreviewBitmap(
        page: DocumentPage,
        options: PdfExportOptions,
        format: ExportFormat
    ): Bitmap {
        val previewLongEdge = min(options.maxLongEdge, exportSinglePreviewLongEdge).coerceAtLeast(mainPreviewLongEdge)
        val renderKey = previewKey(page, previewLongEdge)
        val rendered = cachedPreviewCopy(page, previewLongEdge)
            ?: renderPageBitmap(page, previewLongEdge, true).also {
                rememberPreviewBitmap(renderKey, it)
            }
        val exportLike = simulateExportPreviewCompression(rendered, options, format)
        if (exportLike !== rendered) rendered.recycle()
        if (options.pageSize == PdfPageSize.IMAGE) return exportLike

        val (pageWidthPt, pageHeightPt) = when (options.pageSize) {
            PdfPageSize.CUSTOM -> options.pageWidthPt to options.pageHeightPt
            else -> options.pageSize.widthPt to options.pageSize.heightPt
        }
        val scale = previewLongEdge.toFloat() / max(pageWidthPt, pageHeightPt).toFloat().coerceAtLeast(1f)
        val previewWidth = (pageWidthPt * scale).roundToInt().coerceAtLeast(1)
        val previewHeight = (pageHeightPt * scale).roundToInt().coerceAtLeast(1)
        val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.WHITE)
        val dest = fitCenterRect(
            exportLike.width.toFloat(),
            exportLike.height.toFloat(),
            previewWidth.toFloat(),
            previewHeight.toFloat()
        )
        canvas.drawBitmap(exportLike, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        exportLike.recycle()
        return preview
    }

    private fun renderExportPreviewPageBitmap(
        page: DocumentPage,
        options: PdfExportOptions,
        format: ExportFormat,
        previewLongEdge: Int
    ): Bitmap {
        val renderLongEdge = min(options.maxLongEdge, previewLongEdge).coerceAtLeast(360)
        val renderKey = previewKey(page, renderLongEdge)
        val cachedRendered = cachedPreviewCopy(page, renderLongEdge)
            ?: if (renderLongEdge <= mainPreviewLongEdge) {
                cachedPreviewCopy(page, mainPreviewLongEdge)
            } else {
                null
            }
        val rendered = cachedRendered ?: renderPageBitmap(page, renderLongEdge, true).also {
            rememberPreviewBitmap(renderKey, it)
        }
        val exportLike = simulateExportPreviewCompression(rendered, options, format)
        if (exportLike !== rendered) rendered.recycle()
        if (options.pageSize == PdfPageSize.IMAGE) return exportLike

        val (pageWidthPt, pageHeightPt) = when (options.pageSize) {
            PdfPageSize.CUSTOM -> options.pageWidthPt to options.pageHeightPt
            else -> options.pageSize.widthPt to options.pageSize.heightPt
        }
        val scale = previewLongEdge.toFloat() / max(pageWidthPt, pageHeightPt).toFloat().coerceAtLeast(1f)
        val previewWidth = (pageWidthPt * scale).roundToInt().coerceAtLeast(1)
        val previewHeight = (pageHeightPt * scale).roundToInt().coerceAtLeast(1)
        val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.WHITE)
        val dest = fitCenterRect(
            exportLike.width.toFloat(),
            exportLike.height.toFloat(),
            previewWidth.toFloat(),
            previewHeight.toFloat()
        )
        canvas.drawBitmap(exportLike, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        exportLike.recycle()
        return preview
    }

    private fun simulateExportPreviewCompression(
        source: Bitmap,
        options: PdfExportOptions,
        format: ExportFormat
    ): Bitmap {
        val shouldJpegPreview = format == ExportFormat.JPG ||
            (format == ExportFormat.PDF && options.preCompressImages)
        if (!shouldJpegPreview) return source
        val buffer = ByteArrayOutputStream()
        val ok = source.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality, buffer)
        if (!ok) return source
        val bytes = buffer.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: source
    }

    private fun fitCenterRect(srcWidth: Float, srcHeight: Float, maxWidth: Float, maxHeight: Float): RectF {
        val scale = min(maxWidth / srcWidth, maxHeight / srcHeight)
        val width = srcWidth * scale
        val height = srcHeight * scale
        val left = (maxWidth - width) / 2f
        val top = (maxHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun showCustomPageSizeDialog(
        widthPt: Int,
        heightPt: Int,
        onApply: (widthPt: Int, heightPt: Int) -> Unit
    ) {
        val widthInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatFloat(ptToMm(widthPt)))
            selectAll()
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        val heightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatFloat(ptToMm(heightPt)))
            setSingleLine(true)
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 4.dp, 8.dp, 0)
            addView(TextView(this@MainActivity).apply {
                text = "宽度 mm"
                textSize = 13f
                setTextColor(textSecondary)
            })
            addView(widthInput)
            addView(TextView(this@MainActivity).apply {
                text = "高度 mm"
                textSize = 13f
                setTextColor(textSecondary)
                setPadding(0, 10.dp, 0, 0)
            })
            addView(heightInput)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义页面大小")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val widthMm = widthInput.text.toString().trim().toFloatOrNull()
                val heightMm = heightInput.text.toString().trim().toFloatOrNull()
                if (widthMm == null || heightMm == null || widthMm <= 0f || heightMm <= 0f) {
                    Toast.makeText(this, "请输入有效页面尺寸。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onApply(mmToPt(widthMm), mmToPt(heightMm))
            }
            .show()
    }

    private fun optionsForClarity(clarity: Int, compress: Boolean): PdfExportOptions {
        val t = clarity.coerceIn(0, 100) / 100f
        val longEdge = (1100 + t * 2300).roundToInt()
        val quality = (62 + t * 30).roundToInt().coerceIn(55, 94)
        val exportLongEdge = if (compress) {
            (longEdge * 0.84f).roundToInt().coerceAtLeast(900)
        } else {
            longEdge
        }
        val exportQuality = if (compress) {
            (quality - 8).coerceIn(52, 88)
        } else {
            quality
        }
        return PdfExportOptions(
            maxLongEdge = exportLongEdge,
            jpegQuality = exportQuality,
            preCompressImages = compress
        )
    }

    private fun clarityName(clarity: Int): String {
        return when {
            clarity < 25 -> "快速预览"
            clarity < 60 -> "标准清晰"
            clarity < 84 -> "高清笔记"
            else -> "超清归档"
        }
    }

    private fun exportOptionLine(options: PdfExportOptions): String {
        return if (options.preCompressImages) {
            "${pageSizeSummary(options)} · ${options.maxLongEdge}px · 体积优先"
        } else {
            "${pageSizeSummary(options)} · ${options.maxLongEdge}px · 速度优先"
        }
    }

    private fun exportOptionLine(options: PdfExportOptions, format: ExportFormat): String {
        return when (format) {
            ExportFormat.PDF -> if (options.preCompressImages) {
                "PDF · ${pageSizeSummary(options)} · ${options.maxLongEdge}px · 体积优先"
            } else {
                "PDF · ${pageSizeSummary(options)} · ${options.maxLongEdge}px · 速度优先"
            }
            ExportFormat.JPG -> "JPG · ${options.maxLongEdge}px · JPEG ${options.jpegQuality}"
            ExportFormat.PNG -> "PNG · ${options.maxLongEdge}px · 无损"
        }
    }

    private fun pageSizeSummary(options: PdfExportOptions): String {
        return when (options.pageSize) {
            PdfPageSize.IMAGE -> "图片大小/无白边"
            PdfPageSize.CUSTOM -> {
                val width = ptToMm(options.pageWidthPt).roundToInt()
                val height = ptToMm(options.pageHeightPt).roundToInt()
                "自定义 ${width}×${height}mm"
            }
            else -> options.pageSize.label
        }
    }

    private fun estimateExport(
        options: PdfExportOptions,
        format: ExportFormat = ExportFormat.PDF,
        items: List<ExportItem> = pages.mapIndexed { index, page -> ExportItem(index, page) }
    ): ExportEstimate {
        val pageStats = items.mapIndexed { index, item ->
            val page = item.page
            val (width, height) = estimateRenderedSize(page, options.maxLongEdge)
            val renderedPixels = width.toLong() * height.toLong()
            val (decodedWidth, decodedHeight) = estimateDecodedSize(page, options.maxLongEdge)
            PageEstimateStats(
                index = index,
                renderedWidth = width,
                renderedHeight = height,
                renderedPixels = renderedPixels,
                decodedPixels = decodedWidth.toLong() * decodedHeight.toLong()
            )
        }
        val totalPixels = pageStats.sumOf { it.renderedPixels }
        val estimatedPayloadBytes = pageStats.sumOf { stats ->
            val page = items[stats.index].page
            estimateFastPayloadBytes(page, stats, options, format)
        }.toDouble()
        val outputBytes = when (format) {
            ExportFormat.PDF -> estimateFastPdfOverheadBytes(pageStats, options) + estimatedPayloadBytes
            ExportFormat.JPG, ExportFormat.PNG -> estimatedPayloadBytes
        }
        val maxRenderedPixels = pageStats.maxOf { it.renderedPixels }
        val maxDecodedPixels = pageStats.maxOf { it.decodedPixels }
        val renderedCopies = if (options.preCompressImages) 2.4 else 1.6
        val peakMemoryMb = (
            maxDecodedPixels * 4.0 * 1.8 +
                maxRenderedPixels * 4.0 * renderedCopies
        ) / (1024.0 * 1024.0) + 24.0
        return ExportEstimate(
            pdfMb = (outputBytes / (1024.0 * 1024.0)).coerceAtLeast(0.1),
            peakMemoryMb = peakMemoryMb.coerceAtLeast(24.0),
            totalPixels = totalPixels,
            sampledPages = 0
        )
    }

    private fun estimateFastPayloadBytes(
        page: DocumentPage,
        stats: PageEstimateStats,
        options: PdfExportOptions,
        format: ExportFormat
    ): Long {
        val pixels = stats.renderedPixels.coerceAtLeast(1L)
        val complexityFactor = (estimateFilterComplexity(page) / 1.04).coerceIn(0.72, 1.28)
        if (format == ExportFormat.PNG) {
            val sourceBytes = sourceByteLength(page.sourceUri)
            val sourcePixels = estimateSourcePixels(page).coerceAtLeast(1L)
            val renderedRatio = pixels.toDouble() / sourcePixels.toDouble()
            val sourceAwareBytes = sourceBytes
                ?.takeIf { it > 0L }
                ?.let { it * renderedRatio * 1.12 }
                ?: 0.0
            val pngBytesPerPixel = if (page.scanParameters.grayscale) 0.62 else 0.94
            val analyticBytes = pixels * pngBytesPerPixel * complexityFactor
            val pngBytes = max(analyticBytes, sourceAwareBytes)
            return pngBytes.roundToLong().coerceAtLeast(2_048L)
        }

        val qualityFactor = (options.jpegQuality / 82.0).coerceIn(0.58, 1.24)
        val compressionFactor = if (options.preCompressImages) 0.72 else 1.0
        val formatFactor = if (format == ExportFormat.PDF) 1.08 else 1.0
        val analyticBytesPerPixel = estimatedPdfBytesPerPixel(options) * complexityFactor
        val analyticBytes = pixels * analyticBytesPerPixel
        val sourceBytes = sourceByteLength(page.sourceUri)
        val sourcePixels = estimateSourcePixels(page).coerceAtLeast(1L)
        val calibratedBytes = if (sourceBytes != null && sourceBytes > 0L) {
            val renderedRatio = pixels.toDouble() / sourcePixels.toDouble()
            val sourceScaledBytes = sourceBytes * renderedRatio * qualityFactor * compressionFactor
            // Calibrated with local photo samples: source density is the strongest cheap predictor.
            sourceScaledBytes * 0.45 + analyticBytes
        } else {
            analyticBytes * 3.1
        }
        return (calibratedBytes * formatFactor * 1.05)
            .coerceIn(pixels * 0.028, pixels * 2.4)
            .roundToLong()
            .coerceAtLeast(2_048L)
    }

    private fun estimateFastPdfOverheadBytes(
        pageStats: List<PageEstimateStats>,
        options: PdfExportOptions
    ): Long {
        val pageObjects = pageStats.size * 1_450L
        val sizeObjects = if (options.pageSize == PdfPageSize.IMAGE) {
            pageStats.size * 320L
        } else {
            420L
        }
        return 6_000L + pageObjects + sizeObjects
    }

    private fun estimateFilterComplexity(page: DocumentPage): Double {
        val parameters = page.scanParameters
        var factor = 1.0
        factor += kotlin.math.abs(parameters.contrast - 1f) * 0.10
        factor += parameters.sharpen.coerceAtLeast(0f) * 0.06
        factor += parameters.paperClean.coerceAtLeast(0f) * 0.05
        factor -= parameters.inkBoost.coerceAtLeast(0f) * 0.04
        if (parameters.grayscale) factor *= 0.82
        if (parameters.invert) factor *= 0.98
        return factor.coerceIn(0.68, 1.35)
    }

    private fun estimateSourcePixels(page: DocumentPage): Long {
        val bounds = exportBoundsCache.getOrPut(page.sourceUri.toString()) {
            BitmapDecoder.readBounds(contentResolver, page.sourceUri)
        }
        return bounds.width.toLong() * bounds.height.toLong()
    }

    private fun sourceByteLength(uri: Uri): Long? {
        val key = uri.toString()
        if (exportSourceBytesCache.containsKey(key)) return exportSourceBytesCache[key]
        val length = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()
            ?: queryOpenableSize(uri)
        exportSourceBytesCache[key] = length
        return length
    }

    private fun queryOpenableSize(uri: Uri): Long? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex < 0 || cursor.isNull(sizeIndex)) {
                    null
                } else {
                    cursor.getLong(sizeIndex).takeIf { it > 0L }
                }
            }
        }.getOrNull()
    }

    private fun chooseEstimateSamples(pageStats: List<PageEstimateStats>): List<Int> {
        if (pageStats.size <= 8) return pageStats.map { it.index }

        val candidates = linkedSetOf<Int>()
        candidates += selectedPageIndex.coerceIn(pageStats.indices)
        candidates += 0
        candidates += pageStats.lastIndex
        candidates += pageStats.size / 2
        pageStats.maxByOrNull { it.renderedPixels }?.let { candidates += it.index }
        candidates += pageStats.size / 4
        candidates += pageStats.size * 3 / 4
        candidates += pageStats.size / 8
        candidates += pageStats.size * 7 / 8
        return candidates
            .filter { it in pageStats.indices }
            .take(8)
            .sorted()
    }

    private fun estimatePayloadFromSamples(samples: List<Pair<Long, Long>>, totalPixels: Long): Double {
        val measuredPayloadBytes = samples.sumOf { it.first.toDouble() }
        val measuredPayloadPixels = samples.sumOf { it.second.toDouble() }.coerceAtLeast(1.0)
        val weightedBytesPerPixel = measuredPayloadBytes / measuredPayloadPixels
        val medianBytesPerPixel = samples
            .map { it.first.toDouble() / it.second.toDouble().coerceAtLeast(1.0) }
            .sorted()
            .let { values -> values[values.size / 2] }
        val bytesPerPixel = (weightedBytesPerPixel * 0.8 + medianBytesPerPixel * 0.2)
            .coerceAtLeast(0.01)
        return totalPixels * bytesPerPixel
    }

    private fun estimatePagePdfPayloadBytes(page: DocumentPage, options: PdfExportOptions): Long {
        val measuredBytes = exportSampleBytesCache.getOrPut(estimateSampleCacheKey(page, options)) {
            pdfExporter.measurePagePdfBytes(page, options)
        }
        val blankOptions = if (options.pageSize == PdfPageSize.IMAGE) {
            val (width, height) = estimateRenderedSize(page, options.maxLongEdge)
            options.copy(
                pageSize = PdfPageSize.CUSTOM,
                pageWidthPt = width,
                pageHeightPt = height
            )
        } else {
            options
        }
        val blankBytes = estimateBlankPdfBytes(1, blankOptions)
        return (measuredBytes - blankBytes).coerceAtLeast(512L)
    }

    private fun estimatePageImagePayloadBytes(
        page: DocumentPage,
        options: PdfExportOptions,
        format: ImageOutputFormat
    ): Long {
        return exportSampleBytesCache.getOrPut(estimateSampleCacheKey(page, options, format)) {
            measureRenderedImageBytes(page, options, format)
        }.coerceAtLeast(512L)
    }

    private fun estimateBlankPdfBytes(pageStats: List<PageEstimateStats>, options: PdfExportOptions): Long {
        if (options.pageSize != PdfPageSize.IMAGE) {
            return estimateBlankPdfBytes(pageStats.size, options)
        }
        return pageStats.sumOf { stats ->
            estimateBlankPdfBytes(
                1,
                options.copy(
                    pageSize = PdfPageSize.CUSTOM,
                    pageWidthPt = stats.renderedWidth,
                    pageHeightPt = stats.renderedHeight
                )
            )
        }
    }

    private fun estimateBlankPdfBytes(pageCount: Int, options: PdfExportOptions): Long {
        val key = listOf(
            pageCount,
            options.pageSize.name,
            options.pageWidthPt,
            options.pageHeightPt
        ).joinToString(":")
        return exportBlankBytesCache.getOrPut(key) {
            pdfExporter.measureBlankPdfBytes(pageCount, options)
        }
    }

    private fun estimateSampleCacheKey(page: DocumentPage, options: PdfExportOptions): String {
        val parameters = page.scanParameters
        return listOf(
            page.sourceUri.toString(),
            page.rotationDegrees,
            page.crop.toString(),
            parameters.brightness,
            parameters.contrast,
            parameters.saturation,
            parameters.exposure,
            parameters.gamma,
            parameters.sharpen,
            parameters.blackPoint,
            parameters.whitePoint,
            parameters.shadows,
            parameters.highlights,
            parameters.temperature,
            parameters.tint,
            parameters.paperClean,
            parameters.inkBoost,
            parameters.grayscale,
            parameters.invert,
            parameters.modelKey,
            options.maxLongEdge,
            options.jpegQuality,
            options.preCompressImages,
            options.pageSize.name,
            options.pageWidthPt,
            options.pageHeightPt
        ).joinToString("|")
    }

    private fun estimateSampleCacheKey(
        page: DocumentPage,
        options: PdfExportOptions,
        format: ImageOutputFormat
    ): String {
        return estimateSampleCacheKey(page, options) + "|image|" + format.name
    }

    private fun estimateRenderedSize(page: DocumentPage, maxLongEdge: Int): Pair<Int, Int> {
        val bounds = exportBoundsCache.getOrPut(page.sourceUri.toString()) {
            BitmapDecoder.readBounds(contentResolver, page.sourceUri)
        }
        val normalizedRotation = ((page.rotationDegrees % 360) + 360) % 360
        var sourceWidth = bounds.width.toFloat()
        var sourceHeight = bounds.height.toFloat()
        if (normalizedRotation == 90 || normalizedRotation == 270) {
            val oldWidth = sourceWidth
            sourceWidth = sourceHeight
            sourceHeight = oldWidth
        }

        val crop = page.crop
        val rawWidth: Float
        val rawHeight: Float
        if (crop == null || crop.isFullFrame()) {
            rawWidth = sourceWidth
            rawHeight = sourceHeight
        } else {
            val topWidth = distance(crop.topLeft, crop.topRight, sourceWidth, sourceHeight)
            val bottomWidth = distance(crop.bottomLeft, crop.bottomRight, sourceWidth, sourceHeight)
            val leftHeight = distance(crop.topLeft, crop.bottomLeft, sourceWidth, sourceHeight)
            val rightHeight = distance(crop.topRight, crop.bottomRight, sourceWidth, sourceHeight)
            rawWidth = max(topWidth, bottomWidth)
            rawHeight = max(leftHeight, rightHeight)
        }

        val longEdge = max(rawWidth, rawHeight)
        val scale = if (longEdge > maxLongEdge) maxLongEdge / longEdge else 1f
        return (rawWidth * scale).roundToInt().coerceAtLeast(1) to
            (rawHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private fun estimateDecodedSize(page: DocumentPage, maxLongEdge: Int): Pair<Int, Int> {
        val bounds = exportBoundsCache.getOrPut(page.sourceUri.toString()) {
            BitmapDecoder.readBounds(contentResolver, page.sourceUri)
        }
        val sourceWidth = bounds.width.toFloat()
        val sourceHeight = bounds.height.toFloat()
        val longEdge = max(sourceWidth, sourceHeight)
        val scale = if (longEdge > maxLongEdge) maxLongEdge / longEdge else 1f
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private fun distance(
        first: com.scanclone.model.NormalizedPoint,
        second: com.scanclone.model.NormalizedPoint,
        sourceWidth: Float,
        sourceHeight: Float
    ): Float {
        return hypot(
            (second.x - first.x) * sourceWidth,
            (second.y - first.y) * sourceHeight
        )
    }

    private fun estimatedPdfBytesPerPixel(options: PdfExportOptions): Double {
        val qualityFactor = (options.jpegQuality / 82.0).coerceIn(0.70, 1.22)
        val base = if (options.preCompressImages) 0.052 else 0.061
        return base * qualityFactor
    }

    private fun formatPixels(pixels: Long): String {
        return if (pixels >= 1_000_000L) {
            String.format(Locale.CHINA, "%.1fMP", pixels / 1_000_000.0)
        } else {
            "${(pixels / 1_000L).coerceAtLeast(1L)}KP"
        }
    }

    private fun formatSize(mb: Double): String {
        return if (mb < 1.0) {
            "${(mb * 1024.0).roundToInt().coerceAtLeast(1)} KB"
        } else {
            String.format(Locale.CHINA, "%.1f MB", mb)
        }
    }

    private fun imageExportOptions(): PdfExportOptions {
        return optionsForClarity(74, true)
    }

    private fun defaultImageName(index: Int, format: ImageOutputFormat = ImageOutputFormat.JPG): String {
        return "扫描-${(index + 1).toString().padStart(3, '0')}.${format.extension}"
    }

    private fun launchSingleImageExport(format: ImageOutputFormat, pageIndex: Int) {
        val fileName = defaultImageName(pageIndex, format)
        when (format) {
            ImageOutputFormat.JPG -> createJpgLauncher.launch(fileName)
            ImageOutputFormat.PNG -> createPngLauncher.launch(fileName)
        }
    }

    private fun createExportProgressUi(
        title: String,
        initialStatus: String,
        detailText: String,
        total: Int
    ): ExportProgressUi {
        val status = TextView(this).apply {
            text = initialStatus
            textSize = 14f
            setTextColor(textSecondary)
            includeFontPadding = false
        }
        val percent = TextView(this).apply {
            text = "0%"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            includeFontPadding = false
            gravity = Gravity.END
        }
        val progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = total.coerceAtLeast(1)
            progress = 0
            minHeight = 8.dp
            progressTintList = ColorStateList.valueOf(applyGreen)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(226, 232, 240))
        }
        val detail = TextView(this).apply {
            text = detailText
            textSize = 12f
            setTextColor(textSecondary)
            includeFontPadding = false
            setPadding(0, 12.dp, 0, 0)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 26.dp, Color.rgb(222, 228, 236))
            elevation = 14.dp.toFloat()
            setPadding(22.dp, 21.dp, 22.dp, 18.dp)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.logo)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Color.rgb(245, 248, 252), 12.dp, border)
                    setPadding(3.dp, 3.dp, 3.dp, 3.dp)
                }, LinearLayout.LayoutParams(38.dp, 38.dp).apply {
                    rightMargin = 12.dp
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 24f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(textPrimary)
                        includeFontPadding = false
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "ChaoScan"
                        textSize = 12f
                        setTextColor(googleBlue)
                        includeFontPadding = false
                        setPadding(0, 5.dp, 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, 20.dp, 0, 12.dp)
                addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(percent, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8.dp
            ))
            addView(detail)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.TRANSPARENT, 0))
        }
        dialog.show()
        return ExportProgressUi(dialog, progressBar, status, percent, detail)
    }

    private fun updateExportProgress(
        ui: ExportProgressUi,
        completed: Int,
        total: Int,
        statusText: String
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val done = completed.coerceIn(0, safeTotal)
        ui.progressBar.max = safeTotal
        ui.progressBar.progress = done
        ui.percent.text = "${((done * 100f) / safeTotal).roundToInt().coerceIn(0, 100)}%"
        ui.status.text = statusText
    }

    private fun exportPdf(
        destination: Uri,
        options: PdfExportOptions,
        pagesToExport: List<DocumentPage>
    ) {
        val snapshot = pagesToExport.toList()
        if (snapshot.isEmpty()) return
        lifecycleScope.launch {
            val progressUi = createExportProgressUi(
                title = "正在导出 PDF",
                initialStatus = "准备处理 ${snapshot.size} 页",
                detailText = exportOptionLine(options),
                total = snapshot.size
            )
            runCatching {
                pdfExporter.export(
                    pages = snapshot,
                    destination = destination,
                    options = options,
                    onProgress = { completed, total ->
                        runOnUiThread {
                            updateExportProgress(
                                progressUi,
                                completed,
                                total,
                                if (completed >= total) {
                                    "页面已完成，正在写入文件"
                                } else {
                                    "已完成 $completed / $total 页"
                                }
                            )
                        }
                    }
                )
            }.onSuccess { result ->
                progressUi.dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "已导出 ${result.pageCount} 页。",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                progressUi.dialog.dismiss()
                showError(error)
            }
        }
    }

    private fun exportSingleImage(
        destination: Uri,
        pageIndex: Int,
        options: PdfExportOptions,
        format: ImageOutputFormat
    ) {
        val page = pages.getOrNull(pageIndex) ?: return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    writeRenderedImage(page, destination, options, format)
                }
            }.onSuccess {
                Toast.makeText(this@MainActivity, "当前图片已保存。", Toast.LENGTH_LONG).show()
            }.onFailure(::showError)
        }
    }

    private fun exportAllImages(
        directory: Uri,
        options: PdfExportOptions,
        format: ImageOutputFormat,
        itemsToExport: List<ExportItem> = pages.mapIndexed { index, page -> ExportItem(index, page) }
    ) {
        val snapshot = itemsToExport.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            contentResolver.takePersistableUriPermission(
                directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val progressUi = createExportProgressUi(
                title = "正在导出图片",
                initialStatus = "准备处理 ${snapshot.size} 张",
                detailText = if (format == ImageOutputFormat.JPG) {
                    "JPG · 长边 ${options.maxLongEdge}px · JPEG ${options.jpegQuality}"
                } else {
                    "PNG · 长边 ${options.maxLongEdge}px · 无损"
                },
                total = snapshot.size
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    snapshot.forEachIndexed { index, item ->
                        val imageUri = createImageDocument(
                            directory,
                            defaultImageName(item.originalIndex, format),
                            format
                        )
                        writeRenderedImage(item.page, imageUri, options, format)
                        runOnUiThread {
                            val done = index + 1
                            updateExportProgress(
                                progressUi,
                                done,
                                snapshot.size,
                                "已导出 $done / ${snapshot.size} 张"
                            )
                        }
                    }
                }
            }.onSuccess {
                progressUi.dialog.dismiss()
                Toast.makeText(this@MainActivity, "已导出 ${snapshot.size} 张图片。", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                progressUi.dialog.dismiss()
                showError(error)
            }
        }
    }

    private fun writeRenderedImage(
        page: DocumentPage,
        destination: Uri,
        options: PdfExportOptions,
        format: ImageOutputFormat
    ) {
        val bitmap = renderPageBitmap(page, options.maxLongEdge, true)
        try {
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                check(bitmap.compress(format.compressFormat, options.jpegQuality, output)) {
                    "无法写出 ${format.extension.uppercase(Locale.ROOT)} 图片。"
                }
            } ?: error("无法打开图片保存位置：$destination")
        } finally {
            bitmap.recycle()
        }
    }

    private fun measureRenderedImageBytes(
        page: DocumentPage,
        options: PdfExportOptions,
        format: ImageOutputFormat
    ): Long {
        val bitmap = renderPageBitmap(page, options.maxLongEdge, true)
        try {
            val buffer = ByteArrayOutputStream()
            check(bitmap.compress(format.compressFormat, options.jpegQuality, buffer)) {
                "无法估算 ${format.extension.uppercase(Locale.ROOT)} 图片。"
            }
            return buffer.size().toLong()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createImageDocument(
        directory: Uri,
        fileName: String,
        format: ImageOutputFormat
    ): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(directory)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(directory, treeDocumentId)
        return DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            format.mimeType,
            fileName
        ) ?: error("无法在所选文件夹创建图片。")
    }

    private fun startMlKitScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener(::showError)
    }

    private fun attachLongPressDragSelection(
        recycler: RecyclerView,
        adapter: GalleryAdapter,
        status: TextView
    ) {
        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val edgeSize = 72.dp
        val autoScroller = EdgeAutoScroller(edgeSize)
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var dragActive = false
            private var dragSelect = true
            private var longPressRunnable: Runnable? = null
            private val visited = mutableSetOf<Long>()

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        dragActive = false
                        visited.clear()
                        longPressRunnable = Runnable {
                            dragActive = true
                            dragSelect = startDragMode(rv, downX, downY)
                            rv.parent.requestDisallowInterceptTouchEvent(true)
                            visitDragCell(rv, downX, downY, adapter, status)
                            Toast.makeText(this@MainActivity, "拖动批量选择", Toast.LENGTH_SHORT).show()
                        }.also { handler.postDelayed(it, longPressTimeout) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragActive && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) {
                            cancelLongPress(handler)
                            return false
                        }
                        if (dragActive) return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cancelLongPress(handler)
                        dragActive = false
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (!dragActive) return
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        visitDragCell(rv, e.x, e.y, adapter, status)
                        autoScroller.update(rv, e.y)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cancelLongPress(handler)
                        autoScroller.stop()
                        dragActive = false
                        visited.clear()
                        rv.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }

            private fun cancelLongPress(handler: Handler) {
                longPressRunnable?.let(handler::removeCallbacks)
                longPressRunnable = null
            }

            private fun startDragMode(rv: RecyclerView, x: Float, y: Float): Boolean {
                val image = imageUnder(rv, x, y) ?: return true
                return selectedImages[image.id] == null
            }

            private fun visitDragCell(
                rv: RecyclerView,
                x: Float,
                y: Float,
                adapter: GalleryAdapter,
                status: TextView
            ) {
                val image = imageUnder(rv, x, y) ?: return
                if (!visited.add(image.id)) return
                if (dragSelect) selectedImages[image.id] = image else selectedImages.remove(image.id)
                updateGallerySelection(adapter, status)
            }

            private fun imageUnder(rv: RecyclerView, x: Float, y: Float): GalleryImage? {
                val child = rv.findChildViewUnder(x, y) ?: return null
                val position = rv.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) return null
                return adapter.images.getOrNull(position)
            }
        })
    }

    private fun attachPageReorder(
        recycler: RecyclerView,
        adapter: PageStripAdapter,
        onMoved: (Int, Long?) -> Unit
    ) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            private var lastTarget = RecyclerView.NO_POSITION
            private var capturedUndo = false
            private var selectedStableIdDuringDrag: Long? = null

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from !in pages.indices || to !in pages.indices) return false
                if (!capturedUndo) {
                    rememberUndo("调整顺序")
                    capturedUndo = true
                    selectedStableIdDuringDrag = pages.getOrNull(selectedPageIndex)?.stableId
                }
                adapter.move(from, to)
                adapter.updateVisibleLabels(recyclerView)
                lastTarget = to
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val movedIndex = lastTarget
                lastTarget = RecyclerView.NO_POSITION
                if (movedIndex != RecyclerView.NO_POSITION) {
                    invalidateExportEstimateCache()
                    onMoved(movedIndex, selectedStableIdDuringDrag)
                }
                capturedUndo = false
                selectedStableIdDuringDrag = null
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    private fun rememberStripPosition(recycler: RecyclerView) {
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val child = layoutManager.findViewByPosition(first)
        stripAnchorIndex = first.coerceAtLeast(0)
        stripAnchorOffset = (child?.left ?: recycler.paddingLeft) - recycler.paddingLeft
    }

    private fun anchorStripNearSelected() {
        stripAnchorIndex = selectedPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        stripAnchorOffset = 14.dp
    }

    private fun toggleImageSelection(image: GalleryImage) {
        if (selectedImages.containsKey(image.id)) selectedImages.remove(image.id)
        else selectedImages[image.id] = image
    }

    private fun updateGallerySelection(adapter: GalleryAdapter, status: TextView) {
        adapter.selectedOrder = selectedImages.keys.mapIndexed { index, id -> id to index + 1 }.toMap()
        status.text = "已选择 ${selectedImages.size} 张"
    }

    private fun ensureImagePermission(next: () -> Unit) {
        val permission = imagePermission()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            next()
            return
        }
        pendingPermissionAction = next
        permissionLauncher.launch(permission)
    }

    private fun imagePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun pageTitle(): String = "${selectedPageIndex + 1}/${pages.size}"

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun uriFromPickerPath(path: String): Uri {
        return if (path.startsWith("content://") || path.startsWith("file://")) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
    }

    private fun baseColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
    }

    private fun homeWordmark(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp, 0, 4.dp)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 15.dp, border)
                elevation = 1.dp.toFloat()
                setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            }, LinearLayout.LayoutParams(44.dp, 44.dp).apply {
                rightMargin = 12.dp
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Chao"
                    textSize = 31f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(textPrimary)
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Scan"
                    textSize = 31f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(googleBlue)
                    includeFontPadding = false
                })
            })
        }
    }

    private fun primaryButton(title: String, subtitle: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = 74.dp
            setPadding(18.dp, 14.dp, 18.dp, 14.dp)
            background = rounded(card, 16.dp, Color.rgb(226, 231, 238))
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTextColor(textPrimary)
                includeFontPadding = false
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, 6.dp, 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun modernProgress(sizeDp: Int = 54): ProgressBar {
        return ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.rgb(56, 128, 168))
            background = rounded(Color.argb(222, 255, 255, 255), 18.dp, Color.rgb(226, 232, 240))
            elevation = 5.dp.toFloat()
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            layoutParams = FrameLayout.LayoutParams(sizeDp.dp, sizeDp.dp, Gravity.CENTER)
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            setAllCaps(false)
            minWidth = 0
            minimumWidth = 0
            minHeight = 38.dp
            minimumHeight = 38.dp
            includeFontPadding = false
            stateListAnimator = null
            elevation = 0f
            setPadding(15.dp, 0, 15.dp, 0)
            setTextColor(googleBlue)
            background = rounded(Color.rgb(241, 246, 252), 15.dp, Color.TRANSPARENT)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(241, 246, 252))
            setOnClickListener { onClick() }
        }
    }

    private fun compactButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setAllCaps(false)
            minWidth = 0
            minimumWidth = 0
            minHeight = 34.dp
            minimumHeight = 34.dp
            includeFontPadding = false
            stateListAnimator = null
            elevation = 0f
            setPadding(10.dp, 0, 10.dp, 0)
            setTextColor(googleBlue)
            background = rounded(Color.rgb(241, 246, 252), 14.dp, Color.TRANSPARENT)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(241, 246, 252))
            setOnClickListener { onClick() }
        }
    }

    private fun iconButton(iconRes: Int, label: String, onClick: () -> Unit): ImageButton {
        val destructive = label == "删除"
        val tint = if (destructive) Color.rgb(196, 59, 47) else googleBlue
        val bg = if (destructive) Color.rgb(255, 241, 239) else Color.rgb(239, 244, 250)
        return ImageButton(this).apply {
            contentDescription = label
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            background = rounded(bg, 14.dp, Color.TRANSPARENT)
            backgroundTintList = ColorStateList.valueOf(bg)
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = 46.dp
            minimumHeight = 42.dp
            setPadding(11.dp, 9.dp, 11.dp, 9.dp)
            setOnClickListener { onClick() }
        }
    }

    private fun bulkActionButton(iconRes: Int, label: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = 56.dp
            setPadding(6.dp, 7.dp, 6.dp, 6.dp)
            background = rounded(Color.rgb(241, 246, 252), 14.dp, Color.TRANSPARENT)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(googleBlue)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                setTextColor(googleBlue)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 3.dp, 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun setBulkActionButtonState(button: View, iconRes: Int, label: String, active: Boolean) {
        val group = button as? LinearLayout ?: return
        val tint = if (active) Color.WHITE else googleBlue
        val bg = if (active) applyGreen else Color.rgb(241, 246, 252)
        group.background = rounded(bg, 14.dp, Color.TRANSPARENT)
        group.backgroundTintList = ColorStateList.valueOf(bg)
        (group.getChildAt(0) as? ImageView)?.apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
        }
        (group.getChildAt(1) as? TextView)?.apply {
            text = label
            setTextColor(tint)
        }
    }

    private fun cropToolButton(iconRes: Int, label: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = 58.dp
            layoutParams = LinearLayout.LayoutParams(0, 64.dp, 1f).apply {
                marginEnd = 4.dp
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(30.dp, 30.dp)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            setOnClickListener { onClick() }
        }
    }

    private inner class EdgeAutoScroller(
        private val edgeSize: Int,
        private val maxStepPerFrame: Float = 7.dp.toFloat()
    ) {
        private var recycler: RecyclerView? = null
        private var pointerY = Float.NaN
        private var velocity = 0f
        private var running = false
        private var lastFrameMs = 0L

        private val frameRunnable = object : Runnable {
            override fun run() {
                val rv = recycler ?: return
                if (!running) return
                val now = SystemClock.uptimeMillis()
                val frameScale = ((now - lastFrameMs).coerceAtLeast(1L) / 16.67f).coerceIn(0.5f, 2.5f)
                lastFrameMs = now
                val target = edgeScrollTargetVelocity(pointerY, rv.height, edgeSize, maxStepPerFrame)
                velocity += (target - velocity) * 0.18f
                if (abs(target) < 0.05f && abs(velocity) < 0.12f) {
                    stop()
                    return
                }
                val delta = (velocity * frameScale).roundToInt()
                if (delta != 0) rv.scrollBy(0, delta)
                rv.postOnAnimation(this)
            }
        }

        fun update(rv: RecyclerView, y: Float) {
            recycler = rv
            pointerY = y
            val target = edgeScrollTargetVelocity(y, rv.height, edgeSize, maxStepPerFrame)
            if (target == 0f && abs(velocity) < 0.12f) {
                stop()
                return
            }
            if (!running) {
                running = true
                lastFrameMs = SystemClock.uptimeMillis()
                rv.postOnAnimation(frameRunnable)
            }
        }

        fun stop() {
            recycler?.removeCallbacks(frameRunnable)
            running = false
            velocity = 0f
            pointerY = Float.NaN
            recycler = null
        }
    }

    private fun edgeScrollTargetVelocity(
        pointerY: Float,
        viewportHeight: Int,
        edgeSize: Int,
        maxStep: Float
    ): Float {
        if (viewportHeight <= 0 || edgeSize <= 0 || pointerY.isNaN()) return 0f
        val topDistance = pointerY
        val bottomDistance = viewportHeight - pointerY
        val direction = when {
            topDistance < edgeSize -> -1f
            bottomDistance < edgeSize -> 1f
            else -> return 0f
        }
        val distanceIntoEdge = if (direction < 0f) {
            (edgeSize - topDistance).coerceIn(0f, edgeSize.toFloat())
        } else {
            (edgeSize - bottomDistance).coerceIn(0f, edgeSize.toFloat())
        }
        val progress = (distanceIntoEdge / edgeSize.toFloat()).coerceIn(0f, 1f)
        val eased = progress * progress
        return direction * (maxStep * eased).coerceIn(0f, maxStep)
    }

    private fun scrollRow(vararg views: View): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 2.dp, 0, 2.dp)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                views.forEach { child ->
                    addView(child, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 10.dp
                    })
                }
            })
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(1.dp, strokeColor)
            }
        }
    }

    private fun safeTopPadding(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 24.dp
    }

    private fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun ptToMm(value: Int): Float = value * 25.4f / 72f

    private fun mmToPt(value: Float): Int = (value / 25.4f * 72f)
        .roundToInt()
        .coerceAtLeast(72)

    private fun showError(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(error.message ?: error.toString())
            .setPositiveButton("知道了", null)
            .show()
    }
}
