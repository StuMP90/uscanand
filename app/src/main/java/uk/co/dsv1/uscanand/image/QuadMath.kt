package uk.co.dsv1.uscanand.image

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Geometry for crop quadrilaterals. A quad is 8 floats: x,y pairs for the top-left, top-right,
 * bottom-right and bottom-left corners, normalised to 0..1 across the image.
 */
object QuadMath {
    val FULL: List<Float> = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun normaliseDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Where a normalised point ends up after the image is rotated clockwise by [degrees] (a multiple of 90). */
    fun rotatePoint(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (normaliseDegrees(degrees)) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }

    fun rotateQuad(quad: List<Float>, degrees: Int): List<Float> =
        quad.chunked(2).flatMap { (x, y) -> rotatePoint(x, y, degrees).toList() }

    /** Reorders four corners into top-left, top-right, bottom-right, bottom-left (clockwise on screen). */
    fun orderCorners(quad: List<Float>): List<Float> {
        val points = quad.chunked(2).map { (x, y) -> x to y }
        val cx = points.sumOf { it.first.toDouble() } / 4
        val cy = points.sumOf { it.second.toDouble() } / 4
        val sorted = points.sortedBy { atan2(it.second - cy, it.first - cx) }
        val start = sorted.indices.minBy { sorted[it].first + sorted[it].second }
        return (0 until 4).flatMap { sorted[(start + it) % 4].toList() }
    }

    fun isFull(quad: List<Float>): Boolean = quad.zip(FULL).all { (a, b) -> abs(a - b) < 0.002f }

    /** Output size for a perspective crop of a quad given in pixel coordinates. */
    fun outputSize(pixels: FloatArray): Pair<Int, Int> {
        fun dist(i: Int, j: Int) = hypot(pixels[2 * i] - pixels[2 * j], pixels[2 * i + 1] - pixels[2 * j + 1])
        val width = max(dist(0, 1), dist(3, 2))
        val height = max(dist(0, 3), dist(1, 2))
        return max(1, width.roundToInt()) to max(1, height.roundToInt())
    }
}
