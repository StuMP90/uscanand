package uk.co.dsv1.uscanand.image

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PaperEdgeDetectorTest {
    private val width = 700
    private val height = 1000

    /** A sheet whose top edge bows down and whose left edge bows in, on a dark desk, with some text. */
    private fun scene(topDepth: (Float) -> Float, leftDepth: (Float) -> Float): IntArray {
        val luma = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val onPaper = y >= topDepth(x / width.toFloat()) * height && x >= leftDepth(y / height.toFloat()) * width
                val text = onPaper && y % 40 in 20..26 && x in 80..620 && (x / 9) % 3 != 0
                luma[y * width + x] = when {
                    !onPaper -> 35 + (x * 7 + y * 13) % 20 // textured desk
                    text -> 30
                    else -> 225
                }
            }
        }
        return luma
    }

    @Test
    fun tracesBowedEdges() {
        val top = { x: Float -> 0.01f + 0.04f * sin(PI.toFloat() * x) }
        val left = { y: Float -> 0.02f + 0.03f * sin(PI.toFloat() * y) }
        val edges = PaperEdgeDetector.detect(scene(top, left), width, height)
        val topPoints = edges.top!!
        val leftPoints = edges.left!!
        assertTrue(topPoints.size > 30 && leftPoints.size > 30)
        for ((x, y) in topPoints) assertTrue("top at $x: $y vs ${top(x)}", abs(y - top(x)) < 3f / height)
        for ((x, y) in leftPoints) assertTrue("left at $y: $x vs ${left(y)}", abs(x - left(y)) < 3f / width)
        // The paper reaches the image border on the other two sides.
        assertNull(edges.bottom)
        assertNull(edges.right)
    }

    @Test
    fun paperFillingTheImageHasNoEdges() {
        val edges = PaperEdgeDetector.detect(scene({ 0f }, { 0f }), width, height)
        assertTrue(edges.isEmpty)
    }
}
