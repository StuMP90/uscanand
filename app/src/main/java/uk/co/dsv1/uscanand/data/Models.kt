package uk.co.dsv1.uscanand.data

import kotlinx.serialization.Serializable

@Serializable
enum class PageFilter { ORIGINAL, ENHANCED, GREYSCALE, BLACK_WHITE }

/**
 * A scanned page. Edits are non-destructive: [imageFile] is never modified; rotation, crop and
 * filter are applied when the preview or PDF is rendered.
 */
@Serializable
data class Page(
    val id: String,
    val imageFile: String,
    /** Clockwise rotation in degrees, a multiple of 90. */
    val rotation: Int = 0,
    val filter: PageFilter = PageFilter.ORIGINAL,
    /** Crop corners in the unrotated image (see QuadMath), or null for the whole image. */
    val crop: List<Float>? = null,
    /** Bumped whenever the preview is re-rendered, so image caches reload it. */
    val version: Int = 0,
) {
    val previewFile: String get() = "${id}_preview.jpg"
}

@Serializable
data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<Page> = emptyList(),
)
