package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import models.Photo
import models.PhotoStatus
import viewmodels.FotoFilterViewModel
import repositories.PhotoRepository
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGridScreen(
    viewModel: FotoFilterViewModel = remember { FotoFilterViewModel(PhotoRepository()) }
) {
    val state by viewModel.state.collectAsState()
    var showFolderDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showShortcutsDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxSize()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.K, Key.Spacebar -> viewModel.handleKeyPress("space")
                        Key.D, Key.Delete -> viewModel.handleKeyPress("delete")
                        Key.U -> viewModel.handleKeyPress("u")
                        Key.E -> {
                            if (state.keptPhotos > 0) showExportDialog = true
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
            .focusRequester(focusRequester)
            .clickable { focusRequester.requestFocus() }
    ) {
        // Minimal top bar that's smaller when no photos are loaded
        if (state.photos.isEmpty() && !state.isLoading) {
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
        } else {
            // Standard toolbar when photos are loaded
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

                    FilledTonalButton(
                        onClick = { showExportDialog = true },
                        enabled = state.keptPhotos > 0,
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
                        Text("Loading photos...")
                    }
                }
            }

            state.photos.isEmpty() -> {
                // Empty state with instructions
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
                        Box(modifier = Modifier.weight(0.6f).fillMaxHeight().padding(end = 8.dp)) {
                            state.selectedPhoto?.let { photo ->
                                LargePhotoPreview(photo = photo)
                            }
                        }

                        // Grid of thumbnails (right side)
                        Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                            PhotoGrid(
                                photos = state.photos,
                                selectedIndex = state.selectedIndex,
                                onPhotoClick = { index ->
                                    viewModel.onPhotoSelected(index)
                                }
                            )
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
                        viewModel.loadPhotos(folderPath)
                    }
                }
            },
            onDismiss = { showFolderDialog = false }
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
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showShortcutsDialog) {
        ShortcutsDialog(onDismiss = { showShortcutsDialog = false })
    }

    // Keyboard handling
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
fun LargePhotoPreview(photo: Photo) {
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
        // Placeholder for image loading
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Image Preview",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
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
            else -> {}
        }

        // Filename overlay at bottom
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = photo.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@Composable
fun PhotoGrid(
    photos: List<Photo>,
    selectedIndex: Int,
    onPhotoClick: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(photos) { index, photo ->
            PhotoThumbnail(
                photo = photo,
                isSelected = index == selectedIndex,
                onClick = { onPhotoClick(index) }
            )
        }
    }
}

@Composable
fun PhotoThumbnail(
    photo: Photo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
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
                width = if (isSelected) 2.dp else 1.dp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    photo.status == PhotoStatus.KEEP -> Color(0xFF2E7D32)
                    photo.status == PhotoStatus.DISCARD -> Color(0xFFC62828)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                shape = MaterialTheme.shapes.small
            )
            .clickable { onClick() }
    ) {
        // Placeholder for thumbnail image
        Box(
            modifier = Modifier.fillMaxSize().background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Thumbnail",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
        }

        // Status icon in corner
        when (photo.status) {
            PhotoStatus.KEEP -> {
                Surface(
                    modifier = Modifier.size(24.dp).align(Alignment.TopEnd).padding(4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFF2E7D32)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Kept",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            PhotoStatus.DISCARD -> {
                Surface(
                    modifier = Modifier.size(24.dp).align(Alignment.TopEnd).padding(4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFFC62828)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Discarded",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            else -> {}
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
