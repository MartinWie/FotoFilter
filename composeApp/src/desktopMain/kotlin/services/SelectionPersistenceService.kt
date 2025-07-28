package services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Photo
import models.PhotoStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import utils.Logger

@Serializable
data class PhotoSelection(
    val photoPath: String,
    val status: String,
    val lastModified: Long,
    val fileSize: Long
)

@Serializable
data class FolderSelections(
    val folderPath: String,
    val lastAccessed: Long,
    val selections: List<PhotoSelection>
)

/**
 * Desktop implementation of selection persistence using JSON files
 */
actual class SelectionPersistenceService {
    // Store selections next to the cache in the same .fotofilter directory
    private val persistenceDir = File(System.getProperty("user.home"), ".fotofilter/cache")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        persistenceDir.mkdirs()
    }

    actual suspend fun saveSelections(folderPath: String, photos: List<Photo>) = withContext(Dispatchers.IO) {
        try {
            val selections = photos
                .filter { it.status != PhotoStatus.UNDECIDED }
                .map { photo ->
                    // Always use RAW file for consistency in file metadata
                    val file = File(photo.rawPath)
                    PhotoSelection(
                        photoPath = photo.rawPath,
                        status = photo.status.name,
                        lastModified = file.lastModified(),
                        fileSize = file.length()
                    )
                }

            val folderSelections = FolderSelections(
                folderPath = folderPath,
                lastAccessed = System.currentTimeMillis(),
                selections = selections
            )

            val selectionFile = getSelectionFile(folderPath)
            selectionFile.parentFile?.mkdirs() // Ensure directory exists
            val jsonString = json.encodeToString(folderSelections)
            selectionFile.writeText(jsonString)

            Logger.persistenceService.info { "Saved ${selections.size} photo selections for folder: $folderPath" }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error saving selections for folder: $folderPath" }
        }
    }

    actual suspend fun loadSelections(folderPath: String, photos: List<Photo>): List<Photo> = withContext(Dispatchers.IO) {
        try {
            val selectionFile = getSelectionFile(folderPath)
            Logger.persistenceService.debug { "Looking for selection file: ${selectionFile.absolutePath}" }

            if (!selectionFile.exists()) {
                Logger.persistenceService.debug { "No saved selections found for folder: $folderPath" }
                return@withContext photos
            }

            val jsonString = selectionFile.readText()
            val folderSelections = json.decodeFromString<FolderSelections>(jsonString)
            Logger.persistenceService.debug { "Parsed ${folderSelections.selections.size} selections from file" }

            // Create a map of saved selections by photo path
            val selectionMap = folderSelections.selections.associateBy { it.photoPath }

            // Apply saved selections to photos, but verify file hasn't changed
            var appliedCount = 0
            val updatedPhotos = photos.map { photo ->
                val savedSelection = selectionMap[photo.rawPath]

                if (savedSelection != null) {
                    val file = File(photo.rawPath)

                    // Only apply if file hasn't been modified since selection was saved
                    if (file.lastModified() == savedSelection.lastModified &&
                        file.length() == savedSelection.fileSize) {

                        val status = try {
                            PhotoStatus.valueOf(savedSelection.status)
                        } catch (e: Exception) {
                            PhotoStatus.UNDECIDED
                        }

                        appliedCount++
                        photo.copy(status = status)
                    } else {
                        // File has changed, reset to undecided
                        Logger.persistenceService.debug { "File ${photo.fileName} has changed since selection was saved - resetting to UNDECIDED" }
                        photo.copy(status = PhotoStatus.UNDECIDED)
                    }
                } else {
                    photo
                }
            }

            Logger.persistenceService.info { "Applied $appliedCount selections out of ${selectionMap.size} saved selections" }
            updatedPhotos
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error loading selections for folder: $folderPath" }
            photos
        }
    }

    actual suspend fun deleteSelections(folderPath: String) = withContext(Dispatchers.IO) {
        try {
            val selectionFile = getSelectionFile(folderPath)
            if (selectionFile.exists()) {
                selectionFile.delete()
                Logger.persistenceService.info { "Deleted selections for folder: $folderPath" }
            }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error deleting selections for folder: $folderPath" }
        }
    }

    actual suspend fun cleanupOldSelections(maxAgeDays: Int) = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
            var deletedCount = 0

            persistenceDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    try {
                        val jsonString = file.readText()
                        val folderSelections = json.decodeFromString<FolderSelections>(jsonString)

                        if (folderSelections.lastAccessed < cutoffTime) {
                            file.delete()
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        // If we can't read the file, it's probably corrupted, delete it
                        file.delete()
                        deletedCount++
                    }
                }
            }

            if (deletedCount > 0) {
                Logger.persistenceService.info { "Cleaned up $deletedCount old selection files" }
            }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error cleaning up old selections" }
        }
    }

    private fun getSelectionFile(folderPath: String): File {
        // Create a safe filename from the folder path and add .selections.json extension
        val safeName = folderPath
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace(" ", "_")
            .take(100) // Limit length

        return File(persistenceDir, "${safeName}.selections.json")
    }

    actual fun getSelectionStorageMB(): Double {
        val totalBytes = persistenceDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
        return totalBytes / (1024.0 * 1024.0)
    }
}
