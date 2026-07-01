package com.scanclone.model

data class ScanParameters(
    var brightness: Float = 0.08f,
    var contrast: Float = 1.35f,
    var saturation: Float = 0.05f,
    var exposure: Float = 0.12f,
    var gamma: Float = 0.95f,
    var sharpen: Float = 0.55f,
    var blackPoint: Float = 0f,
    var whitePoint: Float = 0f,
    var shadows: Float = 0f,
    var highlights: Float = 0f,
    var temperature: Float = 0f,
    var tint: Float = 0f,
    var paperClean: Float = 0.12f,
    var inkBoost: Float = 0.12f,
    var grayscale: Boolean = false,
    var invert: Boolean = false,
    var modelKey: String? = null
) {
    fun copyFrom(other: ScanParameters) {
        brightness = other.brightness
        contrast = other.contrast
        saturation = other.saturation
        exposure = other.exposure
        gamma = other.gamma
        sharpen = other.sharpen
        blackPoint = other.blackPoint
        whitePoint = other.whitePoint
        shadows = other.shadows
        highlights = other.highlights
        temperature = other.temperature
        tint = other.tint
        paperClean = other.paperClean
        inkBoost = other.inkBoost
        grayscale = other.grayscale
        invert = other.invert
        modelKey = other.modelKey
    }

    companion object {
        fun original(): ScanParameters = ScanParameters(
            brightness = 0f,
            contrast = 1f,
            saturation = 1f,
            exposure = 0f,
            gamma = 1f,
            sharpen = 0f,
            blackPoint = 0f,
            whitePoint = 0f,
            shadows = 0f,
            highlights = 0f,
            temperature = 0f,
            tint = 0f,
            paperClean = 0f,
            inkBoost = 0f,
            grayscale = false,
            invert = false,
            modelKey = null
        )

        fun natural(): ScanParameters = ScanParameters(
            brightness = 0.02f,
            exposure = 0.04f,
            contrast = 1.12f,
            gamma = 1.00f,
            saturation = 1.05f,
            sharpen = 0.25f,
            paperClean = 0.04f,
            inkBoost = 0.02f,
            grayscale = false,
            invert = false
        )

        fun inkMaxScan(): ScanParameters = ScanParameters(
            inkBoost = 1.2f
        )

        fun colorScan(): ScanParameters = ScanParameters(
            brightness = 0.08f,
            exposure = 0.12f,
            contrast = 1.35f,
            gamma = 0.95f,
            saturation = 0.72f,
            sharpen = 0.62f,
            blackPoint = 0.02f,
            whitePoint = 0.04f,
            paperClean = 0.20f,
            inkBoost = 0.18f,
            grayscale = false,
            invert = false
        )

        fun bluePenNotes(): ScanParameters = ScanParameters(
            brightness = 0.10f,
            exposure = 0.10f,
            contrast = 1.42f,
            gamma = 0.92f,
            saturation = 1.18f,
            sharpen = 0.72f,
            whitePoint = 0.03f,
            shadows = 0.04f,
            paperClean = 0.52f,
            inkBoost = 0.46f,
            grayscale = false,
            invert = false
        )

        fun shadowClean(): ScanParameters = ScanParameters(
            brightness = 0.07f,
            exposure = 0.10f,
            contrast = 1.38f,
            gamma = 0.96f,
            saturation = 0.62f,
            sharpen = 0.55f,
            blackPoint = 0.01f,
            whitePoint = 0.13f,
            highlights = 0.04f,
            paperClean = 1.02f,
            inkBoost = 0.42f,
            grayscale = false,
            invert = false
        )

        fun sharpenedNotes(): ScanParameters = ScanParameters(
            brightness = 0.05f,
            exposure = 0.06f,
            contrast = 1.44f,
            gamma = 0.94f,
            saturation = 0.72f,
            sharpen = 1.12f,
            blackPoint = 0.035f,
            whitePoint = 0.07f,
            paperClean = 0.62f,
            inkBoost = 1.02f,
            grayscale = false,
            invert = false
        )

        fun monoDocument(): ScanParameters = ScanParameters(
            brightness = 0.08f,
            exposure = 0.08f,
            contrast = 1.48f,
            gamma = 0.92f,
            saturation = 0f,
            sharpen = 0.72f,
            blackPoint = 0.04f,
            whitePoint = 0.08f,
            paperClean = 0.76f,
            inkBoost = 0.78f,
            grayscale = true,
            invert = false
        )

        fun highContrast(): ScanParameters = ScanParameters(
            brightness = 0.02f,
            exposure = 0.08f,
            contrast = 1.82f,
            gamma = 0.86f,
            saturation = 0.45f,
            sharpen = 1.00f,
            blackPoint = 0.08f,
            whitePoint = 0.12f,
            paperClean = 0.62f,
            inkBoost = 0.68f,
            grayscale = false,
            invert = false
        )

        fun invertedDocument(): ScanParameters = ScanParameters(
            brightness = 0.02f,
            exposure = 0.02f,
            contrast = 1.18f,
            gamma = 1.00f,
            saturation = 0f,
            sharpen = 0f,
            blackPoint = 0f,
            whitePoint = 0f,
            shadows = 0f,
            highlights = 0f,
            paperClean = 0f,
            inkBoost = 0.45f,
            grayscale = true,
            invert = true
        )

    }
}

data class PdfExportOptions(
    val maxLongEdge: Int = 2200,
    val jpegQuality: Int = 82,
    val pageWidthPt: Int = 595,
    val pageHeightPt: Int = 842,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val preCompressImages: Boolean = false
)

enum class PdfPageSize(
    val label: String,
    val widthPt: Int,
    val heightPt: Int
) {
    IMAGE("图片大小", 0, 0),
    A4("A4", 595, 842),
    A3("A3", 842, 1191),
    LETTER("Letter", 612, 792),
    CUSTOM("自定义", 0, 0)
}

data class PdfExportResult(
    val pageCount: Int
)
