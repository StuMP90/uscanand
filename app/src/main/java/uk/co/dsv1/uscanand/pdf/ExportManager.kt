package uk.co.dsv1.uscanand.pdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.dsv1.uscanand.data.DocumentRepository
import uk.co.dsv1.uscanand.data.SettingsStore
import java.io.File
import java.io.IOException

sealed interface ExportTarget {
    data object Share : ExportTarget
    data class SaveTo(val uri: Uri) : ExportTarget
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val docId: String, val done: Int, val total: Int) : ExportState
    data class Finished(val docId: String, val file: File, val target: ExportTarget, val ocrFailed: Boolean) : ExportState
    data class Failed(val docId: String, val message: String) : ExportState
}

/** Runs PDF exports in the application scope so they survive configuration changes. */
class ExportManager(
    private val context: Context,
    private val repository: DocumentRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val exporter = PdfExporter(context, repository)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(docId: String, target: ExportTarget) {
        if (_state.value is ExportState.Running) return
        val doc = repository.find(docId)?.takeIf { it.pages.isNotEmpty() } ?: return
        _state.value = ExportState.Running(docId, 0, doc.pages.size)
        job = scope.launch {
            try {
                val result = exporter.export(doc, settings.state.value) { done, total ->
                    _state.value = ExportState.Running(docId, done, total)
                }
                if (target is ExportTarget.SaveTo) copyTo(result.file, target.uri)
                _state.value = ExportState.Finished(docId, result.file, target, result.ocrFailed)
            } catch (e: CancellationException) {
                _state.value = ExportState.Idle
                throw e
            } catch (e: Throwable) {
                _state.value = ExportState.Failed(docId, e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Called by the UI once it has acted on a Finished or Failed state. */
    fun acknowledge() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    private suspend fun copyTo(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri)
            ?: throw IOException("Cannot write to the chosen location")
        output.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
