package uk.co.dsv1.uscanand.image

/**
 * Long printed lines on a page, such as table borders and underlines, which should be straight once the
 * page is flat. Each line is a list of normalised (x, y) points along it.
 */
data class RuledLines(
    val horizontal: List<List<Pair<Float, Float>>>,
    val vertical: List<List<Pair<Float, Float>>>,
) {
    val isEmpty: Boolean get() = horizontal.isEmpty() && vertical.isEmpty()
}

/**
 * Finds long thin printed lines by tracing them from short dark seeds. A candidate is kept only if it is
 * long, thin, mostly continuous, and has clear paper just above or below it along most of its length,
 * which rules out lines of text.
 */
object RuleDetector {
    private const val MIN_LENGTH = 0.25f
    private const val MAX_THICKNESS = 4
    private const val STEP = 2
    private const val MAX_GAP = 24
    private const val SEED_SPACING = 25
    private const val SEED_LENGTH = 11
    private const val MIN_DENSITY = 0.8f
    private const val MIN_CLEAR = 0.6f
    private const val SAMPLE_SPACING = 0.0125f

    /** [luma] is row-major brightness (0..255) of a [width] x [height] image, ideally about 1400 pixels on its long side. */
    fun detect(luma: IntArray, width: Int, height: Int): RuledLines {
        require(luma.size >= width * height) { "Image data too small" }
        val threshold = darkThreshold(luma, width * height)
        val dark = BooleanArray(width * height) { luma[it] < threshold }
        val horizontal = trace(width, height) { a, b -> dark[b * width + a] }
            .map { line -> line.map { (a, b) -> a / width to b / height } }
        val vertical = trace(height, width) { a, b -> dark[a * width + b] }
            .map { line -> line.map { (a, b) -> b / width to a / height } }
        return RuledLines(horizontal, vertical)
    }

    /** Ink is darker than this; halfway between the typical paper and a dark print level, within sensible limits. */
    private fun darkThreshold(luma: IntArray, count: Int): Int {
        val histogram = IntArray(256)
        for (i in 0 until count) histogram[luma[i]]++
        var seen = 0
        var paper = 255
        for (v in 255 downTo 0) {
            seen += histogram[v]
            if (seen >= count / 2) {
                paper = v
                break
            }
        }
        return (paper * 0.65f).toInt().coerceIn(90, 180)
    }

    /**
     * Traces lines running along the first coordinate `a` (0 until [along]), at position `b` across
     * (0 until [across]). Returns each line as smoothed (a, b) points in pixels.
     */
    private fun trace(along: Int, across: Int, isDark: (a: Int, b: Int) -> Boolean): List<List<Pair<Float, Float>>> {
        val cell = 4
        val cellsAlong = along / cell + 1
        val used = BooleanArray(cellsAlong * (across / cell + 1))
        fun usedAt(a: Int, b: Int) = used[(b / cell) * cellsAlong + a / cell]

        fun isSeed(a: Int, b: Int): Boolean {
            if (a + SEED_LENGTH >= along) return false
            for (k in 0 until SEED_LENGTH step 2) if (!isDark(a + k, b)) return false
            var thick = 0
            for (bb in maxOf(0, b - MAX_THICKNESS)..minOf(across - 1, b + MAX_THICKNESS)) if (isDark(a + SEED_LENGTH / 2, bb)) thick++
            return thick <= MAX_THICKNESS
        }

        val lines = mutableListOf<List<Pair<Float, Float>>>()
        for (b0 in 2 until across - 2) {
            var a0 = (along * 0.05f).toInt()
            while (a0 < along * 0.95f) {
                if (!usedAt(a0, b0) && isSeed(a0, b0)) {
                    val points = follow(a0, b0, along, across, isDark)
                    if (isRule(points, along, across, isDark)) {
                        for ((a, b) in points) {
                            for (db in -3..3) {
                                val bb = b + db
                                if (bb in 0 until across) used[(bb / cell) * cellsAlong + a / cell] = true
                            }
                        }
                        lines += resample(points, along)
                    }
                }
                a0 += SEED_SPACING
            }
        }
        return lines
    }

    /** Follows a thin dark line both ways from a seed, allowing a gentle drift and short breaks. */
    private fun follow(a0: Int, b0: Int, along: Int, across: Int, isDark: (Int, Int) -> Boolean): Map<Int, Int> {
        val points = sortedMapOf<Int, Int>()
        for (direction in intArrayOf(1, -1)) {
            var a = a0
            var b = b0
            var gap = 0
            while (a in 0 until along) {
                val found = intArrayOf(b, b - 1, b + 1).firstOrNull { it in 0 until across && isDark(a, it) }
                if (found != null) {
                    var lo = found
                    var hi = found
                    while (lo - 1 >= 0 && isDark(a, lo - 1) && found - lo < MAX_THICKNESS) lo--
                    while (hi + 1 < across && isDark(a, hi + 1) && hi - found < MAX_THICKNESS) hi++
                    if (hi - lo + 1 <= MAX_THICKNESS) {
                        b = (lo + hi) / 2
                        points[a] = b
                        gap = 0
                    } else {
                        gap += STEP // crossing something thick, such as text or a junction
                    }
                } else {
                    gap += STEP
                }
                if (gap > MAX_GAP) break
                a += direction * STEP
            }
        }
        return points
    }

    private fun isRule(points: Map<Int, Int>, along: Int, across: Int, isDark: (Int, Int) -> Boolean): Boolean {
        if (points.size < 2) return false
        val first = points.keys.first()
        val last = points.keys.last()
        val span = last - first
        if (span < along * MIN_LENGTH) return false
        if (points.size < MIN_DENSITY * span / STEP) return false
        fun clear(a: Int, b: Int, side: Int) = (3..5).all { k -> val bb = b + side * k; bb !in 0 until across || !isDark(a, bb) }
        val above = points.count { (a, b) -> clear(a, b, -1) }.toFloat() / points.size
        val below = points.count { (a, b) -> clear(a, b, 1) }.toFloat() / points.size
        return maxOf(above, below) >= MIN_CLEAR
    }

    /** Averages the traced points into evenly spaced samples, then lightly smooths them. */
    private fun resample(points: Map<Int, Int>, along: Int): List<Pair<Float, Float>> {
        val binSize = maxOf(STEP.toFloat(), along * SAMPLE_SPACING)
        val bins = points.entries.groupBy { (it.key / binSize).toInt() }.toSortedMap()
        val raw = bins.values.map { entries -> entries.map { it.key }.average().toFloat() to entries.map { it.value }.average().toFloat() }
        return raw.indices.map { i ->
            val window = raw.subList(maxOf(0, i - 1), minOf(raw.size, i + 2))
            raw[i].first to window.map { it.second }.average().toFloat()
        }
    }
}
