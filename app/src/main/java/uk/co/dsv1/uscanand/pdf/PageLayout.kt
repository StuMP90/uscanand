package uk.co.dsv1.uscanand.pdf

import kotlin.math.max
import kotlin.math.min

enum class PageSize(val shortSide: Float, val longSide: Float) {
    A4(595.28f, 841.89f),
    LETTER(612f, 792f),

    /** Page matches the image's aspect ratio, with its long side as long as A4's. */
    FIT(0f, 841.89f),
}

data class PageLayout(val width: Float, val height: Float, val imageBox: Box) {
    companion object {
        /** Places an image of the given pixel size on a page, fitted and centred, matching its orientation. */
        fun of(size: PageSize, imageWidth: Int, imageHeight: Int): PageLayout {
            require(imageWidth > 0 && imageHeight > 0) { "Image has no size" }
            if (size == PageSize.FIT) {
                val scale = size.longSide / max(imageWidth, imageHeight)
                val w = imageWidth * scale
                val h = imageHeight * scale
                return PageLayout(w, h, Box(0f, 0f, w, h))
            }
            val landscape = imageWidth > imageHeight
            val pageW = if (landscape) size.longSide else size.shortSide
            val pageH = if (landscape) size.shortSide else size.longSide
            val scale = min(pageW / imageWidth, pageH / imageHeight)
            val w = imageWidth * scale
            val h = imageHeight * scale
            return PageLayout(pageW, pageH, Box((pageW - w) / 2f, (pageH - h) / 2f, w, h))
        }
    }
}

private val unsafeFileChars = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

/** A safe PDF file name for a document name. */
fun pdfFileName(documentName: String): String {
    val base = documentName.replace(unsafeFileChars, "_").trim().trim('.').take(100)
    return (base.ifEmpty { "scan" }) + ".pdf"
}
