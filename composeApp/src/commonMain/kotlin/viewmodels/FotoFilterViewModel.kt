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

// Simplified ViewModel that directly uses FileService instead of repository pattern
class FotoFilterViewModel {
    private val _state = MutableStateFlow(PhotoLibrary())
    val state: StateFlow<PhotoLibrary> = _state.asStateFlow()

    private val fileService = FileService()
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

    fun onPhotoSelected(index: Int) {
        val currentState = _state.value
        if (index != currentState.selectedIndex && index in 0 until currentState.photos.size) {
            _state.value = currentState.copy(selectedIndex = index)
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
        _state.value = PhotoLibrary()
    }
}
