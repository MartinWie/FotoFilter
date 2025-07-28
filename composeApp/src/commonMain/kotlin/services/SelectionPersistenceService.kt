package services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Photo
import models.PhotoStatus

/**
 * Platform-agnostic interface for selection persistence
 */
expect class SelectionPersistenceService() {
    suspend fun saveSelections(folderPath: String, photos: List<Photo>)
    suspend fun loadSelections(folderPath: String, photos: List<Photo>): List<Photo>
    suspend fun deleteSelections(folderPath: String)
    suspend fun cleanupOldSelections(maxAgeDays: Int = 30)
    fun getSelectionStorageMB(): Double
}
