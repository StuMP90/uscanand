package uk.co.dsv1.uscanand.pdf

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale

/** A rectangle in PDF points, origin at the bottom-left of the page. */
data class Box(val x: Float, val y: Float, val width: Float, val height: Float)

/** A line of recognised text, positioned in the pixel coordinates of the page image (origin top-left). */
data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Minimal streaming PDF writer for scanned documents.
 *
 * Each page is a JPEG embedded unchanged (DCTDecode) plus an optional invisible text layer
 * (text render mode 3), which makes the PDF searchable and selectable without altering how
 * it looks. Pages are written as they are added, so only one page image is held in memory.
 */
class PdfWriter(output: OutputStream, private val title: String? = null) : Closeable {
    private val out = CountingOutputStream(BufferedOutputStream(output))
    private val offsets = HashMap<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = FIRST_FREE_ID
    private var closed = false

    init {
        out.write("%PDF-1.4\n%".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        writeObject(FONT_ID) {
            ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        }
    }

    val pageCount: Int get() = pageIds.size

    fun addPage(jpeg: ByteArray, pageWidth: Float, pageHeight: Float, imageBox: Box, text: List<TextLine> = emptyList()) {
        check(!closed) { "PdfWriter is closed" }
        val info = JpegInfo.parse(jpeg)
        val imageId = nextId++
        val contentId = nextId++
        val pageId = nextId++

        writeObject(imageId) {
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                    "/ColorSpace ${info.colorSpace} /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
            )
            out.write(jpeg)
            ascii("\nendstream")
        }

        val content = pageContent(info, imageBox, text)
        writeObject(contentId) {
            ascii("<< /Length ${content.size} >>\nstream\n")
            out.write(content)
            ascii("\nendstream")
        }

        writeObject(pageId) {
            ascii(
                "<< /Type /Page /Parent $PAGES_ID 0 R /MediaBox [0 0 ${num(pageWidth)} ${num(pageHeight)}] " +
                    "/Resources << /XObject << /Im0 $imageId 0 R >> /Font << /F1 $FONT_ID 0 R >> >> " +
                    "/Contents $contentId 0 R >>",
            )
        }
        pageIds += pageId
    }

    override fun close() {
        if (closed) return
        closed = true
        writeObject(PAGES_ID) {
            ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>")
        }
        writeObject(CATALOG_ID) { ascii("<< /Type /Catalog /Pages $PAGES_ID 0 R >>") }
        val infoId = nextId++
        writeObject(infoId) {
            val titleEntry = title?.let { " /Title ${textString(it)}" }.orEmpty()
            ascii("<< /Producer ${textString("uScanAnd")}$titleEntry >>")
        }

        val xrefOffset = out.count
        val size = nextId
        val xref = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) {
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.getValue(id)))
        }
        xref.append("trailer\n<< /Size $size /Root $CATALOG_ID 0 R /Info $infoId 0 R >>\n")
        xref.append("startxref\n$xrefOffset\n%%EOF\n")
        ascii(xref.toString())
        out.close()
    }

    private fun pageContent(info: JpegInfo, box: Box, text: List<TextLine>): ByteArray {
        val content = ByteArrayOutputStream()
        fun ascii(s: String) = content.write(s.toByteArray(Charsets.US_ASCII))

        ascii("q ${num(box.width)} 0 0 ${num(box.height)} ${num(box.x)} ${num(box.y)} cm /Im0 Do Q\n")

        val scaleX = box.width / info.width
        val scaleY = box.height / info.height
        var inText = false
        for (line in text) {
            val encoded = WinAnsi.encode(line.text.trim())
            val width = (line.right - line.left) * scaleX
            val height = (line.bottom - line.top) * scaleY
            if (encoded.isEmpty() || width <= 0f || height <= 0f) continue
            if (!inText) {
                ascii("BT 3 Tr\n")
                inText = true
            }
            val fontSize = height / (Helvetica.ASCENT + Helvetica.DESCENT)
            val x = box.x + line.left * scaleX
            val baseline = box.y + box.height - line.bottom * scaleY + Helvetica.DESCENT * fontSize
            val naturalWidth = Helvetica.width(encoded) * fontSize / 1000f
            val horizontalScale = if (naturalWidth > 0f) (width / naturalWidth * 100f).coerceIn(10f, 1000f) else 100f
            ascii("/F1 ${num(fontSize)} Tf ${num(horizontalScale)} Tz 1 0 0 1 ${num(x)} ${num(baseline)} Tm ")
            content.write(literalString(encoded))
            ascii(" Tj\n")
        }
        if (inText) ascii("ET\n")
        return content.toByteArray()
    }

    private inline fun writeObject(id: Int, body: () -> Unit) {
        offsets[id] = out.count
        ascii("$id 0 obj\n")
        body()
        ascii("\nendobj\n")
    }

    private fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

    private class CountingOutputStream(stream: OutputStream) : FilterOutputStream(stream) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        private const val CATALOG_ID = 1
        private const val PAGES_ID = 2
        private const val FONT_ID = 3
        private const val FIRST_FREE_ID = 4

        /** Formats a number for PDF syntax: fixed point, no exponent, locale independent. */
        internal fun num(value: Float): String {
            val s = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
            return if (s == "-0" || s.isEmpty()) "0" else s
        }

        /** A PDF literal string of already-encoded bytes. */
        internal fun literalString(bytes: ByteArray): ByteArray {
            val sb = StringBuilder("(")
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                when {
                    c == '('.code || c == ')'.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                    c < 32 || c > 126 -> sb.append('\\').append(Integer.toOctalString(c).padStart(3, '0'))
                    else -> sb.append(c.toChar())
                }
            }
            return sb.append(')').toString().toByteArray(Charsets.US_ASCII)
        }

        /** A PDF text string (for metadata) as UTF-16BE hex with a byte order mark. */
        internal fun textString(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_16BE)
            return bytes.joinToString(separator = "", prefix = "<FEFF", postfix = ">") { "%02X".format(it.toInt() and 0xFF) }
        }
    }
}

/** Dimensions and colour components read from a JPEG's start-of-frame header. */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    val colorSpace: String
        get() = when (components) {
            1 -> "/DeviceGray"
            4 -> "/DeviceCMYK"
            else -> "/DeviceRGB"
        }

    companion object {
        fun parse(data: ByteArray): JpegInfo {
            fun u(i: Int) = data[i].toInt() and 0xFF
            require(data.size > 4 && u(0) == 0xFF && u(1) == 0xD8) { "Not a JPEG image" }
            var i = 2
            while (i + 3 < data.size) {
                if (u(i) != 0xFF) {
                    i++
                    continue
                }
                val marker = u(i + 1)
                when {
                    marker == 0xFF -> i++ // fill byte
                    marker == 0x01 || marker in 0xD0..0xD8 -> i += 2 // markers without a length
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                        require(i + 9 < data.size) { "Truncated JPEG header" }
                        val height = (u(i + 5) shl 8) or u(i + 6)
                        val width = (u(i + 7) shl 8) or u(i + 8)
                        return JpegInfo(width, height, u(i + 9))
                    }
                    else -> i += 2 + ((u(i + 2) shl 8) or u(i + 3))
                }
            }
            throw IllegalArgumentException("JPEG has no frame header")
        }
    }
}

/** Encodes text in the PDF WinAnsiEncoding used by the standard Helvetica font. */
internal object WinAnsi {
    private val specials = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91,
        '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '˜' to 0x98,
        '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F,
    )

    fun encode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        for (ch in text) {
            val code = when {
                ch.code in 32..126 || ch.code in 160..255 -> ch.code
                ch == '\t' || ch == '\n' || ch == '\r' -> ' '.code
                else -> specials[ch] ?: '?'.code
            }
            out.write(code)
        }
        return out.toByteArray()
    }
}

/** Glyph metrics for the standard Helvetica font, in 1/1000 em. */
internal object Helvetica {
    const val ASCENT = 0.718f
    const val DESCENT = 0.207f

    private val asciiWidths = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // space to /
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0 to ?
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ to O
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P to _
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` to o
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, // p to ~
    )

    fun width(encoded: ByteArray): Int = encoded.sumOf { b ->
        val c = b.toInt() and 0xFF
        if (c in 32..126) asciiWidths[c - 32] else 556
    }
}
