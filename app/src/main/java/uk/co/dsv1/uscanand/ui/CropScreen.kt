package uk.co.dsv1.uscanand.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.dsv1.uscanand.R
import uk.co.dsv1.uscanand.app
import uk.co.dsv1.uscanand.image.ImageProcessor
import uk.co.dsv1.uscanand.image.QuadMath
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Manual crop: drag the four corners; the page is then straightened with a perspective warp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(docId: String, pageId: String, onDone: () -> Unit) {
    val repository = LocalContext.current.app.repository
    val documents by repository.documents.collectAsStateWithLifecycle()
    val page = documents?.find { it.id == docId }?.pages?.find { it.id == pageId }
    val scope = rememberCoroutineScope()

    LaunchedEffect(documents == null, page == null) {
        if (documents != null && page == null) onDone()
    }
    if (page == null) return

    // The editor shows the rotated (but uncropped, unfiltered) original; corners are kept in that space.
    val image by produceState<ImageBitmap?>(null, page.imageFile, page.rotation) {
        value = withContext(Dispatchers.Default) {
            val original = ImageProcessor.decodeFile(repository.imageFile(docId, page), EDITOR_MAX_DIMENSION)
            ImageProcessor.rotate(original, page.rotation).asImageBitmap()
        }
    }
    var corners by remember(page.id, page.rotation) {
        mutableStateOf(QuadMath.rotateQuad(page.crop ?: QuadMath.FULL, page.rotation))
    }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { corners = QuadMath.FULL }) { Text(stringResource(R.string.reset)) }
                    IconButton(
                        enabled = !saving && image != null,
                        onClick = {
                            saving = true
                            val crop = QuadMath.orderCorners(QuadMath.rotateQuad(corners, -page.rotation))
                            scope.launch {
                                repository.editPage(docId, pageId) { it.copy(crop = crop.takeUnless(QuadMath::isFull)) }
                                onDone()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.apply))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = image
            if (bitmap == null || saving) {
                CircularProgressIndicator()
            } else {
                CropEditor(bitmap, corners, onChange = { corners = it }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CropEditor(image: ImageBitmap, corners: List<Float>, onChange: (List<Float>) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val inset = with(density) { 28.dp.toPx() }
    val touchRadius = with(density) { 48.dp.toPx() }
    val handleRadius = with(density) { 10.dp.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    var size by remember { mutableStateOf(IntSize.Zero) }
    val imageRect = remember(size, image) { fitRect(image.width, image.height, size, inset) }
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChange by rememberUpdatedState(onChange)

    // Handles sit near the screen edges, where gesture navigation would otherwise treat a drag as "back".
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(corners, imageRect, origin) {
        val rects = (0 until 4).map {
            val c = cornerOffset(corners, it, imageRect) + origin
            android.graphics.Rect(
                (c.x - touchRadius).toInt(),
                (c.y - touchRadius).toInt(),
                (c.x + touchRadius).toInt(),
                (c.y + touchRadius).toInt(),
            )
        }
        ViewCompat.setSystemGestureExclusionRects(view, rects)
    }
    DisposableEffect(view) {
        onDispose { ViewCompat.setSystemGestureExclusionRects(view, emptyList()) }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(imageRect) {
                var active = -1
                detectDragGestures(
                    onDragStart = { position -> active = nearestCorner(currentCorners, imageRect, position, touchRadius) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, _ ->
                        if (active >= 0) {
                            change.consume()
                            val x = ((change.position.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f)
                            val y = ((change.position.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
                            currentOnChange(currentCorners.toMutableList().also { it[active * 2] = x; it[active * 2 + 1] = y })
                        }
                    },
                )
            },
    ) {
        if (imageRect.width <= 0f || imageRect.height <= 0f) return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset(imageRect.left.roundToInt(), imageRect.top.roundToInt()),
            dstSize = IntSize(imageRect.width.roundToInt(), imageRect.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val points = (0 until 4).map { cornerOffset(corners, it, imageRect) }
        val quad = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(imageRect)
            addPath(quad)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.55f))
        drawPath(quad, accent, style = Stroke(width = strokeWidth))
        points.forEach {
            drawCircle(Color.White, radius = handleRadius + strokeWidth, center = it)
            drawCircle(accent, radius = handleRadius, center = it)
        }
    }
}

private fun fitRect(imageWidth: Int, imageHeight: Int, container: IntSize, inset: Float): Rect {
    val availableW = container.width - 2 * inset
    val availableH = container.height - 2 * inset
    if (availableW <= 0f || availableH <= 0f) return Rect.Zero
    val scale = min(availableW / imageWidth, availableH / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (container.width - w) / 2f
    val top = (container.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun cornerOffset(corners: List<Float>, index: Int, rect: Rect) =
    Offset(rect.left + corners[index * 2] * rect.width, rect.top + corners[index * 2 + 1] * rect.height)

private fun nearestCorner(corners: List<Float>, rect: Rect, position: Offset, maxDistance: Float): Int {
    val (index, distance) = (0 until 4)
        .map { it to cornerOffset(corners, it, rect).let { c -> hypot(c.x - position.x, c.y - position.y) } }
        .minBy { it.second }
    return if (distance <= maxDistance) index else -1
}

private const val EDITOR_MAX_DIMENSION = 1600
