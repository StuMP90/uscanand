package uk.co.dsv1.uscanand.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the paper's edges are in a scanned page, as normalised (x, y) points traced along each side.
 * A side is null when no background is visible there (the paper runs right to the image edge).
 */
data class PaperEdges(
    val top: List<Pair<Float, Float>>?,
    val bottom: List<Pair<Float, Float>>?,
    val left: List<Pair<Float, Float>>?,
    val right: List<Pair<Float, Float>>?,
) {
    val isEmpty: Boolean get() = top == null && bottom == null && left == null && right == null
}

/**
 * Finds the edges of the paper where a darker background shows around a scanned page, which
 * happens when a curled sheet bows in from the straight edges the scanner cropped to.
 */
object PaperEdgeDetector {
    private const val SAMPLES = 48
    private const val MAX_DEPTH = 0.1f
    private const val MIN_CONTRAST = 50
    private const val MIN_VALID = 0.6f
    private const val MAX_DEVIATION = 0.015f

    /** [luma] is row-major brightness (0..255) of a [width] x [height] image. */
    fun detect(luma: IntArray, width: Int, height: Int): PaperEdges {
        require(luma.size >= width * height) { "Image data too small" }
        fun at(x: Int, y: Int) = luma[y * width + x]
        val top = traceSide(width, height) { a, d -> at(a, d) }
        val bottom = traceSide(width, height) { a, d -> at(a, height - 1 - d) }
        val left = traceSide(height, width) { a, d -> at(d, a) }
        val right = traceSide(height, width) { a, d -> at(width - 1 - d, a) }
        return PaperEdges(
            top = top?.map { (a, d) -> a to d },
            bottom = bottom?.map { (a, d) -> a to 1f - d },
            left = left?.map { (a, d) -> d to a },
            right = right?.map { (a, d) -> 1f - d to a },
        )
    }

    /**
     * Traces one side. [pixel] gives brightness at position `a` along the side and depth `d` in
     * from it. Returns (along, depth) pairs normalised to the side's length and the image's depth.
     */
    private fun traceSide(along: Int, across: Int, pixel: (a: Int, d: Int) -> Int): List<Pair<Float, Float>>? {
        val maxDepth = (across * MAX_DEPTH).toInt()
        if (along < SAMPLES || maxDepth < 4) return null

        // Separate background from paper within the border band.
        val histogram = IntArray(256)
        val step = max(1, along / 200)
        for (a in 0 until along step step) for (d in 0 until maxDepth) histogram[pixel(a, d)]++
        val (threshold, darkMean, brightMean) = otsu(histogram)
        if (brightMean - darkMean < MIN_CONTRAST) return null

        fun smoothed(a: Int, d: Int): Int {
            var sum = 0
            var count = 0
            for (da in -2..2) {
                val aa = a + da
                if (aa in 0 until along) {
                    sum += pixel(aa, d)
                    count++
                }
            }
            return sum / count
        }

        // The edge is the first point, coming in from the border, where the paper starts.
        val depths = FloatArray(SAMPLES) { Float.NaN }
        for (i in 0 until SAMPLES) {
            val a = (along * (0.04f + 0.92f * i / (SAMPLES - 1))).toInt().coerceIn(0, along - 1)
            for (d in 0 until maxDepth - 3) {
                if (smoothed(a, d) > threshold && smoothed(a, d + 1) > threshold && smoothed(a, d + 2) > threshold) {
                    depths[i] = d.toFloat() / across
                    break
                }
            }
        }
        val valid = depths.count { !it.isNaN() }
        if (valid < SAMPLES * MIN_VALID) return null
        // No background showing at all on this side: nothing to correct.
        if (depths.filter { !it.isNaN() }.all { it * across < 1f }) return null

        // Drop samples that disagree with their neighbours (a stray mark, a shadow).
        val points = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until SAMPLES) {
            if (depths[i].isNaN()) continue
            val neighbours = (max(0, i - 2)..min(SAMPLES - 1, i + 2)).map { depths[it] }.filter { !it.isNaN() }.sorted()
            val median = neighbours[neighbours.size / 2]
            if (abs(depths[i] - median) <= MAX_DEVIATION) points += (0.04f + 0.92f * i / (SAMPLES - 1)) to depths[i]
        }
        return points.takeIf { it.size >= SAMPLES * MIN_VALID }
    }

    /** Otsu's threshold for a histogram, with the mean of each class. */
    private fun otsu(histogram: IntArray): Triple<Int, Double, Double> {
        val total = histogram.sum().toDouble()
        val sumAll = histogram.indices.sumOf { it.toDouble() * histogram[it] }
        var weightDark = 0.0
        var sumDark = 0.0
        var best = Triple(128, 0.0, 255.0)
        var bestVariance = -1.0
        for (t in 0 until 255) {
            weightDark += histogram[t]
            sumDark += t.toDouble() * histogram[t]
            val weightBright = total - weightDark
            if (weightDark == 0.0 || weightBright == 0.0) continue
            val meanDark = sumDark / weightDark
            val meanBright = (sumAll - sumDark) / weightBright
            val variance = weightDark * weightBright * (meanDark - meanBright) * (meanDark - meanBright)
            if (variance > bestVariance) {
                bestVariance = variance
                best = Triple(t, meanDark, meanBright)
            }
        }
        return best
    }
}
