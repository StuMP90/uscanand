package uk.co.dsv1.uscanand.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RuleDetectorTest {
    private val width = 1000
    private val height = 700

    /** A table rule that bows by 12 pixels, a column line that leans, and several lines of text. */
    private val ruleY = { x: Int -> 300 + 12 * sin(PI * x / width) }
    private val columnX = { y: Int -> 600 + 0.03 * y }

    private fun page(): IntArray {
        val luma = IntArray(width * height) { 230 }
        fun ink(x: Int, y: Int) { if (x in 0 until width && y in 0 until height) luma[y * width + x] = 40 }
        for (x in 60 until 940) for (t in 0..1) ink(x, ruleY(x).toInt() + t)
        for (y in 80 until 640) for (t in 0..1) ink(columnX(y).toInt() + t, y)
        // Text: rows of letter-like strokes with gaps between letters and words.
        for (row in 0 until 6) {
            val base = 100 + row * 30
            for (x in 80 until 560) {
                if ((x / 7) % 2 == 0 && (x / 60) % 5 != 4) for (y in base - 10..base) ink(x, y)
            }
        }
        return luma
    }

    @Test
    fun tracesACurvedRuleAndALeaningColumnButNotText() {
        val rules = RuleDetector.detect(page(), width, height)
        assertEquals("horizontal rules found", 1, rules.horizontal.size)
        assertEquals("vertical rules found", 1, rules.vertical.size)
        for ((x, y) in rules.horizontal.single()) {
            val expected = (ruleY((x * width).toInt()) + 0.5) / height
            assertTrue("rule at x=$x: $y vs $expected", abs(y - expected) < 2.5 / height)
        }
        for ((x, y) in rules.vertical.single()) {
            val expected = (columnX((y * height).toInt()) + 0.5) / width
            assertTrue("column at y=$y: $x vs $expected", abs(x - expected) < 2.5 / width)
        }
        assertTrue("rule spans most of the page", rules.horizontal.single().let { it.last().first - it.first().first } > 0.8f)
    }

    @Test
    fun blankPageHasNoRules() {
        assertTrue(RuleDetector.detect(IntArray(width * height) { 230 }, width, height).isEmpty)
    }
}
