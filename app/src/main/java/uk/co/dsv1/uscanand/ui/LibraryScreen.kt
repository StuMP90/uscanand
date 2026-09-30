package uk.co.dsv1.uscanand.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.dsv1.uscanand.R
import uk.co.dsv1.uscanand.app
import uk.co.dsv1.uscanand.data.ScanDocument
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenDocument: (String) -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.app
    val repository = app.repository
    val documents by repository.documents.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val createFrom: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            val doc = repository.createDocument()
            app.importPages(doc.id, uris)
            onOpenDocument(doc.id)
        }
    }
    val scan = rememberDocumentScanner(createFrom)
    val pickImages = rememberImagePicker(createFrom)

    var renaming by remember { mutableStateOf<ScanDocument?>(null) }
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = pickImages) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = stringResource(R.string.import_images))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = scan,
                icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                text = { Text(stringResource(R.string.scan)) },
            )
        },
    ) { padding ->
        val docs = documents
        when {
            docs == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            docs.isEmpty() -> EmptyLibrary(Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(docs, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        onClick = { onOpenDocument(doc.id) },
                        onRename = { renaming = doc },
                        onDelete = { deleting = doc },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    renaming?.let { doc ->
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = doc.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                scope.launch { repository.renameDocument(doc.id, name) }
                renaming = null
            },
        )
    }
    deleting?.let { doc ->
        ConfirmDialog(
            title = stringResource(R.string.delete_document_title),
            text = stringResource(R.string.delete_document_text, doc.name),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { deleting = null },
            onConfirm = {
                scope.launch { repository.deleteDocument(doc.id) }
                deleting = null
            },
        )
    }
}

@Composable
private fun DocumentRow(
    doc: ScanDocument,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalContext.current.app.repository
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 56.dp, height = 72.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val first = doc.pages.firstOrNull()
                if (first != null) {
                    PageImage(repository.previewFile(doc.id, first), first.version, Modifier.fillMaxSize(), ContentScale.Crop)
                } else {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = remember(doc.updatedAt) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(doc.updatedAt))
                }
                Text(
                    pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size) + " · " + date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverflowMenu { dismiss ->
                MenuItem(R.string.rename, Icons.Filled.Edit) { dismiss(); onRename() }
                MenuItem(R.string.delete, Icons.Filled.Delete) { dismiss(); onDelete() }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.padding(8.dp))
        Text(stringResource(R.string.empty_library_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(4.dp))
        Text(
            stringResource(R.string.empty_library_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
