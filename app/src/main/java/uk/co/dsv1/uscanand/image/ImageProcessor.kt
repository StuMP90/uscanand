package uk.co.dsv1.uscanand.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import uk.co.dsv1.uscanand.data.Page
import uk.co.dsv1.uscanand.data.PageFilter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ImageProcessor {

    /** Decodes an image from a content URI, upright (EXIF applied) and no larger than [maxDim]. */
    fun decodeUri(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image: $uri")

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode image: $uri")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val upright = applyOrientation(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        return scaleToFit(upright, maxDim).also { if (it !== upright) upright.recycle() }
    }

    /** Decodes one of the app's own (already upright) JPEGs, no larger than [maxDim]. */
    fun decodeFile(file: File, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot read image: $file")
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim) }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Cannot decode image: $file")
        return scaleToFit(decoded, maxDim).also { if (it !== decoded) decoded.recycle() }
    }

    fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot write $file")
        }
    }

    /** Returns a copy no larger than [maxDim] on its long side, or [src] itself if it already fits. */
    fun scaleToFit(src: Bitmap, maxDim: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
            true,
        )
    }

    /**
     * Applies a page's crop, rotation and filter. Returns a new bitmap, or [original] itself when
     * there is nothing to apply; [original] is never recycled.
     */
    fun render(original: Bitmap, page: Page, mesh: FlattenMesh?): Bitmap {
        var current = original
        fun advance(next: Bitmap) {
            if (current !== original && current !== next) current.recycle()
            current = next
        }
        page.crop?.takeUnless(QuadMath::isFull)?.let { advance(perspectiveCrop(current, it)) }
        advance(rotate(current, page.rotation))
        mesh?.let { advance(applyFlatten(current, it)) }
        advance(applyFilter(current, page.filter))
        return current
    }

    /** The image a page's flatten mesh is computed from: cropped and rotated, but not flattened or filtered. */
    fun renderForFlattening(original: Bitmap, page: Page): Bitmap =
        render(original, page.copy(filter = PageFilter.ORIGINAL), mesh = null)

    /** Warps [src] with a mesh from FlattenSolver so that curved text lines and paper edges come out straight. */
    fun applyFlatten(src: Bitmap, mesh: FlattenMesh): Bitmap {
        val width = src.width.toFloat()
        val height = src.height.toFloat()
        val vertices = FloatArray(mesh.nodeCount * 2)
        var i = 0
        for (row in 0..mesh.rows) {
            for (column in 0..mesh.columns) {
                vertices[i * 2] = column * width / mesh.columns + mesh.dx[i] * width
                vertices[i * 2 + 1] = row * height / mesh.rows + mesh.dy[i] * height
                i++
            }
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmapMesh(src, mesh.columns, mesh.rows, vertices, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** Row-major brightness (0..255) of every pixel. */
    fun luminance(src: Bitmap): IntArray {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) pixels[i] = luma(pixels[i])
        return pixels
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val normalised = QuadMath.normaliseDegrees(degrees)
        if (normalised == 0) return src
        val matrix = Matrix().apply { postRotate(normalised.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** Warps the quadrilateral [quad] (normalised corners, see QuadMath) into an upright rectangle. */
    fun perspectiveCrop(src: Bitmap, quad: List<Float>): Bitmap {
        val points = FloatArray(8) { i -> quad[i] * if (i % 2 == 0) src.width else src.height }
        var (width, height) = QuadMath.outputSize(points)
        val limit = max(src.width, src.height).toFloat()
        if (max(width, height) > limit) {
            val scale = limit / max(width, height)
            width = max(1, (width * scale).roundToInt())
            height = max(1, (height * scale).roundToInt())
        }
        val target = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(points, 0, target, 0, 4) }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    fun applyFilter(src: Bitmap, filter: PageFilter): Bitmap = when (filter) {
        PageFilter.ORIGINAL -> src
        PageFilter.GREYSCALE -> withColorMatrix(src, ColorMatrix().apply { setSaturation(0f) })
        PageFilter.ENHANCED -> normaliseAgainstPaper(src, blackAndWhite = false)
        PageFilter.BLACK_WHITE -> normaliseAgainstPaper(src, blackAndWhite = true)
    }

    private fun withColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    /** Maps ratio-to-paper (0..255) to output: paper becomes white, ink gets darker. */
    private val enhanceCurve = IntArray(256) { r ->
        val v = ((r / 255f - 0.1f) / 0.85f).coerceIn(0f, 1f)
        (255f * v.pow(1.3f)).roundToInt()
    }

    private const val BLACK_WHITE_THRESHOLD = 190

    /**
     * Divides each pixel by the local paper colour, which removes shadows and uneven lighting,
     * then either boosts contrast (colour) or thresholds (black and white).
     */
    private fun normaliseAgainstPaper(src: Bitmap, blackAndWhite: Boolean): Bitmap {
        val width = src.width
        val height = src.height
        val paper = estimatePaper(src)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val band = 64
        val pixels = IntArray(width * band)
        val background = IntArray(width * band)
        var y = 0
        while (y < height) {
            val rows = min(band, height - y)
            src.getPixels(pixels, 0, width, 0, y, width, rows)
            paper.getPixels(background, 0, width, 0, y, width, rows)
            for (i in 0 until width * rows) {
                val p = pixels[i]
                val b = background[i]
                pixels[i] = if (blackAndWhite) {
                    val grey = luma(p)
                    val paperGrey = max(luma(b), 1)
                    if (grey * 255 / paperGrey < BLACK_WHITE_THRESHOLD) Color.BLACK else Color.WHITE
                } else {
                    val r = enhanceCurve[ratio((p shr 16) and 0xFF, (b shr 16) and 0xFF)]
                    val g = enhanceCurve[ratio((p shr 8) and 0xFF, (b shr 8) and 0xFF)]
                    val bl = enhanceCurve[ratio(p and 0xFF, b and 0xFF)]
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            out.setPixels(pixels, 0, width, 0, y, width, rows)
            y += rows
        }
        paper.recycle()
        return out
    }

    private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    private fun ratio(value: Int, paper: Int): Int = min(255, value * 255 / max(paper, 1))

    /**
     * Estimates the paper colour at every pixel: average-downscale to drop noise and thin strokes,
     * max-pool so each cell takes its brightest (paper) colour, then scale back up smoothly.
     */
    private fun estimatePaper(src: Bitmap): Bitmap {
        var small = src
        fun replace(next: Bitmap) {
            if (small !== src) small.recycle()
            small = next
        }
        repeat(2) {
            if (small.width > 1 && small.height > 1) {
                replace(Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true))
            }
        }
        while (max(small.width, small.height) > 32) replace(maxPool(small))
        val paper = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== src && small !== paper) small.recycle()
        return paper
    }

    private fun maxPool(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val nw = max(1, (w + 1) / 2)
        val nh = max(1, (h + 1) / 2)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    val sy = min(2 * y + dy, h - 1)
                    for (dx in 0..1) {
                        val p = pixels[sy * w + min(2 * x + dx, w - 1)]
                        r = max(r, (p shr 16) and 0xFF)
                        g = max(g, (p shr 8) and 0xFF)
                        b = max(b, p and 0xFF)
                    }
                }
                out[y * nw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, nw, nh, Bitmap.Config.ARGB_8888)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
