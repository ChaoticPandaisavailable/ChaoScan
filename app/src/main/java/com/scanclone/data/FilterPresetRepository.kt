package com.scanclone.data

import android.content.Context
import com.scanclone.model.ScanParameters
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class FilterPresetRepository(context: Context) {
    private val file = File(context.filesDir, "filter_presets.json")

    data class FilterPreset(
        val id: String,
        val name: String,
        val parameters: ScanParameters
    )

    fun listPresets(): List<FilterPreset> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            FilterPreset(
                id = json.getString("id"),
                name = json.optString("name").ifBlank { "未命名预设" },
                parameters = scanFromJson(json.getJSONObject("parameters"))
            )
        }
    }

    fun savePreset(name: String, parameters: ScanParameters): FilterPreset {
        val presets = listPresets().toMutableList()
        val preset = FilterPreset(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "未命名预设" },
            parameters = parameters.copy()
        )
        presets += preset
        writePresets(presets.takeLast(24))
        return preset
    }

    fun deletePreset(id: String) {
        writePresets(listPresets().filterNot { it.id == id })
    }

    fun replacePresets(presets: List<FilterPreset>) {
        writePresets(presets.map { it.copy(parameters = it.parameters.copy()) })
    }

    private fun writePresets(presets: List<FilterPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("parameters", scanToJson(preset.parameters))
            })
        }
        file.writeText(array.toString(), Charsets.UTF_8)
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

    private fun scanFromJson(json: JSONObject): ScanParameters {
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
