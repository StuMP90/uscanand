package uk.co.dsv1.uscanand.pdf

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uk.co.dsv1.uscanand.data.DocumentRepository
import uk.co.dsv1.uscanand.data.ExportSettings
import uk.co.dsv1.uscanand.data.ScanDocument
import uk.co.dsv1.uscanand.image.ImageProcessor
import uk.co.dsv1.uscanand.ocr.OcrEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PdfExporter(private val context: Context, private val repository: DocumentRepository) {

    data class Result(val file: File, val ocrFailed: Boolean)

    /** Renders every page of [doc] into a PDF in the app's cache. [onProgress] gets (pages done, total). */
    suspend fun export(doc: ScanDocument, settings: ExportSettings, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.Default) {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val staleBefore = System.currentTimeMillis() - STALE_EXPORT_MS
            dir.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }

            val file = File(dir, pdfFileName(doc.name))
            val ocr = if (settings.ocr) OcrEngine() else null
            var ocrFailed = false
            try {
                FileOutputStream(file).use { stream ->
                    PdfWriter(stream, title = doc.name).use { writer ->
                        doc.pages.forEachIndexed { index, page ->
                            ensureActive()
                            onProgress(index, doc.pages.size)
                            val original = ImageProcessor.decodeFile(repository.imageFile(doc.id, page), settings.quality.maxDimension)
                            val rendered = ImageProcessor.render(original, page, repository.loadMesh(doc.id, page))
                            try {
                                val lines = if (ocr != null && !ocrFailed) {
                                    try {
                                        ocr.recognise(rendered)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Text recognition failed; continuing without it", e)
                                        ocrFailed = true
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
                                val jpeg = ByteArrayOutputStream().also {
                                    rendered.compress(Bitmap.CompressFormat.JPEG, settings.quality.jpegQuality, it)
                                }.toByteArray()
                                val layout = PageLayout.of(settings.pageSize, rendered.width, rendered.height)
                                writer.addPage(jpeg, layout.width, layout.height, layout.imageBox, lines)
                            } finally {
                                if (rendered !== original) rendered.recycle()
                                original.recycle()
                            }
                        }
                        onProgress(doc.pages.size, doc.pages.size)
                    }
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            } finally {
                ocr?.close()
            }
            Result(file, ocrFailed)
        }

    companion object {
        const val EXPORT_DIR = "exports"
        private const val TAG = "PdfExporter"
        private const val STALE_EXPORT_MS = 60 * 60 * 1000L
    }
}
