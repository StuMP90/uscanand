package uk.co.dsv1.uscanand.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.dsv1.uscanand.R
import uk.co.dsv1.uscanand.app
import uk.co.dsv1.uscanand.data.Page
import uk.co.dsv1.uscanand.pdf.ExportManager
import uk.co.dsv1.uscanand.pdf.ExportState
import uk.co.dsv1.uscanand.pdf.ExportTarget
import uk.co.dsv1.uscanand.pdf.pdfFileName
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(docId: String, onBack: () -> Unit, onOpenPage: (String) -> Unit) {
    val app = LocalContext.current.app
    val repository = app.repository
    val documents by repository.documents.collectAsStateWithLifecycle()
    val importing by repository.importing.collectAsStateWithLifecycle()
    val exportState by app.exporter.state.collectAsStateWithLifecycle()
    val doc = documents?.find { it.id == docId }
    val scope = rememberCoroutineScope()

    LaunchedEffect(documents == null, doc == null) {
        if (documents != null && doc == null) onBack()
    }

    val scanMore = rememberDocumentScanner { app.importPages(docId, it) }
    val pickImages = rememberImagePicker { app.importPages(docId, it) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) app.exporter.start(docId, ExportTarget.SaveTo(uri))
    }

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pageToDelete by remember { mutableStateOf<Page?>(null) }

    if (doc == null) return
    val hasPages = doc.pages.isNotEmpty()
    val isImporting = docId in importing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        doc.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { saveLauncher.launch(pdfFileName(doc.name)) }, enabled = hasPages && !isImporting) {
                        Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.save_pdf))
                    }
                    OverflowMenu { dismiss ->
                        MenuItem(R.string.rename, Icons.Filled.Edit) { dismiss(); renaming = true }
                        MenuItem(R.string.delete_document, Icons.Filled.Delete) { dismiss(); confirmDelete = true }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    TextButton(onClick = scanMore) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_more))
                    }
                    TextButton(onClick = pickImages) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_short))
                    }
                },
                floatingActionButton = {
                    if (hasPages && !isImporting) {
                        ExtendedFloatingActionButton(
                            onClick = { app.exporter.start(docId, ExportTarget.Share) },
                            icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            text = { Text(stringResource(R.string.share_pdf)) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isImporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!hasPages) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (isImporting) R.string.importing_pages else R.string.no_pages),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(doc.pages, key = { _, page -> page.id }) { index, page ->
                        PageCard(
                            file = repository.previewFile(docId, page),
                            version = page.version,
                            number = index + 1,
                            total = doc.pages.size,
                            onClick = { onOpenPage(page.id) },
                            onMove = { delta -> scope.launch { repository.movePage(docId, index, index + delta) } },
                            onRotate = {
                                scope.launch { repository.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } }
                            },
                            onDelete = { pageToDelete = page },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    ExportProgress(docId, exportState, app.exporter)

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = doc.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                scope.launch { repository.renameDocument(docId, name) }
                renaming = false
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_document_title),
            text = stringResource(R.string.delete_document_text, doc.name),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { repository.deleteDocument(docId) }
            },
        )
    }
    pageToDelete?.let { page ->
        ConfirmDialog(
            title = stringResource(R.string.delete_page_title),
            text = stringResource(R.string.delete_page_text),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { pageToDelete = null },
            onConfirm = {
                pageToDelete = null
                scope.launch { repository.deletePage(docId, page.id) }
            },
        )
    }
}

@Composable
private fun PageCard(
    file: File,
    version: Int,
    number: Int,
    total: Int,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PageImage(file, version, Modifier.fillMaxSize().padding(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OverflowMenu { dismiss ->
                if (number > 1) MenuItem(R.string.move_earlier, Icons.AutoMirrored.Filled.ArrowBack) { dismiss(); onMove(-1) }
                if (number < total) MenuItem(R.string.move_later, Icons.AutoMirrored.Filled.ArrowForward) { dismiss(); onMove(1) }
                MenuItem(R.string.rotate_right, Icons.AutoMirrored.Filled.RotateRight) { dismiss(); onRotate() }
                MenuItem(R.string.delete_page, Icons.Filled.Delete) { dismiss(); onDelete() }
            }
        }
    }
}

/** Shows export progress for [docId] and acts on the result: open the share sheet or confirm the save. */
@Composable
private fun ExportProgress(docId: String, state: ExportState, exporter: ExportManager) {
    val context = LocalContext.current
    when (state) {
        is ExportState.Running -> if (state.docId == docId) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.creating_pdf)) },
                text = {
                    Column {
                        val current = minOf(state.done + 1, state.total)
                        Text(stringResource(R.string.export_progress, current, state.total))
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = exporter::cancel) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
        is ExportState.Finished -> if (state.docId == docId) {
            LaunchedEffect(state) {
                if (state.ocrFailed) context.toast(context.getString(R.string.ocr_unavailable))
                when (state.target) {
                    ExportTarget.Share -> sharePdf(context, state.file)
                    is ExportTarget.SaveTo -> context.toast(context.getString(R.string.pdf_saved))
                }
                exporter.acknowledge()
            }
        }
        is ExportState.Failed -> if (state.docId == docId) {
            LaunchedEffect(state) {
                context.toast(context.getString(R.string.export_failed, state.message))
                exporter.acknowledge()
            }
        }
        ExportState.Idle -> Unit
    }
}
