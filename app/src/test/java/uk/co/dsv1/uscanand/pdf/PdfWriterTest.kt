package uk.co.dsv1.uscanand.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PdfWriterTest {

    /** Test fixtures in src/test/resources: 400x560 and 560x400 RGB, 50x70 greyscale. */
    private fun jpeg(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing fixture $name" }.use { it.readBytes() }

    private fun writePdf(vararg pages: Pair<ByteArray, List<TextLine>>): ByteArray {
        val out = ByteArrayOutputStream()
        PdfWriter(out, title = "Test “scan”").use { writer ->
            for ((jpeg, text) in pages) {
                val info = JpegInfo.parse(jpeg)
                val layout = PageLayout.of(PageSize.A4, info.width, info.height)
                writer.addPage(jpeg, layout.width, layout.height, layout.imageBox, text)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun jpegInfoReadsFrameHeader() {
        assertEquals(JpegInfo(400, 560, 3), JpegInfo.parse(jpeg("portrait.jpg")))
        assertEquals(JpegInfo(50, 70, 1), JpegInfo.parse(jpeg("grey.jpg")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun jpegInfoRejectsNonJpeg() {
        JpegInfo.parse(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0))
    }

    @Test
    fun xrefOffsetsPointAtObjects() {
        val pdf = writePdf(jpeg("portrait.jpg") to emptyList(), jpeg("landscape.jpg") to emptyList())
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))

        val startXref = Regex("startxref\\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref", startXref))
        val header = Regex("xref\\n0 (\\d+)\\n").find(text, startXref)!!
        val size = header.groupValues[1].toInt()
        val entriesStart = header.range.last + 1
        for (id in 1 until size) {
            val entry = text.substring(entriesStart + id * 20, entriesStart + id * 20 + 20)
            val offset = entry.substring(0, 10).toInt()
            assertTrue("object $id at $offset", text.startsWith("$id 0 obj", offset))
        }
        assertTrue(text.contains("/Count 2"))
    }

    @Test
    fun landscapeImageGetsLandscapePage() {
        val layout = PageLayout.of(PageSize.A4, 3000, 2000)
        assertEquals(841.89f, layout.width, 0.01f)
        assertEquals(595.28f, layout.height, 0.01f)
        // 3:2 is wider than A4, so the image fills the width and is centred vertically.
        assertEquals(841.89f, layout.imageBox.width, 0.01f)
        assertEquals(561.26f, layout.imageBox.height, 0.01f)
        assertEquals((595.28f - 561.26f) / 2f, layout.imageBox.y, 0.01f)
    }

    @Test
    fun fitPageMatchesImageAspect() {
        val layout = PageLayout.of(PageSize.FIT, 1000, 2000)
        assertEquals(841.89f, layout.height, 0.01f)
        assertEquals(420.945f, layout.width, 0.01f)
        assertEquals(Box(0f, 0f, layout.width, layout.height), layout.imageBox)
    }

    @Test
    fun textIsInvisibleAndEscaped() {
        val pdf = String(writePdf(jpeg("portrait.jpg") to listOf(TextLine("Total (net) £5\\", 10, 10, 200, 40))), Charsets.ISO_8859_1)
        assertTrue(pdf.contains("3 Tr"))
        assertTrue(pdf.contains("(Total \\(net\\) \\2435\\\\) Tj"))
    }

    @Test
    fun numbersUsePlainNotation() {
        assertEquals("0", PdfWriter.num(0f))
        assertEquals("12.5", PdfWriter.num(12.5f))
        assertEquals("0.001", PdfWriter.num(0.001f))
        assertEquals("100000", PdfWriter.num(1e5f))
        assertEquals("0", PdfWriter.num(-0.0001f))
    }

    @Test
    fun winAnsiMapsTypographicCharacters() {
        assertEquals(listOf(0x93, 0x41, 0x94, 0x80, 0x3F), WinAnsi.encode("“A”€漢").map { it.toInt() and 0xFF })
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("Invoice_ 2026_09.pdf", pdfFileName("Invoice: 2026/09"))
        assertEquals("scan.pdf", pdfFileName("  ...  "))
    }

    /** Writes a sample to build/ so the output can be checked with external tools (e.g. pdftotext). */
    @Test
    fun writesSampleForManualInspection() {
        val lines = listOf(
            TextLine("Hello searchable world", 40, 50, 360, 80),
            TextLine("Second line of text", 40, 100, 300, 125),
        )
        val file = File("build/test-output/sample.pdf").apply { parentFile.mkdirs() }
        file.writeBytes(writePdf(jpeg("portrait.jpg") to lines, jpeg("landscape.jpg") to emptyList()))
        assertTrue(file.length() > 0)
    }
}
