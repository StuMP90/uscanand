package uk.co.dsv1.uscanand.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadMathTest {

    private fun assertQuad(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-5f) }
    }

    @Test
    fun rotatingClockwiseMovesTopLeftToTopRight() {
        assertEquals(1f to 0f, QuadMath.rotatePoint(0f, 0f, 90))
        assertEquals(1f to 1f, QuadMath.rotatePoint(0f, 0f, 180))
        assertEquals(0f to 1f, QuadMath.rotatePoint(0f, 0f, 270))
        assertEquals(0.75f to 0.1f, QuadMath.rotatePoint(0.1f, 0.25f, 90))
    }

    @Test
    fun rotationRoundTrips() {
        val quad = listOf(0.1f, 0.2f, 0.9f, 0.15f, 0.85f, 0.95f, 0.05f, 0.8f)
        for (degrees in listOf(0, 90, 180, 270)) {
            assertQuad(quad, QuadMath.rotateQuad(QuadMath.rotateQuad(quad, degrees), -degrees))
        }
    }

    @Test
    fun orderCornersRestoresClockwiseOrder() {
        val ordered = listOf(0.1f, 0.2f, 0.9f, 0.15f, 0.85f, 0.95f, 0.05f, 0.8f)
        val shuffled = listOf(0.85f, 0.95f, 0.1f, 0.2f, 0.05f, 0.8f, 0.9f, 0.15f)
        assertQuad(ordered, QuadMath.orderCorners(shuffled))
    }

    @Test
    fun fullQuadSurvivesRotationAndOrdering() {
        for (degrees in listOf(90, 180, 270)) {
            val back = QuadMath.orderCorners(QuadMath.rotateQuad(QuadMath.rotateQuad(QuadMath.FULL, degrees), -degrees))
            assertTrue(QuadMath.isFull(back))
            assertTrue(QuadMath.isFull(QuadMath.orderCorners(QuadMath.rotateQuad(QuadMath.FULL, degrees))))
        }
        assertFalse(QuadMath.isFull(listOf(0.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
    }

    @Test
    fun outputSizeUsesLongestEdges() {
        val pixels = floatArrayOf(10f, 10f, 110f, 20f, 120f, 220f, 0f, 200f)
        val (w, h) = QuadMath.outputSize(pixels)
        assertEquals(122, w) // bottom edge: (0,200) to (120,220)
        assertEquals(200, h) // right edge: (110,20) to (120,220)
    }
}
