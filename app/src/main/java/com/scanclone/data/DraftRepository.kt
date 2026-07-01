package com.scanclone.data

import android.content.Context
import android.net.Uri
import com.scanclone.model.DocumentPage
import com.scanclone.model.NormalizedCrop
import com.scanclone.model.NormalizedPoint
import com.scanclone.model.ScanParameters
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class DraftRepository(context: Context) {
    private val draftsDir = File(context.filesDir, "drafts").apply { mkdirs() }

    data class DraftSummary(
        val id: String,
        val name: String,
        val updatedAt: Long,
        val pageCount: Int
    )

    data class Draft(
        val summary: DraftSummary,
        val pages: List<DocumentPage>
    )

    fun listDrafts(): List<DraftSummary> {
        return draftsDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    DraftSummary(
                        id = json.getString("id"),
                        name = json.optString("name").ifBlank { "未命名草稿" },
                        updatedAt = json.optLong("updatedAt", file.lastModified()),
                        pageCount = json.optJSONArray("pages")?.length() ?: 0
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun saveDraft(
        name: String,
        pages: List<DocumentPage>,
        draftId: String? = null
    ): DraftSummary {
        require(pages.isNotEmpty()) { "空文档不需要保存草稿。" }
        val id = draftId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val summary = DraftSummary(
            id = id,
            name = name.ifBlank { "未命名草稿" },
            updatedAt = now,
            pageCount = pages.size
        )
        val json = JSONObject().apply {
            put("id", summary.id)
            put("name", summary.name)
            put("updatedAt", summary.updatedAt)
            put("pages", JSONArray().apply {
                pages.forEach { put(pageToJson(it)) }
            })
        }
        File(draftsDir, "$id.json").writeText(json.toString(), Charsets.UTF_8)
        return summary
    }

    fun loadDraft(id: String): Draft {
        val file = File(draftsDir, "$id.json")
        require(file.exists()) { "草稿不存在。" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val pagesJson = json.getJSONArray("pages")
        val pages = List(pagesJson.length()) { index ->
            pageFromJson(pagesJson.getJSONObject(index))
        }
        val summary = DraftSummary(
            id = json.getString("id"),
            name = json.optString("name").ifBlank { "未命名草稿" },
            updatedAt = json.optLong("updatedAt", file.lastModified()),
            pageCount = pages.size
        )
        return Draft(summary, pages)
    }

    fun deleteDraft(id: String) {
        File(draftsDir, "$id.json").delete()
    }

    private fun pageToJson(page: DocumentPage): JSONObject {
        return JSONObject().apply {
            put("sourceUri", page.sourceUri.toString())
            put("title", page.title)
            put("rotationDegrees", page.rotationDegrees)
            put("crop", page.crop?.let(::cropToJson))
            put("scanParameters", scanToJson(page.scanParameters))
        }
    }

    private fun pageFromJson(json: JSONObject): DocumentPage {
        return DocumentPage(
            sourceUri = Uri.parse(json.getString("sourceUri")),
            title = json.optString("title").ifBlank { "草稿页" },
            rotationDegrees = json.optInt("rotationDegrees", 0),
            crop = json.optJSONObject("crop")?.let(::cropFromJson),
            scanParameters = scanFromJson(json.optJSONObject("scanParameters"))
        )
    }

    private fun cropToJson(crop: NormalizedCrop): JSONObject {
        return JSONObject().apply {
            put("topLeft", pointToJson(crop.topLeft))
            put("topRight", pointToJson(crop.topRight))
            put("bottomRight", pointToJson(crop.bottomRight))
            put("bottomLeft", pointToJson(crop.bottomLeft))
        }
    }

    private fun cropFromJson(json: JSONObject): NormalizedCrop {
        return NormalizedCrop(
            topLeft = pointFromJson(json.getJSONObject("topLeft")),
            topRight = pointFromJson(json.getJSONObject("topRight")),
            bottomRight = pointFromJson(json.getJSONObject("bottomRight")),
            bottomLeft = pointFromJson(json.getJSONObject("bottomLeft"))
        )
    }

    private fun pointToJson(point: NormalizedPoint): JSONObject {
        return JSONObject().apply {
            put("x", point.x.toDouble())
            put("y", point.y.toDouble())
        }
    }

    private fun pointFromJson(json: JSONObject): NormalizedPoint {
        return NormalizedPoint(
            x = json.optDouble("x").toFloat(),
            y = json.optDouble("y").toFloat()
        )
    }

    private fun scanToJson(scan: ScanParameters): JSONObject {
        return JSONObject().apply {
            put("brightness", scan.brightness.toDouble())
            put("contrast", scan.contrast.toDouble())
            put("saturation", scan.saturation.toDouble())
            put("exposure", scan.exposure.toDouble())
            put("gamma", scan.gamma.toDouble())
            put("sharpen", scan.sharpen.toDouble())
            put("blackPoint", scan.blackPoint.toDouble())
            put("whitePoint", scan.whitePoint.toDouble())
            put("shadows", scan.shadows.toDouble())
            put("highlights", scan.highlights.toDouble())
            put("temperature", scan.temperature.toDouble())
            put("tint", scan.tint.toDouble())
            put("paperClean", scan.paperClean.toDouble())
            put("inkBoost", scan.inkBoost.toDouble())
            put("grayscale", scan.grayscale)
            put("invert", scan.invert)
            put("modelKey", scan.modelKey ?: JSONObject.NULL)
        }
    }

    private fun scanFromJson(json: JSONObject?): ScanParameters {
        if (json == null) return ScanParameters()
        return ScanParameters(
            brightness = json.optDouble("brightness", 0.08).toFloat(),
            contrast = json.optDouble("contrast", 1.35).toFloat(),
            saturation = json.optDouble("saturation", 0.05).toFloat(),
            exposure = json.optDouble("exposure", 0.12).toFloat(),
            gamma = json.optDouble("gamma", 0.95).toFloat(),
            sharpen = json.optDouble("sharpen", 0.55).toFloat(),
            blackPoint = json.optDouble("blackPoint", 0.0).toFloat(),
            whitePoint = json.optDouble("whitePoint", 0.0).toFloat(),
            shadows = json.optDouble("shadows", 0.0).toFloat(),
            highlights = json.optDouble("highlights", 0.0).toFloat(),
            temperature = json.optDouble("temperature", 0.0).toFloat(),
            tint = json.optDouble("tint", 0.0).toFloat(),
            paperClean = json.optDouble("paperClean", 0.12).toFloat(),
            inkBoost = json.optDouble("inkBoost", 0.12).toFloat(),
            grayscale = json.optBoolean("grayscale", false),
            invert = json.optBoolean("invert", false),
            modelKey = json.optString("modelKey")
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
