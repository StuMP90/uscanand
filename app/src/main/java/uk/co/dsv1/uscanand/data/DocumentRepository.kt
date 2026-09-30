package uk.co.dsv1.uscanand.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uk.co.dsv1.uscanand.image.ImageProcessor
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Stores documents as folders under the app's private files directory: a `document.json`
 * plus each page's original image and rendered preview.
 */
class DocumentRepository(private val context: Context) {
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Guards the document list and JSON files. */
    private val listMutex = Mutex()

    /** Serialises page edits so the latest page state is always the one rendered. */
    private val editMutex = Mutex()

    private val _documents = MutableStateFlow<List<ScanDocument>?>(null)

    /** All documents, most recently updated first; null until loaded. */
    val documents: StateFlow<List<ScanDocument>?> = _documents.asStateFlow()

    private val _importing = MutableStateFlow<Set<String>>(emptySet())

    /** IDs of documents that are currently receiving new pages. */
    val importing: StateFlow<Set<String>> = _importing.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
            val file = File(dir, DOCUMENT_JSON)
            if (!file.isFile) return@mapNotNull null
            runCatching { json.decodeFromString<ScanDocument>(file.readText()) }
                .onFailure { Log.w(TAG, "Skipping unreadable document in $dir", it) }
                .getOrNull()
        }
        listMutex.withLock { _documents.value = loaded.sortedByDescending { it.updatedAt } }
    }

    fun find(id: String): ScanDocument? = _documents.value?.find { it.id == id }

    fun imageFile(docId: String, page: Page) = File(dir(docId), page.imageFile)

    fun previewFile(docId: String, page: Page) = File(dir(docId), page.previewFile)

    suspend fun createDocument(): ScanDocument = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date(now))
        val doc = ScanDocument(id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now)
        listMutex.withLock {
            dir(doc.id).mkdirs()
            writeJson(doc)
            _documents.value = listOf(doc) + _documents.value.orEmpty()
        }
        doc
    }

    /** Copies images into a document as new pages, in order. Returns how many could not be read. */
    suspend fun addImages(docId: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        _importing.update { it + docId }
        var failures = 0
        try {
            for (uri in uris) {
                try {
                    val id = UUID.randomUUID().toString()
                    val page = Page(id = id, imageFile = "$id.jpg")
                    val bitmap = ImageProcessor.decodeUri(context, uri, ORIGINAL_MAX_DIMENSION)
                    try {
                        ImageProcessor.writeJpeg(bitmap, imageFile(docId, page), ORIGINAL_JPEG_QUALITY)
                        editMutex.withLock { writePreview(docId, page, bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                    modify(docId) { it.copy(pages = it.pages + page) } ?: throw IOException("Document was deleted")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not import $uri", e)
                    failures++
                }
            }
        } finally {
            _importing.update { it - docId }
        }
        failures
    }

    /** Applies [transform] to the latest state of a page and re-renders its preview. */
    suspend fun editPage(docId: String, pageId: String, transform: (Page) -> Page) = withContext(Dispatchers.Default) {
        editMutex.withLock {
            val page = find(docId)?.pages?.find { it.id == pageId } ?: return@withLock
            val edited = transform(page).copy(version = page.version)
            if (edited == page) return@withLock
            writePreview(docId, edited, source = null)
            val updated = edited.copy(version = page.version + 1)
            modify(docId) { doc -> doc.copy(pages = doc.pages.map { if (it.id == pageId) updated else it }) }
        }
        Unit
    }

    suspend fun movePage(docId: String, from: Int, to: Int) {
        modify(docId) { doc ->
            if (from !in doc.pages.indices || to !in doc.pages.indices) return@modify null
            doc.copy(pages = doc.pages.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    suspend fun deletePage(docId: String, pageId: String) {
        val removed = find(docId)?.pages?.find { it.id == pageId } ?: return
        modify(docId) { doc -> doc.copy(pages = doc.pages.filterNot { it.id == pageId }) }
        withContext(Dispatchers.IO) {
            imageFile(docId, removed).delete()
            previewFile(docId, removed).delete()
        }
    }

    suspend fun renameDocument(docId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        modify(docId) { it.copy(name = trimmed) }
    }

    suspend fun deleteDocument(docId: String) = withContext(Dispatchers.IO) {
        listMutex.withLock {
            _documents.value = _documents.value?.filterNot { it.id == docId }
            dir(docId).deleteRecursively()
        }
    }

    private fun dir(docId: String) = File(root, docId)

    private fun writePreview(docId: String, page: Page, source: Bitmap?) {
        val original = source?.let { ImageProcessor.scaleToFit(it, PREVIEW_MAX_DIMENSION) }
            ?: ImageProcessor.decodeFile(imageFile(docId, page), PREVIEW_MAX_DIMENSION)
        val rendered = ImageProcessor.render(original, page)
        try {
            ImageProcessor.writeJpeg(rendered, previewFile(docId, page), PREVIEW_JPEG_QUALITY)
        } finally {
            if (rendered !== original) rendered.recycle()
            if (original !== source) original.recycle()
        }
    }

    /** Replaces a document with [block]'s result (null means no change) and persists it. */
    private suspend fun modify(docId: String, block: (ScanDocument) -> ScanDocument?): ScanDocument? =
        withContext(Dispatchers.IO) {
            listMutex.withLock {
                val current = _documents.value?.find { it.id == docId } ?: return@withLock null
                val updated = block(current)?.copy(updatedAt = System.currentTimeMillis()) ?: return@withLock null
                writeJson(updated)
                _documents.value = (listOf(updated) + _documents.value.orEmpty().filterNot { it.id == docId })
                    .sortedByDescending { it.updatedAt }
                updated
            }
        }

    private fun writeJson(doc: ScanDocument) {
        val target = File(dir(doc.id), DOCUMENT_JSON)
        val tmp = File(dir(doc.id), "$DOCUMENT_JSON.tmp")
        tmp.writeText(json.encodeToString(ScanDocument.serializer(), doc))
        if (!tmp.renameTo(target)) throw IOException("Cannot save $target")
    }

    private companion object {
        const val TAG = "DocumentRepository"
        const val DOCUMENT_JSON = "document.json"
        const val ORIGINAL_MAX_DIMENSION = 3000
        const val ORIGINAL_JPEG_QUALITY = 92
        const val PREVIEW_MAX_DIMENSION = 1200
        const val PREVIEW_JPEG_QUALITY = 85
    }
}
