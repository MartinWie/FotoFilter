package viewmodels

import kotlinx.coroutines.flow.StateFlow
import models.PhotoLibrary
import services.CachedProject

// Expect declaration. Actual implementation lives in desktopMain.
expect class FotoFilterViewModel() {
    val state: StateFlow<PhotoLibrary>
    val importProgress: StateFlow<Pair<Int, Int>?>
    val isCacheLoading: StateFlow<Boolean>
    val cachedProjects: StateFlow<List<CachedProject>>
    val isLoadingProjects: StateFlow<Boolean>

    suspend fun loadPhotosWithPreload(folderPath: String)
    fun handleKeyPress(key: String)
    fun exportKeptPhotos(destinationPath: String)
    fun loadCachedProjects()
    fun loadCachedProject(project: CachedProject)
    fun deleteCachedProject(project: CachedProject)
    fun deleteDiscardedPhotos()
    fun onPhotoSelected(index: Int)
}
