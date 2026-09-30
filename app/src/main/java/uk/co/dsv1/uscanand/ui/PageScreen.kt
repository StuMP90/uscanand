package uk.co.dsv1.uscanand.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.dsv1.uscanand.R
import uk.co.dsv1.uscanand.app
import uk.co.dsv1.uscanand.data.Page
import uk.co.dsv1.uscanand.data.PageFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(docId: String, pageId: String, onBack: () -> Unit, onCrop: () -> Unit) {
    val context = LocalContext.current
    val repository = context.app.repository
    val documents by repository.documents.collectAsStateWithLifecycle()
    val doc = documents?.find { it.id == docId }
    val index = doc?.pages?.indexOfFirst { it.id == pageId } ?: -1
    val page = doc?.pages?.getOrNull(index)
    val scope = rememberCoroutineScope()

    LaunchedEffect(documents == null, page == null) {
        if (documents != null && page == null) onBack()
    }

    var pendingEdits by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (doc == null || page == null) return

    fun edit(transform: (Page) -> Page) {
        scope.launch {
            pendingEdits++
            try {
                repository.editPage(docId, pageId, transform)
            } finally {
                pendingEdits--
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.page_of, index + 1, doc.pages.size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_page))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PageFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = page.filter == filter,
                                onClick = { edit { it.copy(filter = filter) } },
                                label = { Text(stringResource(filter.label())) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToolButton(Icons.AutoMirrored.Filled.RotateLeft, R.string.rotate_left) {
                            edit { it.copy(rotation = (it.rotation + 270) % 360) }
                        }
                        ToolButton(Icons.AutoMirrored.Filled.RotateRight, R.string.rotate_right) {
                            edit { it.copy(rotation = (it.rotation + 90) % 360) }
                        }
                        ToolButton(Icons.Filled.Crop, R.string.crop, onClick = onCrop)
                        ToolButton(Icons.Filled.Straighten, R.string.flatten, selected = page.isFlattened) {
                            val enable = !page.isFlattened
                            scope.launch {
                                pendingEdits++
                                try {
                                    if (!repository.setFlatten(docId, pageId, enable)) {
                                        context.toast(context.getString(R.string.flatten_failed))
                                    }
                                } finally {
                                    pendingEdits--
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
            PageImage(repository.previewFile(docId, page), page.version, Modifier.fillMaxSize())
            if (pendingEdits > 0) CircularProgressIndicator()
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_page_title),
            text = stringResource(R.string.delete_page_text),
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { repository.deletePage(docId, pageId) }
            },
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, @StringRes label: Int, selected: Boolean = false, onClick: () -> Unit) {
    val colors = if (selected) {
        ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        ButtonDefaults.textButtonColors()
    }
    TextButton(onClick = onClick, colors = colors) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        }
    }
}
