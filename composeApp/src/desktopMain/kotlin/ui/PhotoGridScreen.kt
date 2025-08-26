package ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.launch
import models.Photo
import models.PhotoStatus
import services.CachedProject
import utils.ImageUtils
import utils.Logger
import viewmodels.FotoFilterViewModel
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGridScreen(
    viewModel: FotoFilterViewModel = remember { FotoFilterViewModel() }
) {
    val state by viewModel.state.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val isCacheLoading by viewModel.isCacheLoading.collectAsState()
    val cachedProjects by viewModel.cachedProjects.collectAsState()
    val isLoadingProjects by viewModel.isLoadingProjects.collectAsState()
    var showFolderDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var showShortcutsDialog by remember { mutableStateOf(false) }
    var isSidebarVisible by remember { mutableStateOf(false) } // Changed from true to false so sidebar is folded by default
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var showSkippedDialog by remember { mutableStateOf(false) }

    // Load cached projects when screen is first shown
    LaunchedEffect(Unit) {
        viewModel.loadCachedProjects()
    }

    // Debug logging for cached projects state
    LaunchedEffect(cachedProjects, isLoadingProjects) {
        Logger.ui.info { "CachedProjects state changed: ${cachedProjects.size} projects, isLoading: $isLoadingProjects" }
        cachedProjects.forEach { project ->
            Logger.ui.info { "Project: ${project.folderName} at ${project.folderPath}" }
        }
    }

    // Handle export overlay animation
    val exportOverlayAlpha by animateFloatAsState(
        targetValue = if (state.isExporting || state.exportCompleted) 1f else 0f,
        animationSpec = tween(durationMillis = 500)
    )

    Box(modifier = Modifier.fillMaxSize()
        .focusRequester(focusRequester)
        .onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    Key.K, Key.Spacebar -> viewModel.handleKeyPress("space")
                    Key.D, Key.Delete -> viewModel.handleKeyPress("delete")
                    Key.U -> viewModel.handleKeyPress("u")
                    Key.E -> {
                        if (state.keptPhotos > 0 || state.discardedPhotos > 0) showExportOptionsDialog = true
                        true
                    }
                    Key.H -> {
                        showShortcutsDialog = true
                        true
                    }
                    Key.DirectionRight -> viewModel.handleKeyPress("ArrowRight")
                    Key.DirectionLeft -> viewModel.handleKeyPress("ArrowLeft")
                    else -> return@onKeyEvent false
                }
                true
            } else {
                false
            }
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { focusRequester.requestFocus() }
    ) {
        // Main content
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Minimal top bar that's smaller when no photos are loaded
            if (state.photos.isEmpty() && !state.isLoading && importProgress == null) {
                // Ultra minimal header before folder selection
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { showFolderDialog = true },
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text("Open Photo Folder", style = MaterialTheme.typography.titleMedium)
                        }

                        OutlinedButton(
                            onClick = { showShortcutsDialog = true },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Keyboard Shortcuts", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            } else if (importProgress == null) {
                // Standard toolbar when photos are loaded and not importing
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { showFolderDialog = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("Open", style = MaterialTheme.typography.labelMedium)
                        }

                        // Status counts
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                MaterialTheme.shapes.small
                            ).padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Kept: ${state.keptPhotos}",
                                color = Color.Green.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text("|", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "Discarded: ${state.discardedPhotos}",
                                color = Color.Red.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text("|", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "Remaining: ${state.remainingPhotos}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        // Cache loading indicator when building cache
                        if (isCacheLoading) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    MaterialTheme.shapes.small
                                ).padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Building cache...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = { if (state.keptPhotos > 0 || state.discardedPhotos > 0) showExportOptionsDialog = true },
                            enabled = state.keptPhotos > 0 || state.discardedPhotos > 0,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "Export (${state.keptPhotos})",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        OutlinedButton(
                            onClick = { showShortcutsDialog = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("?", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }

            when {
                importProgress != null -> {
                    // Full screen loading animation with progress
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(500.dp)
                                .padding(32.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Text(
                                    "Processing Photos",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                val (current, total) = importProgress ?: (0 to 0)
                                val progress = if (total > 0) current.toFloat() / total else 0f

                                // Large circular progress indicator
                                Box(
                                    modifier = Modifier.size(120.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxSize(),
                                        strokeWidth = 8.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    "Generating thumbnails and previews...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    "$current of $total photos processed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Linear progress bar
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    "This will make browsing much faster!",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Scanning folder...")
                        }
                    }
                }

                state.photos.isEmpty() -> {
                    // Empty state with cached projects or instructions
                    if (cachedProjects.isNotEmpty()) {
                        // Show cached projects
                        CachedProjectsScreen(
                            cachedProjects = cachedProjects,
                            isLoading = isLoadingProjects,
                            onProjectSelected = { project ->
                                viewModel.loadCachedProject(project)
                                // Request focus after loading project to ensure keyboard shortcuts work
                                focusRequester.requestFocus()
                            },
                            onProjectDeleted = { project ->
                                viewModel.deleteCachedProject(project)
                            },
                            onOpenNewFolder = { showFolderDialog = true }
                        )
                    } else {
                        // Original empty state with instructions
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Text(
                                    "Foto-Filter",
                                    style = MaterialTheme.typography.headlineLarge
                                )

                                Text(
                                    "Select a folder of photos to begin filtering",
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Spacer(Modifier.height(16.dp))

                                Column(
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Keyboard Shortcuts:", fontWeight = FontWeight.Bold)
                                    Text("K or Space: Keep photo")
                                    Text("D or Delete: Discard photo")
                                    Text("U: Undecide (reset status)")
                                    Text("← →: Navigate between photos")
                                    Text("E: Export kept photos")
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Main layout with preview and grid
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Status indicator
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Photo ${state.selectedIndex + 1} of ${state.totalPhotos}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // Two-panel layout - preview and grid
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            // Large preview (left side)
                            Box(modifier = Modifier.weight(if (isSidebarVisible) 0.6f else 1f).fillMaxHeight().padding(end = 8.dp)) {
                                state.selectedPhoto?.let { photo ->
                                    state.folderPath?.let { folderPath ->
                                        LargePhotoPreview(photo = photo, folderPath = folderPath)
                                    }
                                }
                            }

                            // Grid of thumbnails (right side)
                            if (isSidebarVisible) {
                                Box(
                                    modifier = Modifier
                                        .weight(0.4f)
                                        .fillMaxHeight()
                                ) {
                                    // Sidebar content
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        PhotoGrid(
                                            photos = state.photos,
                                            folderPath = state.folderPath ?: "",
                                            selectedIndex = state.selectedIndex,
                                            onPhotoClick = { index ->
                                                viewModel.onPhotoSelected(index)
                                            }
                                        )
                                    }
                                }
                            }

                            // Toggle button - always visible
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .align(Alignment.CenterVertically)
                            ) {
                                IconButton(
                                    onClick = { isSidebarVisible = !isSidebarVisible }
                                ) {
                                    Icon(
                                        imageVector = if (isSidebarVisible) Icons.Default.ArrowBack else Icons.Default.ArrowForward,
                                        contentDescription = "Toggle Sidebar",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Export or Delete overlay (combined logic)
        if (state.isExporting || state.exportCompleted || state.isDeleting || state.deleteCompleted) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f * exportOverlayAlpha))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .width(480.dp) // make wider so text and buttons fit
                        .padding(16.dp)
                        .animateContentSize(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        when {
                            state.isExporting -> {
                                CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                                Text("Copying kept photos…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small))
                            }
                            state.exportCompleted -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp).background(Color(0xFF4CAF50), CircleShape).padding(16.dp),
                                    tint = Color.White
                                )
                                Text("Export Complete", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "${state.keptPhotos} photos copied to:\n${state.exportPath}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                            state.isDeleting -> {
                                CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 4.dp, color = MaterialTheme.colorScheme.error)
                                Text("Deleting discarded photos…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small), color = MaterialTheme.colorScheme.error)
                            }
                            state.deleteCompleted -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp).background(Color(0xFFD32F2F), CircleShape).padding(16.dp),
                                    tint = Color.White
                                )
                                Text("Deletion Complete", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "${state.lastDeletedCount} discarded photos deleted",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        FolderPickerDialog(
            title = "Select Photo Folder",
            onFolderSelected = { folderPath ->
                showFolderDialog = false
                if (folderPath != null) {
                    coroutineScope.launch {
                        // Use the new preloading method instead of loadPhotos
                        viewModel.loadPhotosWithPreload(folderPath)
                    }
                }
                // Request focus again after dialog closes
                focusRequester.requestFocus()
            },
            onDismiss = {
                showFolderDialog = false
                // Request focus again after dialog closes
                focusRequester.requestFocus()
            }
        )
    }

    // New export options dialog
    if (showExportOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showExportOptionsDialog = false },
            title = { Text("Actions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose an action for your selections:")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistChip(
                            onClick = {
                                showExportOptionsDialog = false
                                showExportDialog = true
                            },
                            label = { Text("Copy kept photos") },
                            enabled = state.keptPhotos > 0
                        )
                        AssistChip(
                            onClick = {
                                showExportOptionsDialog = false
                                viewModel.deleteDiscardedPhotos()
                            },
                            label = { Text("Delete discarded") },
                            enabled = state.discardedPhotos > 0,
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        )
                    }
                    Text("Kept: ${state.keptPhotos}  •  Discarded: ${state.discardedPhotos}", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportOptionsDialog = false }) { Text("Close") }
            }
        )
    }

    if (showExportDialog) {
        FolderPickerDialog(
            title = "Select Export Destination",
            onFolderSelected = { folderPath ->
                showExportDialog = false
                if (folderPath != null) {
                    viewModel.exportKeptPhotos(folderPath)
                }
                // Request focus again after dialog closes
                focusRequester.requestFocus()
            },
            onDismiss = {
                showExportDialog = false
                // Request focus again after dialog closes
                focusRequester.requestFocus()
            }
        )
    }

    if (showShortcutsDialog) {
        ShortcutsDialog(onDismiss = {
            showShortcutsDialog = false
            // Request focus again after dialog closes
            focusRequester.requestFocus()
        })
    }

    // Show warning dialog if skippedFiles not empty after loading photos
    LaunchedEffect(state.skippedFiles) {
        if (state.skippedFiles.isNotEmpty()) showSkippedDialog = true
    }

    // Keyboard handling
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (showSkippedDialog) {
        AlertDialog(
            onDismissRequest = { showSkippedDialog = false },
            title = { Text("Some files were skipped") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.skippedFiles.size} files could not be imported (unsupported format).")
                    Spacer(Modifier.height(8.dp))
                    val preview = state.skippedFiles.take(10)
                    preview.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    if (state.skippedFiles.size > preview.size) {
                        Text("…and ${state.skippedFiles.size - preview.size} more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkippedDialog = false }) { Text("OK") }
            }
        )
    }
}

@Composable
fun LargePhotoPreview(photo: Photo, folderPath: String) {
    // Using optimized preview image loading with simplified ImageUtils
    var imageBitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photo.id) {
        imageBitmap = ImageUtils.getPreview(photo, folderPath)
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Reset zoom when photo changes
    LaunchedEffect(photo.id) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(
                when (photo.status) {
                    PhotoStatus.KEEP -> Color(0xFF1B5E20).copy(alpha = 0.05f)
                    PhotoStatus.DISCARD -> Color(0xFFB71C1C).copy(alpha = 0.05f)
                    PhotoStatus.UNDECIDED -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(
                width = 2.dp,
                color = when (photo.status) {
                    PhotoStatus.KEEP -> Color(0xFF2E7D32)
                    PhotoStatus.DISCARD -> Color(0xFFC62828)
                    PhotoStatus.UNDECIDED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                shape = MaterialTheme.shapes.medium
            ),
        contentAlignment = Alignment.Center
    ) {
        // Image loading with zoom functionality
        imageBitmap?.let { bitmap ->
            ZoomableImage(
                bitmap = bitmap,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                onScaleChange = { newScale -> scale = newScale },
                onOffsetChange = { newOffsetX, newOffsetY ->
                    offsetX = newOffsetX
                    offsetY = newOffsetY
                },
                modifier = Modifier.fillMaxSize()
            )
        } ?: run {
            // Minimal placeholder - no loading indicator since images load so fast from cache
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                // Just show a subtle background, no spinning indicator
            }
        }

        // Zoom controls overlay
        if (scale > 1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Button(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Status overlay
        when (photo.status) {
            PhotoStatus.KEEP -> {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFF2E7D32).copy(alpha = 0.8f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Kept",
                            modifier = Modifier.size(48.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            PhotoStatus.DISCARD -> {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFFC62828).copy(alpha = 0.8f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Discarded",
                            modifier = Modifier.size(48.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            PhotoStatus.UNDECIDED -> {
                // No overlay for undecided
            }
        }

        // Zoom instructions overlay (when not zoomed)
        if (scale == 1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    text = "Mouse wheel: Zoom • Drag: Pan",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhotoGrid(
    photos: List<Photo>,
    folderPath: String,
    selectedIndex: Int,
    onPhotoClick: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isScrolling by remember { mutableStateOf(false) }
    var scrollResetJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Simplified scroll detection - only for very basic optimizations
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.onPointerEvent(PointerEventType.Scroll) {
            // Much simpler scroll detection - just track if we're actively scrolling
            scrollResetJob?.cancel()
            isScrolling = true

            // Reset scrolling state with a short delay
            scrollResetJob = coroutineScope.launch {
                kotlinx.coroutines.delay(150) // Shorter delay for more responsive feel
                isScrolling = false
            }
        }
    ) {
        itemsIndexed(photos) { index, photo ->
            SmoothPhotoThumbnail(
                photo = photo,
                folderPath = folderPath,
                isSelected = index == selectedIndex,
                isScrolling = isScrolling,
                onClick = { onPhotoClick(index) }
            )
        }
    }
}

@Composable
fun SmoothPhotoThumbnail(
    photo: Photo,
    folderPath: String,
    isSelected: Boolean,
    isScrolling: Boolean,
    onClick: () -> Unit
) {
    var imageBitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(photo.id) { mutableStateOf(false) }

    // Much more permissive loading strategy - always try to load unless actively scrolling very fast
    LaunchedEffect(photo.id) {
        if (imageBitmap == null && !isLoading) {
            isLoading = true
            try {
                // Always try to load the image, even during scrolling
                imageBitmap = ImageUtils.getThumbnail(photo, folderPath)
            } catch (e: Exception) {
                // Only handle critical memory errors
                if (e.message?.contains("OutOfMemory") == true) {
                    Logger.ui.warn { "Memory warning for ${photo.fileName}, will retry later" }
                }
            } finally {
                isLoading = false
            }
        }
    }

    // Retry loading when scrolling stops, but only if image is still null
    LaunchedEffect(isScrolling) {
        if (!isScrolling && imageBitmap == null && !isLoading) {
            kotlinx.coroutines.delay(50) // Very short delay
            if (!isScrolling && imageBitmap == null) { // Double-check conditions
                isLoading = true
                try {
                    imageBitmap = ImageUtils.getThumbnail(photo, folderPath)
                } catch (e: Exception) {
                    // Handle errors silently for smoother UX
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(4f/3f)
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    photo.status == PhotoStatus.KEEP -> Color(0xFF1B5E20).copy(alpha = 0.1f)
                    photo.status == PhotoStatus.DISCARD -> Color(0xFFB71C1C).copy(alpha = 0.1f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            imageBitmap != null -> {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = photo.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            isLoading -> {
                // Minimal loading state to avoid visual interruptions
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Only show loading spinner if not scrolling and item is selected or nearby
                    if (!isScrolling && isSelected) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            else -> {
                // Clean placeholder state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Subtle placeholder icon
                    Text(
                        "📷",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        // Status indicator
        if (photo.status != PhotoStatus.UNDECIDED) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        when (photo.status) {
                            PhotoStatus.KEEP -> Color(0xFF1B5E20)
                            PhotoStatus.DISCARD -> Color(0xFFB71C1C)
                            else -> Color.Transparent
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (photo.status) {
                        PhotoStatus.KEEP -> Icons.Default.Check
                        PhotoStatus.DISCARD -> Icons.Default.Close
                        else -> Icons.Default.Check
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun FolderPickerDialog(
    title: String,
    onFolderSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        val dialog = FileDialog(Frame(), title)

        // For macOS to allow directory selection
        if (System.getProperty("os.name", "").lowercase().contains("mac")) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
        }

        dialog.isMultipleMode = false
        dialog.isVisible = true

        if (dialog.directory != null && dialog.file != null) {
            onFolderSelected(dialog.directory + dialog.file)
        } else {
            onFolderSelected(null)
        }

        // For macOS, reset the property
        if (System.getProperty("os.name", "").lowercase().contains("mac")) {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }

        dialog.dispose()

        onDispose {}
    }
}

@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard Shortcuts") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShortcutRow("K or Space", "Keep photo")
                ShortcutRow("D or Delete", "Discard photo")
                ShortcutRow("U", "Undecide (reset status)")
                ShortcutRow("←/→", "Navigate between photos")
                ShortcutRow("E", "Export kept photos")
                ShortcutRow("H", "Show this help dialog")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ShortcutRow(shortcut: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = shortcut,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var imageWidth by remember { mutableStateOf(0f) }
    var imageHeight by remember { mutableStateOf(0f) }
    var containerWidth by remember { mutableStateOf(0f) }
    var containerHeight by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.first()
                val scrollDelta = change.scrollDelta.y

                // Skip if no scroll delta (prevents issues with trackpad)
                if (scrollDelta == 0f) return@onPointerEvent

                // Calculate new scale based on scroll direction - even slower zoom for trackpad
                val zoomFactor = if (scrollDelta > 0) 0.96f else 1.04f  // Reduced from 0.93f/1.07f
                val newScale = (scale * zoomFactor).coerceIn(0.5f, 8.0f)

                // Get the position of the cursor relative to the container
                val pointerX = change.position.x
                val pointerY = change.position.y

                // Calculate the center of the container
                val centerX = containerWidth / 2f
                val centerY = containerHeight / 2f

                // Calculate the position relative to center
                val relativeX = pointerX - centerX
                val relativeY = pointerY - centerY

                // Calculate new offsets to zoom towards cursor position
                val scaleChange = newScale / scale
                val newOffsetX = offsetX * scaleChange + relativeX * (1 - scaleChange)
                val newOffsetY = offsetY * scaleChange + relativeY * (1 - scaleChange)

                // Calculate proper bounds based on scaled image size
                val scaledImageWidth = imageWidth * newScale
                val scaledImageHeight = imageHeight * newScale

                val maxOffsetX = max(0f, (scaledImageWidth - containerWidth) / 2f)
                val maxOffsetY = max(0f, (scaledImageHeight - containerHeight) / 2f)

                onScaleChange(newScale)
                onOffsetChange(
                    newOffsetX.coerceIn(-maxOffsetX, maxOffsetX),
                    newOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
                )
            }
            .pointerInput(scale, imageWidth, imageHeight, containerWidth, containerHeight) {
                detectDragGestures(
                    onDragStart = { },
                    onDrag = { _, dragAmount ->
                        // Calculate the scaled image dimensions
                        val scaledImageWidth = imageWidth * scale
                        val scaledImageHeight = imageHeight * scale

                        // Only allow panning if the scaled image is larger than the container
                        var newOffsetX = offsetX
                        var newOffsetY = offsetY

                        // Handle horizontal panning
                        if (scaledImageWidth > containerWidth) {
                            val maxOffsetX = (scaledImageWidth - containerWidth) / 2f
                            newOffsetX = (offsetX + dragAmount.x).coerceIn(-maxOffsetX, maxOffsetX)
                        }

                        // Handle vertical panning
                        if (scaledImageHeight > containerHeight) {
                            val maxOffsetY = (scaledImageHeight - containerHeight) / 2f
                            newOffsetY = (offsetY + dragAmount.y).coerceIn(-maxOffsetY, maxOffsetY)
                        }

                        // Only update if there was an actual change
                        if (newOffsetX != offsetX || newOffsetY != offsetY) {
                            onOffsetChange(newOffsetX, newOffsetY)
                        }
                    }
                )
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Update container dimensions
                    containerWidth = coordinates.size.width.toFloat()
                    containerHeight = coordinates.size.height.toFloat()

                    // Calculate the actual displayed image dimensions
                    val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val containerAspectRatio = containerWidth / containerHeight

                    if (bitmapAspectRatio > containerAspectRatio) {
                        // Image is wider - fit to width
                        imageWidth = containerWidth
                        imageHeight = containerWidth / bitmapAspectRatio
                    } else {
                        // Image is taller - fit to height
                        imageHeight = containerHeight
                        imageWidth = containerHeight * bitmapAspectRatio
                    }
                },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun CachedProjectsScreen(
    cachedProjects: List<CachedProject>,
    isLoading: Boolean,
    onProjectSelected: (CachedProject) -> Unit,
    onProjectDeleted: (CachedProject) -> Unit,
    onOpenNewFolder: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "Foto-Filter",
            style = MaterialTheme.typography.headlineLarge
        )

        Text(
            "Recent Projects",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(cachedProjects) { project ->
                    CachedProjectCard(
                        project = project,
                        onProjectSelected = { onProjectSelected(project) },
                        onProjectDeleted = { onProjectDeleted(project) }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onOpenNewFolder,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Open New Folder", style = MaterialTheme.typography.titleMedium)
            }

            if (cachedProjects.isNotEmpty()) {
                Text(
                    "or select a recent project above",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CachedProjectCard(
    project: CachedProject,
    onProjectSelected: () -> Unit,
    onProjectDeleted: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProjectSelected() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = project.folderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = project.folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(
                        count = project.keptPhotos,
                        label = "Kept",
                        color = Color(0xFF2E7D32)
                    )
                    StatusChip(
                        count = project.discardedPhotos,
                        label = "Discarded",
                        color = Color(0xFFC62828)
                    )
                    StatusChip(
                        count = project.remainingPhotos,
                        label = "Remaining",
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "Last accessed: ${formatLastAccessed(project.lastAccessed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onProjectDeleted,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete Project"
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    count: Int,
    label: String,
    color: Color
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}

private fun formatLastAccessed(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMillis = now - timestamp
    val diffDays = diffMillis / (24 * 60 * 60 * 1000)

    return when {
        diffDays == 0L -> "Today"
        diffDays == 1L -> "Yesterday"
        diffDays < 7 -> "$diffDays days ago"
        diffDays < 30 -> "${diffDays / 7} weeks ago"
        else -> "${diffDays / 30} months ago"
    }
}
