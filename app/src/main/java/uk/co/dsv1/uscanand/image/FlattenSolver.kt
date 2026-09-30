package uk.co.dsv1.uscanand.image

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A recognised word's bounding box, in pixels of the image being flattened. */
data class WordBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * How far each node of a uniform [columns] x [rows] grid over the source image moves, as fractions of
 * the image width ([dx]) and height ([dy]). This is a forward mapping, as used by Canvas.drawBitmapMesh.
 */
class FlattenMesh(val columns: Int, val rows: Int, val dx: FloatArray, val dy: FloatArray) {
    val nodeCount: Int get() = (columns + 1) * (rows + 1)

    init {
        require(dx.size == nodeCount && dy.size == nodeCount) { "Mesh size doesn't match its grid" }
    }

    /** The vertical displacement at a normalised point. */
    fun displacementAt(x: Float, y: Float): Float = interpolate(dy, x, y)

    /** The horizontal displacement at a normalised point. */
    fun horizontalDisplacementAt(x: Float, y: Float): Float = interpolate(dx, x, y)

    private fun interpolate(values: FloatArray, x: Float, y: Float): Float {
        val cell = Bilinear.at(x, y, columns, rows)
        var sum = 0.0
        for (i in cell.indices.indices) sum += values[cell.indices[i]] * cell.weights[i]
        return sum.toFloat()
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + 8 * nodeCount).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(VERSION).putInt(columns).putInt(rows)
        dx.forEach { buffer.putFloat(it) }
        dy.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    companion object {
        private const val MAGIC = 0x48534D55 // "UMSH"
        private const val VERSION = 1
        private const val HEADER_BYTES = 16

        fun fromBytes(bytes: ByteArray): FlattenMesh {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= HEADER_BYTES && buffer.getInt() == MAGIC) { "Not a flatten mesh" }
            require(buffer.getInt() == VERSION) { "Unsupported flatten mesh version" }
            val columns = buffer.getInt()
            val rows = buffer.getInt()
            val nodes = (columns + 1) * (rows + 1)
            require(columns in 1..512 && rows in 1..512 && bytes.size == HEADER_BYTES + 8 * nodes) { "Corrupt flatten mesh" }
            return FlattenMesh(columns, rows, FloatArray(nodes) { buffer.getFloat() }, FloatArray(nodes) { buffer.getFloat() })
        }

        /** Meshes stored inline by earlier versions: 16 x 22, vertical only (or horizontal then vertical). */
        fun fromLegacy(values: List<Float>): FlattenMesh? {
            val nodes = 17 * 23
            return when (values.size) {
                nodes -> FlattenMesh(16, 22, FloatArray(nodes), values.toFloatArray())
                2 * nodes -> FlattenMesh(16, 22, values.subList(0, nodes).toFloatArray(), values.subList(nodes, 2 * nodes).toFloatArray())
                else -> null
            }
        }
    }
}

/**
 * Works out how to flatten a curled page from the positions of its words and the edges of the paper.
 *
 * Two fields are solved over a mesh and added together:
 *
 * - The line field levels every text line and every horizontal printed rule, and makes vertical rules
 *   (table columns) upright. Text lines are fitted with a smooth curve, and only differences along
 *   each line are constrained, so the solution is free to shear the whole page consistently; that keeps
 *   side-by-side columns and table cells aligned with each other.
 * - The edge field pulls any visible paper edge out to the image border, removing the strips of
 *   background around a curled sheet. It may bend only within a band next to each edge; across the
 *   rest of the page it can only shift or stretch the page evenly, and it may not bend any line.
 *   So the edges tidy the border, but only the text and rules decide how the page is tilted.
 *
 * Each field has a vertical part (from text, horizontal rules, and the top and bottom edges) and a
 * horizontal part (from vertical rules, and the left and right edges).
 */
object FlattenSolver {
    /** Bump when flattening changes enough that pages flattened earlier should be redone. */
    const val VERSION = 5

    const val COLUMNS = 32
    const val ROWS = 44
    private const val NODE_COUNT = (COLUMNS + 1) * (ROWS + 1)

    private const val MIN_LINES = 4
    private const val MIN_VERTICAL_COVERAGE = 0.25f
    private const val MIN_LINE_SPAN = 0.15f
    private const val SAMPLE_SPACING = 0.0125f
    private const val MAX_TILT = 0.14f // about 8 degrees
    private const val MAX_DISPLACEMENT = 0.12f

    private const val EDGE_WEIGHT = 1.0
    private const val SMOOTHNESS = 0.01
    private const val STIFFNESS = 0.0005
    /** Holds the page's scale in the line field, so straightening lines can't be faked by squashing the page. */
    private const val SCALE_STIFFNESS = 0.05
    /**
     * The same for the horizontal line field, which only follows printed rules. Rules are precise, so it
     * can be much lighter, which lets the field square up a table that is narrower at one end.
     */
    private const val RULE_SCALE_STIFFNESS = 0.001
    /** Smoothness within the band next to a visible paper edge, where paper curls hardest. */
    private const val EDGE_BAND_SMOOTHNESS = 0.0005
    /** How firmly the edge field is held to an even shift or stretch away from the edges. */
    private const val FIRM = 1.0
    /** The band reaches a little further in than the edge does, within these limits. */
    private const val MIN_EDGE_BAND = 0.04f
    private const val MAX_EDGE_BAND = 0.12f
    private const val ANCHOR = 1e-6

    /** Largest amount (fraction of the page) any printed rule may end up less straight than it started. */
    private const val MAX_RULE_WORSENING = 0.003f

    /**
     * Returns the mesh, or null when there isn't enough text, rule or edge to trust a result. As a
     * safeguard, a result that would leave any printed rule noticeably less straight is not used: the
     * page is tried again without its paper edges, and if that doesn't help it is left unflattened.
     */
    fun solve(
        lines: List<List<WordBox>>,
        edges: PaperEdges?,
        width: Int,
        height: Int,
        rules: RuledLines? = null,
    ): FlattenMesh? {
        val mesh = solveOnce(lines, edges, width, height, rules) ?: return null
        if (rules == null || !worsensRules(mesh, rules)) return mesh
        if (edges == null || edges.isEmpty) return null
        return solveOnce(lines, null, width, height, rules)?.takeUnless { worsensRules(it, rules) }
    }

    /** Whether [mesh] leaves any horizontal rule less level, or any vertical rule less upright, than before. */
    private fun worsensRules(mesh: FlattenMesh, rules: RuledLines): Boolean {
        fun spread(values: List<Float>) = values.max() - values.min()
        val horizontal = rules.horizontal.filter { it.size >= 2 }.any { rule ->
            spread(rule.map { (x, y) -> y + mesh.displacementAt(x, y) }) > spread(rule.map { it.second }) + MAX_RULE_WORSENING
        }
        val vertical = rules.vertical.filter { it.size >= 2 }.any { rule ->
            spread(rule.map { (x, y) -> x + mesh.horizontalDisplacementAt(x, y) }) > spread(rule.map { it.first }) + MAX_RULE_WORSENING
        }
        return horizontal || vertical
    }

    private fun solveOnce(
        lines: List<List<WordBox>>,
        edges: PaperEdges?,
        width: Int,
        height: Int,
        rules: RuledLines?,
    ): FlattenMesh? {
        if (width <= 0 || height <= 0) return null
        val textCurves = lines.mapNotNull { fitLine(it, width.toFloat(), height.toFloat()) }
        val enoughText = textCurves.size >= MIN_LINES &&
            textCurves.maxOf { it.centreY } - textCurves.minOf { it.centreY } >= MIN_VERTICAL_COVERAGE
        val hasEdges = edges != null && !edges.isEmpty
        val horizontalRules = rules?.horizontal.orEmpty().filter { it.size >= 2 }
        val verticalRules = rules?.vertical.orEmpty().filter { it.size >= 2 }
        if (!enoughText && !hasEdges && horizontalRules.size < 2 && verticalRules.size < 2) return null

        // Everything that should run straight across the page, and everything that should run straight down it.
        val across: List<List<Pair<Float, Float>>> = textCurves.map { it.samples } + horizontalRules
        val down: List<List<Pair<Float, Float>>> = verticalRules

        // Consecutive points along each line, split so no segment spans more than half a mesh cell.
        fun forEachSegment(lines: List<List<Pair<Float, Float>>>, action: (px: Float, py: Float, qx: Float, qy: Float) -> Unit) {
            for (line in lines) {
                for (k in 0 until line.size - 1) {
                    val (px, py) = line[k]
                    val (qx, qy) = line[k + 1]
                    val pieces = maxOf(1, ceil(maxOf(abs(qx - px) * COLUMNS, abs(qy - py) * ROWS) * 2f).toInt())
                    for (j in 0 until pieces) {
                        val t0 = j.toFloat() / pieces
                        val t1 = (j + 1).toFloat() / pieces
                        action(px + (qx - px) * t0, py + (qy - py) * t0, px + (qx - px) * t1, py + (qy - py) * t1)
                    }
                }
            }
        }

        // Line field, vertical part: level every text line and horizontal rule. Nothing here fixes the
        // overall position, so remove any shift.
        val levelling = MeshSystem()
        forEachSegment(across) { px, py, qx, qy -> levelling.addDifference(px, py, qx, qy, (py - qy).toDouble(), 1.0) }
        val dy = levelling.solve(Weights.lines(pullsDown = true)) ?: return null
        val meanY = dy.average()
        for (i in dy.indices) dy[i] -= meanY

        // Line field, horizontal part: make vertical rules upright.
        val dx = DoubleArray(NODE_COUNT)
        if (down.isNotEmpty()) {
            val upright = MeshSystem()
            forEachSegment(down) { px, py, qx, qy -> upright.addDifference(px, py, qx, qy, (px - qx).toDouble(), 1.0) }
            val solved = upright.solve(Weights.lines(pullsDown = false)) ?: return null
            val meanX = solved.average()
            for (i in dx.indices) dx[i] = solved[i] - meanX
        }

        // Vertical edge field: pull the top and bottom edges (as the text field left them) to the border.
        if (edges?.top != null || edges?.bottom != null) {
            val field = MeshSystem()
            edges.top?.forEach { (x, y) -> field.addValue(x, y, (0f - y - interpolate(dy, x, y)).toDouble(), EDGE_WEIGHT) }
            edges.bottom?.forEach { (x, y) -> field.addValue(x, y, (1f - y - interpolate(dy, x, y)).toDouble(), EDGE_WEIGHT) }
            forEachSegment(across) { px, py, qx, qy -> field.addDifference(px, py, qx, qy, 0.0, 1.0) }
            val weights = Weights.edges(
                pullsDown = true,
                startBand = band(edges.top?.maxOf { it.second }),
                endBand = band(edges.bottom?.maxOf { 1f - it.second }),
            )
            val correction = field.solve(weights) ?: return null
            for (i in dy.indices) dy[i] += correction[i]
        }

        // Horizontal edge field: pull the left and right edges (as the line field left them) to the border.
        if (edges?.left != null || edges?.right != null) {
            val field = MeshSystem()
            edges.left?.forEach { (x, y) -> field.addValue(x, y, (0f - x - interpolate(dx, x, y)).toDouble(), EDGE_WEIGHT) }
            edges.right?.forEach { (x, y) -> field.addValue(x, y, (1f - x - interpolate(dx, x, y)).toDouble(), EDGE_WEIGHT) }
            forEachSegment(down) { px, py, qx, qy -> field.addDifference(px, py, qx, qy, 0.0, 1.0) }
            val weights = Weights.edges(
                pullsDown = false,
                startBand = band(edges.left?.maxOf { it.first }),
                endBand = band(edges.right?.maxOf { 1f - it.first }),
            )
            val correction = field.solve(weights) ?: return null
            for (i in dx.indices) dx[i] += correction[i]
        }

        val mesh = FlattenMesh(COLUMNS, ROWS, FloatArray(NODE_COUNT) { dx[it].toFloat() }, FloatArray(NODE_COUNT) { dy[it].toFloat() })
        if ((mesh.dx.asSequence() + mesh.dy.asSequence()).any { !it.isFinite() || abs(it) > MAX_DISPLACEMENT }) return null
        return mesh
    }

    private fun interpolate(values: DoubleArray, x: Float, y: Float): Float {
        val cell = Bilinear.at(x, y, COLUMNS, ROWS)
        var sum = 0.0
        for (i in cell.indices.indices) sum += values[cell.indices[i]] * cell.weights[i]
        return sum.toFloat()
    }

    /** How far in from a side the edge field may bend, given how far in the paper edge reaches there. */
    private fun band(edgeDepth: Float?): Float =
        if (edgeDepth == null) 0f else (edgeDepth * 1.5f + 0.02f).coerceIn(MIN_EDGE_BAND, MAX_EDGE_BAND)

    /**
     * Regularisation weights for a field, per mesh node: second differences (curvature) and first
     * differences (slope), across the page (along a row) and down it (along a column).
     */
    private class Weights(
        val acrossCurve: (r: Int, c: Int) -> Double,
        val downCurve: (r: Int, c: Int) -> Double,
        val acrossSlope: (r: Int, c: Int) -> Double,
        val downSlope: (r: Int, c: Int) -> Double,
    ) {
        companion object {
            /**
             * A line field moving rows ([pullsDown]) or columns: smooth, free to shear, but holding the
             * page's scale in the direction it moves, so straightening can't be faked by squashing.
             */
            fun lines(pullsDown: Boolean) = if (pullsDown) {
                Weights({ _, _ -> SMOOTHNESS }, { _, _ -> SMOOTHNESS }, { _, _ -> STIFFNESS }, { _, _ -> SCALE_STIFFNESS })
            } else {
                Weights({ _, _ -> SMOOTHNESS }, { _, _ -> SMOOTHNESS }, { _, _ -> RULE_SCALE_STIFFNESS }, { _, _ -> STIFFNESS })
            }

            /**
             * An edge field pulling down the page ([pullsDown], moving rows) or across it (moving
             * columns). Within [startBand] and [endBand] of the sides it pulls toward, it bends freely;
             * elsewhere it may only shift or stretch evenly, never tilt or curve.
             */
            fun edges(pullsDown: Boolean, startBand: Float, endBand: Float): Weights {
                fun inBand(i: Int, count: Int) = i < count * startBand || i > count * (1 - endBand)
                return if (pullsDown) {
                    Weights(
                        acrossCurve = { _, _ -> SMOOTHNESS },
                        downCurve = { r, _ -> if (inBand(r, ROWS)) EDGE_BAND_SMOOTHNESS else FIRM },
                        acrossSlope = { r, _ -> if (inBand(r, ROWS)) STIFFNESS else FIRM },
                        downSlope = { _, _ -> STIFFNESS },
                    )
                } else {
                    Weights(
                        acrossCurve = { _, c -> if (inBand(c, COLUMNS)) EDGE_BAND_SMOOTHNESS else FIRM },
                        downCurve = { _, _ -> SMOOTHNESS },
                        acrossSlope = { _, _ -> STIFFNESS },
                        downSlope = { _, c -> if (inBand(c, COLUMNS)) STIFFNESS else FIRM },
                    )
                }
            }
        }
    }

    /**
     * Least-squares system for one displacement value per mesh node. Every term only links nearby
     * nodes, so the normal matrix is banded and is solved with a banded Cholesky decomposition.
     */
    private class MeshSystem {
        private val n = NODE_COUNT
        // Vertical second differences span two rows; a line segment can reach a diagonally adjacent cell.
        private val bandwidth = 2 * (COLUMNS + 1) + 2
        private val band = DoubleArray(n * (bandwidth + 1)) // lower band: [i, i - j]
        private val rhs = DoubleArray(n)

        fun addRow(indices: IntArray, coefficients: DoubleArray, target: Double, weight: Double) {
            for (a in indices.indices) {
                val ia = indices[a]
                rhs[ia] += weight * coefficients[a] * target
                for (b in indices.indices) {
                    val ib = indices[b]
                    if (ib > ia) continue
                    check(ia - ib <= bandwidth) { "Term links nodes too far apart" }
                    band[ia * (bandwidth + 1) + (ia - ib)] += weight * coefficients[a] * coefficients[b]
                }
            }
        }

        /** d(q) - d(p) = target. */
        fun addDifference(px: Float, py: Float, qx: Float, qy: Float, target: Double, weight: Double) {
            val p = Bilinear.at(px, py, COLUMNS, ROWS)
            val q = Bilinear.at(qx, qy, COLUMNS, ROWS)
            addRow(q.indices + p.indices, q.weights + p.weights.map { -it }.toDoubleArray(), target, weight)
        }

        /** d(p) = target. */
        fun addValue(x: Float, y: Float, target: Double, weight: Double) {
            val p = Bilinear.at(x, y, COLUMNS, ROWS)
            addRow(p.indices, p.weights, target, weight)
        }

        /** Adds the regularisation terms and a tiny anchor, then solves. */
        fun solve(weights: Weights): DoubleArray? {
            fun node(r: Int, c: Int) = r * (COLUMNS + 1) + c
            for (r in 0..ROWS) {
                for (c in 0..COLUMNS) {
                    if (c in 1 until COLUMNS) {
                        addRow(intArrayOf(node(r, c - 1), node(r, c), node(r, c + 1)), doubleArrayOf(1.0, -2.0, 1.0), 0.0, weights.acrossCurve(r, c))
                    }
                    if (r in 1 until ROWS) {
                        addRow(intArrayOf(node(r - 1, c), node(r, c), node(r + 1, c)), doubleArrayOf(1.0, -2.0, 1.0), 0.0, weights.downCurve(r, c))
                    }
                    if (c < COLUMNS) addRow(intArrayOf(node(r, c), node(r, c + 1)), doubleArrayOf(-1.0, 1.0), 0.0, weights.acrossSlope(r, c))
                    if (r < ROWS) addRow(intArrayOf(node(r, c), node(r + 1, c)), doubleArrayOf(-1.0, 1.0), 0.0, weights.downSlope(r, c))
                    addRow(intArrayOf(node(r, c)), doubleArrayOf(1.0), 0.0, ANCHOR)
                }
            }
            return solveBanded()
        }

        private fun solveBanded(): DoubleArray? {
            val w = bandwidth + 1
            fun l(i: Int, j: Int) = band[i * w + (i - j)]
            for (i in 0 until n) {
                for (j in max(0, i - bandwidth)..i) {
                    var sum = band[i * w + (i - j)]
                    for (k in max(0, i - bandwidth) until j) sum -= l(i, k) * l(j, k)
                    if (i == j) {
                        if (sum <= 0.0) return null
                        band[i * w] = sqrt(sum)
                    } else {
                        band[i * w + (i - j)] = sum / band[j * w]
                    }
                }
            }
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var sum = rhs[i]
                for (k in max(0, i - bandwidth) until i) sum -= l(i, k) * y[k]
                y[i] = sum / band[i * w]
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var sum = y[i]
                for (k in i + 1..min(n - 1, i + bandwidth)) sum -= l(k, i) * x[k]
                x[i] = sum / band[i * w]
            }
            return x
        }
    }

    internal class Curve(val samples: List<Pair<Float, Float>>, val centreY: Float)

    /** Fits a line of words with a straight line or gentle curve, in normalised page coordinates. */
    internal fun fitLine(words: List<WordBox>, width: Float, height: Float): Curve? {
        if (words.size < 2) return null
        val left = words.minOf { it.left } / width
        val right = words.maxOf { it.right } / width
        if (right - left < MIN_LINE_SPAN) return null

        val xs = words.map { (it.left + it.right) / 2f / width }
        val ys = words.map { (it.top + it.bottom) / 2f / height }
        val ws = words.map { (it.right - it.left) / width }
        // A curve needs enough words across enough width to measure; otherwise noise looks like curl.
        val degree = if (words.size >= 6 && right - left >= 0.35f) 2 else 1
        val centreX = (left + right) / 2f

        var keep = xs.indices.toList()
        var coefficients = polyFit(keep.map { xs[it] - centreX }, keep.map { ys[it] }, keep.map { ws[it] }, degree) ?: return null
        // One pass of outlier rejection (superscripts, stray marks merged into the line).
        val residuals = xs.indices.map { abs(ys[it] - evaluate(coefficients, xs[it] - centreX)) }
        val typical = residuals.sorted()[residuals.size / 2]
        if (typical > 0f) {
            val filtered = xs.indices.filter { residuals[it] <= 3f * typical + 1e-6f }
            if (filtered.size >= 2 && filtered.size < keep.size) {
                keep = filtered
                coefficients = polyFit(keep.map { xs[it] - centreX }, keep.map { ys[it] }, keep.map { ws[it] }, degree) ?: return null
            }
        }

        // Reject lines tilted too far to be ordinary text (in pixel terms, not normalised units).
        val tilt = abs(coefficients[1]) * height / width
        if (tilt > MAX_TILT) return null

        val count = maxOf(2, ceil((right - left) / SAMPLE_SPACING).toInt() + 1)
        val samples = (0 until count).map { i ->
            val x = left + (right - left) * i / (count - 1)
            x to evaluate(coefficients, x - centreX)
        }
        return Curve(samples, evaluate(coefficients, 0f))
    }

    private fun evaluate(c: FloatArray, x: Float): Float {
        var result = 0f
        for (i in c.indices.reversed()) result = result * x + c[i]
        return result
    }

    /** Weighted least-squares polynomial fit; coefficients lowest order first. */
    private fun polyFit(xs: List<Float>, ys: List<Float>, ws: List<Float>, degree: Int): FloatArray? {
        val m = degree + 1
        val a = DoubleArray(m * m)
        val b = DoubleArray(m)
        for (i in xs.indices) {
            val powers = DoubleArray(m).also { p -> p[0] = 1.0; for (k in 1 until m) p[k] = p[k - 1] * xs[i] }
            for (r in 0 until m) {
                b[r] += ws[i] * powers[r] * ys[i]
                for (c in 0 until m) a[r * m + c] += ws[i] * powers[r] * powers[c]
            }
        }
        for (k in 0 until m) a[k * m + k] += 1e-12 // keep near-degenerate fits solvable
        return solveSmall(a, b, m)?.let { s -> FloatArray(m) { s[it].toFloat() } }
    }

    /** Solves a small dense symmetric positive-definite system by Cholesky decomposition. */
    private fun solveSmall(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val l = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = a[i * n + j]
                for (k in 0 until j) sum -= l[i * n + k] * l[j * n + k]
                if (i == j) {
                    if (sum <= 0.0) return null
                    l[i * n + i] = sqrt(sum)
                } else {
                    l[i * n + j] = sum / l[j * n + j]
                }
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i * n + k] * y[k]
            y[i] = sum / l[i * n + i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = y[i]
            for (k in i + 1 until n) sum -= l[k * n + i] * x[k]
            x[i] = sum / l[i * n + i]
        }
        return x
    }
}

/** Bilinear interpolation weights of the four mesh nodes around a normalised point. */
internal class Bilinear(val indices: IntArray, val weights: DoubleArray) {
    companion object {
        fun at(x: Float, y: Float, columns: Int, rows: Int): Bilinear {
            val gx = x.coerceIn(0f, 1f) * columns
            val gy = y.coerceIn(0f, 1f) * rows
            val c = floor(gx).toInt().coerceIn(0, columns - 1)
            val r = floor(gy).toInt().coerceIn(0, rows - 1)
            val fx = (gx - c).toDouble()
            val fy = (gy - r).toDouble()
            val stride = columns + 1
            val base = r * stride + c
            return Bilinear(
                intArrayOf(base, base + 1, base + stride, base + stride + 1),
                doubleArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy),
            )
        }
    }
}
