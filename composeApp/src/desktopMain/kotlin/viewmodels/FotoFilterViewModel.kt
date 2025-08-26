package viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import models.PhotoLibrary
import models.PhotoStatus
import services.CachedProject
import services.FileService
import services.SelectionPersistenceService
import utils.ImageUtils
import utils.Logger

actual class FotoFilterViewModel actual constructor() {
    private val _state = MutableStateFlow(PhotoLibrary())
    actual val state: StateFlow<PhotoLibrary> = _state.asStateFlow()

    // Import progress state
    private val _importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    actual val importProgress: StateFlow<Pair<Int, Int>?> = _importProgress.asStateFlow()

    // Cache loading state - separate from initial loading
    private val _isCacheLoading = MutableStateFlow(false)
    actual val isCacheLoading: StateFlow<Boolean> = _isCacheLoading.asStateFlow()

    // Cached projects state
    private val _cachedProjects = MutableStateFlow<List<CachedProject>>(emptyList())
    actual val cachedProjects: StateFlow<List<CachedProject>> = _cachedProjects.asStateFlow()

    private val _isLoadingProjects = MutableStateFlow(false)
    actual val isLoadingProjects: StateFlow<Boolean> = _isLoadingProjects.asStateFlow()

    private val fileService = FileService()
    private val selectionPersistenceService = SelectionPersistenceService()
    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    /**
     * Load photos with full preloading and restore saved selections
     */
    actual suspend fun loadPhotosWithPreload(folderPath: String) {
        _state.value = _state.value.copy(isLoading = true)
        _isCacheLoading.value = true
        _importProgress.value = Pair(0, 0)

        try {
            // Scan folder (recursive) and capture skipped files
            val (photosList, skipped) = fileService.scanFolderWithSkipped(folderPath)

            // Restore saved selections
            val photosWithSelections = selectionPersistenceService.loadSelections(folderPath, photosList)

            _state.value = _state.value.copy(
                photos = photosWithSelections,
                folderPath = folderPath,
                selectedIndex = 0,
                isLoading = false,
                skippedFiles = skipped
            )

            ImageUtils.importFolder(photosWithSelections, folderPath) { current: Int, total: Int ->
                _importProgress.value = Pair(current, total)
            }

            _importProgress.value = null
            _isCacheLoading.value = false
        } catch (e: Exception) {
            Logger.viewModel.error(e) { "Error loading photos with preload" }
            _importProgress.value = null
            _isCacheLoading.value = false
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    actual fun handleKeyPress(key: String) {
        val currentState = _state.value
        if (currentState.photos.isEmpty()) return

        when (key) {
            "k", "space" -> markCurrentAs(PhotoStatus.KEEP)
            "d", "delete" -> markCurrentAs(PhotoStatus.DISCARD)
            "u" -> markCurrentAs(PhotoStatus.UNDECIDED)
            "ArrowRight" -> nextPhoto()
            "ArrowLeft" -> previousPhoto()
        }
    }

    private fun markCurrentAs(status: PhotoStatus) {
        val currentState = _state.value
        val currentPhoto = currentState.selectedPhoto ?: return

        val updatedPhotos = currentState.photos.toMutableList()
        updatedPhotos[currentState.selectedIndex] = currentPhoto.copy(status = status)

        _state.value = currentState.copy(photos = updatedPhotos)

        // Auto-save selections after each change (only if folderPath is not null)
        viewModelScope.launch {
            currentState.folderPath?.let { folderPath ->
                selectionPersistenceService.saveSelections(folderPath, updatedPhotos)
            }
        }

        nextPhoto() // Auto-advance
    }

    private fun nextPhoto() {
        val currentState = _state.value
        if (currentState.selectedIndex < currentState.photos.size - 1) {
            _state.value = currentState.copy(selectedIndex = currentState.selectedIndex + 1)
        }
    }

    private fun previousPhoto() {
        val currentState = _state.value
        if (currentState.selectedIndex > 0) {
            _state.value = currentState.copy(selectedIndex = currentState.selectedIndex - 1)
        }
    }

    actual fun exportKeptPhotos(destinationPath: String) {
        _state.value = _state.value.copy(isExporting = true, exportPath = destinationPath)

        viewModelScope.launch {
            try {
                val photosToExport = _state.value.photos.filter { it.status == PhotoStatus.KEEP }

                if (photosToExport.isNotEmpty()) {
                    fileService.exportPhotos(photosToExport, destinationPath)
                    _state.value = _state.value.copy(isExporting = false, exportCompleted = true)

                    // Clean up cache and selections after successful export (only if folderPath is not null)
                    _state.value.folderPath?.let { folderPath ->
                        ImageUtils.cleanupExportedPhotos(_state.value.photos, folderPath)
                        selectionPersistenceService.deleteSelections(folderPath)
                    }

                    delay(3000)
                    resetToStartScreen()
                } else {
                    _state.value = _state.value.copy(isExporting = false)
                }
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Export failed" }
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    private fun resetToStartScreen() {
        // Clean up old selection files periodically
        viewModelScope.launch {
            selectionPersistenceService.cleanupOldSelections()
        }

        // Clear state and refresh cached projects list to reflect changes
        _state.value = PhotoLibrary() // clears skippedFiles automatically

        // Refresh the cached projects list so UI shows updated state
        loadCachedProjects()
    }

    actual fun loadCachedProjects() {
        viewModelScope.launch {
            _isLoadingProjects.value = true
            try {
                Logger.viewModel.info { "Starting to load cached projects..." }

                // Validate and repair cache consistency
                selectionPersistenceService.validateAndRepairCache()

                val projects = selectionPersistenceService.listCachedProjects()
                Logger.viewModel.info { "Retrieved ${projects.size} cached projects from service" }
                _cachedProjects.value = projects
                Logger.viewModel.info { "Updated cachedProjects state with ${projects.size} projects" }
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Error loading cached projects" }
                _cachedProjects.value = emptyList()
            } finally {
                _isLoadingProjects.value = false
            }
        }
    }

    actual fun deleteCachedProject(project: CachedProject) {
        viewModelScope.launch {
            try {
                selectionPersistenceService.deleteCachedProject(project.folderPath)
                // Refresh the list
                loadCachedProjects()
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Error deleting cached project: ${project.folderName}" }
            }
        }
    }

    actual fun loadCachedProject(project: CachedProject) {
        viewModelScope.launch {
            try {
                loadPhotosWithPreload(project.folderPath)
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Error loading cached project: ${project.folderName}" }
            }
        }
    }

    actual fun deleteDiscardedPhotos() {
        val currentState = _state.value
        if (currentState.photos.isEmpty()) return

        viewModelScope.launch {
            try {
                val discarded = currentState.photos.filter { it.status == PhotoStatus.DISCARD }
                if (discarded.isEmpty()) return@launch

                // Start deletion overlay
                _state.value = currentState.copy(
                    isDeleting = true,
                    deleteCompleted = false,
                    lastDeletedCount = discarded.size
                )

                // Delete files from disk
                fileService.deleteDiscardedPhotos(discarded)

                // Clean up caches for those photos
                currentState.folderPath?.let { folderPath ->
                    ImageUtils.cleanupExportedPhotos(discarded, folderPath)
                    // Remove saved selections entirely since project ends after deletion
                    selectionPersistenceService.deleteSelections(folderPath)
                }

                // Show completion state
                _state.value = _state.value.copy(
                    isDeleting = false,
                    deleteCompleted = true
                )

                // Brief delay to show success then reset
                delay(3000)
                resetToStartScreen()
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Error deleting discarded photos" }
                // Ensure overlay dismissed on error
                _state.value = _state.value.copy(isDeleting = false, deleteCompleted = false)
            }
        }
    }

    actual fun onPhotoSelected(index: Int) {
        val currentState = _state.value
        if (index != currentState.selectedIndex && index in 0 until currentState.photos.size) {
            _state.value = currentState.copy(selectedIndex = index)
            currentState.folderPath?.let { folderPath ->
                ImageUtils.preloadImagesAround(currentState.photos, folderPath, index)
            }
        }
    }
}
