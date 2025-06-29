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

class FotoFilterViewModel {
    private val _state = MutableStateFlow(PhotoLibrary())
    val state: StateFlow<PhotoLibrary> = _state.asStateFlow()

    private val photoRepository: PhotoRepository
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    constructor(photoRepository: PhotoRepository) {
        this.photoRepository = photoRepository
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
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false)
            // Handle error
        }
    }

    fun onPhotoSelected(index: Int) {
        _state.value = _state.value.copy(selectedIndex = index)
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
            _state.value = currentState.copy(
                selectedIndex = currentState.selectedIndex + 1
            )
        }
    }

    private fun previousPhoto() {
        val currentState = _state.value
        if (currentState.selectedIndex > 0) {
            _state.value = currentState.copy(
                selectedIndex = currentState.selectedIndex - 1
            )
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
