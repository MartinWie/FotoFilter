package services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Photo
import models.PhotoStatus

data class CachedProject(
    val folderPath: String,
    val folderName: String,
    val lastAccessed: Long,
    val totalPhotos: Int,
    val keptPhotos: Int,
    val discardedPhotos: Int,
    val remainingPhotos: Int
)

/**
 * Platform-agnostic interface for selection persistence
 */
expect class SelectionPersistenceService() {
    suspend fun saveSelections(folderPath: String, photos: List<Photo>)
    suspend fun loadSelections(folderPath: String, photos: List<Photo>): List<Photo>
    suspend fun deleteSelections(folderPath: String)
    suspend fun cleanupOldSelections(maxAgeDays: Int = 30)
    fun getSelectionStorageMB(): Double
    suspend fun listCachedProjects(): List<CachedProject>
    suspend fun deleteCachedProject(folderPath: String)
    suspend fun validateAndRepairCache()
}
