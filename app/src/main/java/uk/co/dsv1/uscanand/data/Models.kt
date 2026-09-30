package uk.co.dsv1.uscanand.data

import kotlinx.serialization.Serializable
import uk.co.dsv1.uscanand.image.FlattenSolver

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
    /** A flattening mesh stored inline by earlier versions (see FlattenMesh.fromLegacy); superseded by [flattenMesh]. */
    val flatten: List<Float>? = null,
    /** File holding the mesh that flattens a curled page (see FlattenSolver); null when not flattened. */
    val flattenMesh: String? = null,
    /** FlattenSolver.VERSION that produced [flattenMesh], so pages can be redone when flattening improves. */
    val flattenVersion: Int = 0,
    /** Bumped whenever the preview is re-rendered, so image caches reload it. */
    val version: Int = 0,
) {
    val previewFile: String get() = "${id}_preview.jpg"

    val isFlattened: Boolean get() = flattenMesh != null || flatten != null

    /** Flattened by the current version of the flattening, rather than an earlier one. */
    val isFlattenedByCurrentVersion: Boolean get() = flattenMesh != null && flattenVersion >= FlattenSolver.VERSION
}

@Serializable
data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<Page> = emptyList(),
)
