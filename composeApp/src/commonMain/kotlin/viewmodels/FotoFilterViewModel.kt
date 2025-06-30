package viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import models.PhotoStatus
import models.PhotoLibrary
import repositories.PhotoRepository
import utils.ImageProcessing

class FotoFilterViewModel {
    private val _state = MutableStateFlow(PhotoLibrary())
    val state: StateFlow<PhotoLibrary> = _state.asStateFlow()

    private val photoRepository: PhotoRepository
    private val imageProcessor: ImageProcessing
    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    constructor(photoRepository: PhotoRepository, imageProcessor: ImageProcessing) {
        this.photoRepository = photoRepository
        this.imageProcessor = imageProcessor
    }

    suspend fun loadPhotos(folderPath: String) {
        _state.value = _state.value.copy(isLoading = true)

        try {
            val photos = photoRepository.scanFolder(folderPath)
            _state.value = _state.value.copy(
                photos = photos,
                isLoading = false,
                folderPath = folderPath,
                selectedIndex = 0
            )

            // Preload images using memory-efficient loading strategy
            imageProcessor.preloadImages(photos)

            // Ensure the initial view has properly loaded images
            if (photos.isNotEmpty()) {
                imageProcessor.updateFocusIndex(photos, 0)
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false)
            // Handle error
        }
    }

    fun onPhotoSelected(index: Int) {
        val currentState = _state.value
        if (index == currentState.selectedIndex) return

        _state.value = currentState.copy(selectedIndex = index)

        // Update image loading to focus around the new index
        imageProcessor.updateFocusIndex(currentState.photos, index)
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
            "e" -> exportKeptPhotos()
        }
    }

    private fun markCurrentAs(status: PhotoStatus) {
        val currentState = _state.value
        val currentPhoto = currentState.selectedPhoto ?: return

        val updatedPhotos = currentState.photos.toMutableList()
        val index = currentState.selectedIndex
        updatedPhotos[index] = currentPhoto.copy(status = status)

        _state.value = currentState.copy(photos = updatedPhotos)

        // Auto-advance to next photo after making a decision
        nextPhoto()
    }

    private fun nextPhoto() {
        val currentState = _state.value
        if (currentState.selectedIndex < currentState.photos.size - 1) {
            val newIndex = currentState.selectedIndex + 1
            _state.value = currentState.copy(
                selectedIndex = newIndex
            )

            // Update image preloading to focus around the new index
            imageProcessor.updateFocusIndex(currentState.photos, newIndex)
        }
    }

    private fun previousPhoto() {
        val currentState = _state.value
        if (currentState.selectedIndex > 0) {
            val newIndex = currentState.selectedIndex - 1
            _state.value = currentState.copy(
                selectedIndex = newIndex
            )

            // Update image preloading to focus around the new index
            imageProcessor.updateFocusIndex(currentState.photos, newIndex)
        }
    }

    fun exportKeptPhotos(destinationPath: String? = null) {
        if (destinationPath == null) return

        viewModelScope.launch {
            val photosToExport = _state.value.photos.filter {
                it.status == PhotoStatus.KEEP
            }

            if (photosToExport.isNotEmpty()) {
                photoRepository.exportPhotos(photosToExport, destinationPath)
            }
        }
    }
}
