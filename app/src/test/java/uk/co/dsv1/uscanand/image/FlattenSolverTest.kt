package uk.co.dsv1.uscanand.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FlattenSolverTest {
    private val width = 1400
    private val height = 2000

    /** Builds a page of text in two columns whose lines follow [shape] (normalised centre y for a line at x). */
    private fun page(from: Float = 0.1f, to: Float = 0.9f, shape: (x: Float, lineY: Float) -> Float): List<List<WordBox>> {
        val lines = mutableListOf<List<WordBox>>()
        var lineY = from
        while (lineY <= to) {
            for ((start, end) in listOf(0.08f to 0.46f, 0.54f to 0.92f)) {
                val words = mutableListOf<WordBox>()
                var x = start
                while (x + 0.05f <= end) {
                    val cy = shape(x + 0.025f, lineY)
                    words += WordBox(x * width, (cy - 0.006f) * height, (x + 0.05f) * width, (cy + 0.006f) * height)
                    x += 0.065f
                }
                lines += words
            }
            lineY += 0.04f
        }
        return lines
    }

    /** Spread of a line's heights after flattening (or before, with no mesh). */
    private fun spread(mesh: FlattenMesh?, shape: (Float, Float) -> Float, lineY: Float, from: Float = 0.1f, to: Float = 0.9f): Float {
        val ys = (0..20).map { i ->
            val x = from + (to - from) * i / 20f
            val y = shape(x, lineY)
            y + (mesh?.displacementAt(x, y) ?: 0f)
        }
        val mean = ys.average()
        return sqrt(ys.sumOf { (it - mean) * (it - mean) } / ys.size).toFloat()
    }

    private fun levelAfter(mesh: FlattenMesh, shape: (Float, Float) -> Float, lineY: Float, x: Float): Float {
        val y = shape(x, lineY)
        return y + mesh.displacementAt(x, y)
    }

    @Test
    fun curledPageComesOutLevel() {
        // A bowed page whose tilt also changes down the page, like paper curling at the bottom.
        val curl = { x: Float, lineY: Float -> lineY + 0.012f * sin(PI.toFloat() * x) + 0.01f * (x - 0.5f) * lineY }
        val mesh = FlattenSolver.solve(page(shape = curl), null, width, height)!!
        for (lineY in listOf(0.14f, 0.5f, 0.86f)) {
            val before = spread(null, curl, lineY)
            val after = spread(mesh, curl, lineY)
            assertTrue("line at $lineY: before $before, after $after", after < before / 5f)
        }
        // Columns side by side stay aligned with each other.
        for (lineY in listOf(0.22f, 0.62f)) {
            val gap = abs(levelAfter(mesh, curl, lineY, 0.2f) - levelAfter(mesh, curl, lineY, 0.8f))
            assertTrue("columns misaligned by $gap at $lineY", gap < 0.0015f)
        }
    }

    @Test
    fun rotatedPageIsShearedConsistently() {
        val tilt = { x: Float, lineY: Float -> lineY + 0.02f * (x - 0.5f) }
        val mesh = FlattenSolver.solve(page(shape = tilt), null, width, height)!!
        assertTrue(spread(mesh, tilt, 0.5f) < spread(null, tilt, 0.5f) / 5f)
        val gap = abs(levelAfter(mesh, tilt, 0.5f, 0.2f) - levelAfter(mesh, tilt, 0.5f, 0.8f))
        assertTrue("columns misaligned by $gap", gap < 0.0015f)
    }

    @Test
    fun flatPageIsLeftAlone() {
        val mesh = FlattenSolver.solve(page { _, lineY -> lineY }, null, width, height)!!
        assertTrue(mesh.dy.all { abs(it) < 1e-4f } && mesh.dx.all { it == 0f })
    }

    @Test
    fun jitteryWordPositionsDoNotWarpAFlatPage() {
        // OCR boxes wobble by a pixel or two; that noise shouldn't turn into visible warping.
        val random = java.util.Random(7)
        val lines = page { _, lineY -> lineY }.map { words ->
            words.map { w ->
                val shift = (random.nextGaussian() * 0.0015 * height).toFloat()
                WordBox(w.left, w.top + shift, w.right, w.bottom + shift)
            }
        }
        val mesh = FlattenSolver.solve(lines, null, width, height)!!
        // Check where the text is; blank margins can drift slightly without affecting anything visible.
        val worst = (1..9).flatMap { i -> (1..9).map { j -> abs(mesh.displacementAt(i / 10f, j / 10f)) } }.max()
        // 0.002 of the page height is about 4 pixels on a 2000 pixel scan.
        assertTrue("flat page moved by up to $worst", worst < 0.002f)
    }

    @Test
    fun tooLittleTextGivesNoResult() {
        val lines = page { _, lineY -> lineY }.take(3)
        assertNull(FlattenSolver.solve(lines, null, width, height))
    }

    /** Paper edges sampled along each side of a sheet that bows in from the image border. */
    private fun bowedEdges(): PaperEdges {
        val along = (0 until 48).map { 0.04f + 0.92f * it / 47 }
        return PaperEdges(
            top = along.map { it to 0.01f + 0.03f * sin(PI.toFloat() * it) },
            bottom = along.map { it to 0.99f - 0.03f * sin(PI.toFloat() * it) },
            left = along.map { 0.01f + 0.02f * sin(PI.toFloat() * it) to it },
            right = along.map { 0.97f to it },
        )
    }

    @Test
    fun paperEdgesArePinnedToTheBorder() {
        val edges = bowedEdges()
        val mesh = FlattenSolver.solve(page { _, lineY -> lineY }, edges, width, height)!!
        fun worst(points: List<Pair<Float, Float>>, error: (Float, Float) -> Float) = points.maxOf { (x, y) -> abs(error(x, y)) }
        val top = worst(edges.top!!) { x, y -> y + mesh.displacementAt(x, y) }
        val bottom = worst(edges.bottom!!) { x, y -> y + mesh.displacementAt(x, y) - 1f }
        val left = worst(edges.left!!) { x, y -> x + mesh.horizontalDisplacementAt(x, y) }
        val right = worst(edges.right!!) { x, y -> x + mesh.horizontalDisplacementAt(x, y) - 1f }
        // Before flattening the edges sit up to 4% in from the border; afterwards they should be close to it.
        for ((side, error) in listOf("top" to top, "bottom" to bottom, "left" to left, "right" to right)) {
            assertTrue("$side edge ends up $error from the border", error < 0.004f)
        }
    }

    @Test
    fun pullingInAnEdgeDoesNotBendNearbyText() {
        // Level text starting just below a strongly bowed top edge: the text should stay level.
        val flat = { _: Float, lineY: Float -> lineY }
        val mesh = FlattenSolver.solve(page(from = 0.07f, shape = flat), bowedEdges(), width, height)!!
        for (lineY in listOf(0.07f, 0.11f, 0.15f, 0.87f)) {
            val after = spread(mesh, flat, lineY)
            // 0.0006 of the page height is about 1 pixel on a 2000 pixel scan.
            assertTrue("line at $lineY bent by $after", after < 0.0006f)
        }
    }

    @Test
    fun aTiltedEdgeDoesNotTiltThePage() {
        // Like a financial statement: labels on the left, figures on the right, no line spanning both,
        // so nothing in the text ties the two sides. A top edge that slopes must not tilt the page.
        val lines = (0 until 20).map { i ->
            val y = 0.15f + i * 0.035f
            (0 until 3).map { k -> WordBox((0.08f + k * 0.07f) * width, (y - 0.006f) * height, (0.13f + k * 0.07f) * width, (y + 0.006f) * height) }
        }
        val along = (0 until 48).map { 0.04f + 0.92f * it / 47 }
        val edges = PaperEdges(top = along.map { it to 0.005f + 0.025f * it }, bottom = null, left = null, right = null)
        val mesh = FlattenSolver.solve(lines, edges, width, height)!!
        for (y in listOf(0.3f, 0.5f, 0.7f)) {
            val lean = abs(mesh.displacementAt(0.85f, y) - mesh.displacementAt(0.2f, y))
            assertTrue("page tilted by $lean at $y", lean < 0.002f)
        }
        // The edge itself still reaches the border.
        val worst = edges.top!!.maxOf { (x, y) -> abs(y + mesh.displacementAt(x, y)) }
        assertTrue("top edge ends up $worst from the border", worst < 0.004f)
    }

    @Test
    fun tableRulesAloneStraightenAPage() {
        // A table page with little text: bowed row rules and leaning column rules.
        val along = (0..40).map { it / 40f }
        val rows = listOf(0.2f, 0.4f, 0.6f, 0.8f).map { y0 -> along.map { x -> x to y0 + 0.015f * sin(PI.toFloat() * x) } }
        val columns = listOf(0.2f, 0.5f, 0.8f).map { x0 -> along.map { y -> x0 + 0.02f * y to y } }
        val mesh = FlattenSolver.solve(emptyList(), null, width, height, RuledLines(rows, columns))!!
        for (row in rows) {
            val after = row.map { (x, y) -> y + mesh.displacementAt(x, y) }
            assertTrue("row still bows by ${after.max() - after.min()}", after.max() - after.min() < 0.002f)
        }
        for (column in columns) {
            val after = column.map { (x, y) -> x + mesh.horizontalDisplacementAt(x, y) }
            assertTrue("column still leans by ${after.max() - after.min()}", after.max() - after.min() < 0.002f)
        }
    }

    @Test
    fun aTableNarrowerAtTheBottomIsSquaredUp() {
        // Keystone: the left border leans right and the right border leans left, as when the bottom of
        // the page is further from the camera. A plain shear can't fix both; each must end up upright.
        val along = (0..40).map { 0.2f + 0.4f * it / 40f }
        val lean = 0.012f
        val columns = listOf(
            along.map { y -> 0.1f + lean * (y - 0.2f) / 0.4f to y },
            along.map { y -> 0.75f to y },
            along.map { y -> 0.86f - lean * (y - 0.2f) / 0.4f to y },
        )
        val rows = listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f).map { y -> (0..40).map { 0.1f + 0.76f * it / 40f to y } }
        val mesh = FlattenSolver.solve(emptyList(), null, width, height, RuledLines(rows, columns))!!
        for ((i, column) in columns.withIndex()) {
            val before = column.maxOf { it.first } - column.minOf { it.first }
            val after = column.map { (x, y) -> x + mesh.horizontalDisplacementAt(x, y) }.let { it.max() - it.min() }
            assertTrue("column $i leans ${after} after (was $before)", after < 0.002f)
        }
    }

    @Test
    fun edgesAloneAreEnoughToFlatten() {
        assertTrue(FlattenSolver.solve(emptyList(), bowedEdges(), width, height) != null)
        assertNull(FlattenSolver.solve(emptyList(), PaperEdges(null, null, null, null), width, height))
    }

    @Test
    fun shortOrSteepLinesAreIgnored() {
        assertNull(FlattenSolver.fitLine(listOf(WordBox(100f, 100f, 140f, 120f), WordBox(150f, 100f, 190f, 120f)), 1400f, 2000f))
        val steep = (0 until 6).map { i -> WordBox(100f + i * 150f, 100f + i * 60f, 200f + i * 150f, 120f + i * 60f) }
        assertNull(FlattenSolver.fitLine(steep, 1400f, 2000f))
    }

    @Test
    fun meshSurvivesSavingAndLoading() {
        val mesh = FlattenSolver.solve(page { x, lineY -> lineY + 0.005f * x }, bowedEdges(), width, height)!!
        val loaded = FlattenMesh.fromBytes(mesh.toBytes())
        assertEquals(mesh.columns, loaded.columns)
        assertEquals(mesh.rows, loaded.rows)
        assertArrayEquals(mesh.dx, loaded.dx, 0f)
        assertArrayEquals(mesh.dy, loaded.dy, 0f)
    }

    @Test
    fun legacyInlineMeshesStillLoad() {
        val nodes = 17 * 23
        val verticalOnly = FlattenMesh.fromLegacy(List(nodes) { 0.01f })!!
        assertEquals(0.01f, verticalOnly.displacementAt(0.3f, 0.6f), 1e-6f)
        assertEquals(0f, verticalOnly.horizontalDisplacementAt(0.3f, 0.6f), 0f)
        assertNull(FlattenMesh.fromLegacy(List(10) { 0f }))
    }
}
