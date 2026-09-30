package uk.co.dsv1.uscanand.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import uk.co.dsv1.uscanand.image.WordBox
import uk.co.dsv1.uscanand.pdf.TextLine
import java.io.Closeable

/** On-device text recognition (Latin script) using ML Kit via Google Play services. */
class OcrEngine : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Recognises text lines, positioned in [bitmap]'s pixel coordinates. */
    suspend fun recognise(bitmap: Bitmap): List<TextLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TextLine(line.text, box.left, box.top, box.right, box.bottom)
        }
    }

    /** Recognises words, grouped by text line, positioned in [bitmap]'s pixel coordinates. */
    suspend fun recogniseWords(bitmap: Bitmap): List<List<WordBox>> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { it.lines }.map { line ->
            line.elements.mapNotNull { element ->
                element.boundingBox?.let { WordBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            }
        }.filter { it.isNotEmpty() }
    }

    override fun close() = recognizer.close()
}
