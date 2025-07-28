package viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import models.PhotoStatus
import models.PhotoLibrary
import services.FileService
import services.SelectionPersistenceService
import services.CachedProject
import utils.ImageUtils
import utils.Logger

// Simplified ViewModel that directly uses FileService instead of repository pattern
class FotoFilterViewModel {
    private val _state = MutableStateFlow(PhotoLibrary())
    val state: StateFlow<PhotoLibrary> = _state.asStateFlow()

    // Import progress state
    private val _importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importProgress: StateFlow<Pair<Int, Int>?> = _importProgress.asStateFlow()

    // Cache loading state - separate from initial loading
    private val _isCacheLoading = MutableStateFlow(false)
    val isCacheLoading: StateFlow<Boolean> = _isCacheLoading.asStateFlow()

    // Cached projects state
    private val _cachedProjects = MutableStateFlow<List<CachedProject>>(emptyList())
    val cachedProjects: StateFlow<List<CachedProject>> = _cachedProjects.asStateFlow()

    private val _isLoadingProjects = MutableStateFlow(false)
    val isLoadingProjects: StateFlow<Boolean> = _isLoadingProjects.asStateFlow()

    private val fileService = FileService()
    private val selectionPersistenceService = SelectionPersistenceService()
    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    suspend fun loadPhotos(folderPath: String) {
        _state.value = _state.value.copy(isLoading = true)

        try {
            val photos = fileService.scanFolder(folderPath)
            _state.value = _state.value.copy(
                photos = photos,
                isLoading = false,
                folderPath = folderPath,
                selectedIndex = 0
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /**
     * Import folder: Generate disk cache for all photos
     */
    fun importFolder(folderPath: String) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                _importProgress.value = Pair(0, 0)

                // First scan the folder
                val photos = fileService.scanFolder(folderPath)
                _state.value = _state.value.copy(
                    photos = photos,
                    folderPath = folderPath,
                    selectedIndex = 0
                )

                // Then generate thumbnails and previews on disk
                ImageUtils.importFolder(photos) { current, total ->
                    _importProgress.value = Pair(current, total)
                }

                _importProgress.value = null
                _state.value = _state.value.copy(isLoading = false)
            } catch (e: Exception) {
                _importProgress.value = null
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Load photos with full preloading and restore saved selections
     */
    suspend fun loadPhotosWithPreload(folderPath: String) {
        _state.value = _state.value.copy(isLoading = true)
        _isCacheLoading.value = true
        _importProgress.value = Pair(0, 0)

        try {
            // First scan the folder
            val photos = fileService.scanFolder(folderPath)

            // Restore saved selections
            val photosWithSelections = selectionPersistenceService.loadSelections(folderPath, photos)

            // Update state with photos immediately so UI can show them
            _state.value = _state.value.copy(
                photos = photosWithSelections,
                folderPath = folderPath,
                selectedIndex = 0,
                isLoading = false // Photos are loaded, now just caching
            )

            // Preload ALL images with progress tracking in background
            ImageUtils.importFolder(photosWithSelections) { current, total ->
                _importProgress.value = Pair(current, total)
            }

            // Clear both progress and cache loading states
            _importProgress.value = null
            _isCacheLoading.value = false
        } catch (e: Exception) {
            _importProgress.value = null
            _isCacheLoading.value = false
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    fun onPhotoSelected(index: Int) {
        val currentState = _state.value
        if (index != currentState.selectedIndex && index in 0 until currentState.photos.size) {
            _state.value = currentState.copy(selectedIndex = index)

            // Trigger preloading of adjacent images for smooth navigation
            ImageUtils.preloadImagesAround(currentState.photos, index)
        }
    }

    fun handleKeyPress(key: String) {
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

    fun exportKeptPhotos(destinationPath: String) {
        _state.value = _state.value.copy(isExporting = true, exportPath = destinationPath)

        viewModelScope.launch {
            try {
                val photosToExport = _state.value.photos.filter { it.status == PhotoStatus.KEEP }

                if (photosToExport.isNotEmpty()) {
                    fileService.exportPhotos(photosToExport, destinationPath)
                    _state.value = _state.value.copy(isExporting = false, exportCompleted = true)

                    // Clean up cache and selections after successful export (only if folderPath is not null)
                    ImageUtils.cleanupExportedPhotos(_state.value.photos)
                    _state.value.folderPath?.let { folderPath ->
                        selectionPersistenceService.deleteSelections(folderPath)
                    }

                    delay(3000)
                    resetToStartScreen()
                } else {
                    _state.value = _state.value.copy(isExporting = false)
                }
            } catch (e: Exception) {
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
        _state.value = PhotoLibrary()

        // Refresh the cached projects list so UI shows updated state
        loadCachedProjects()
    }

    /**
     * Load list of cached projects
     */
    fun loadCachedProjects() {
        viewModelScope.launch {
            _isLoadingProjects.value = true
            try {
                Logger.viewModel.info { "Starting to load cached projects..." }
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

    /**
     * Delete a cached project
     */
    fun deleteCachedProject(project: CachedProject) {
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

    /**
     * Load a cached project
     */
    fun loadCachedProject(project: CachedProject) {
        viewModelScope.launch {
            try {
                // Use the existing preload method which will restore selections
                loadPhotosWithPreload(project.folderPath)
            } catch (e: Exception) {
                Logger.viewModel.error(e) { "Error loading cached project: ${project.folderName}" }
            }
        }
    }
}
