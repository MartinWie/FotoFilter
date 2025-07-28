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
    val selections: List<PhotoSelection>,
    val totalPhotos: Int, // Add total photo count
    val thumbnailHashes: List<String> = emptyList(), // Track thumbnail hashes for cleanup
    val cacheVersion: Int = 2 // Add version for future migrations
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

        // Debug: Log cache directory info on startup
        Logger.persistenceService.info { "Cache directory initialized at: ${persistenceDir.absolutePath}" }
        Logger.persistenceService.info { "Directory exists: ${persistenceDir.exists()}" }
        Logger.persistenceService.info { "Directory is writable: ${persistenceDir.canWrite()}" }
    }

    private fun createTestCacheFileIfNeeded() {
        try {
            val existingFiles = persistenceDir.listFiles()?.filter { it.name.endsWith(".selections.json") }
            if (existingFiles.isNullOrEmpty()) {
                Logger.persistenceService.info { "No existing cache files found, creating test cache file for debugging" }

                // Create a test cache file for debugging with an existing folder path
                val userHome = System.getProperty("user.home")
                val testFolderPath = "$userHome/Desktop" // Use Desktop which likely exists

                val testProject = FolderSelections(
                    folderPath = testFolderPath,
                    lastAccessed = System.currentTimeMillis(),
                    selections = listOf(
                        PhotoSelection(
                            photoPath = "$testFolderPath/test1.cr3",
                            status = PhotoStatus.KEEP.name,
                            lastModified = System.currentTimeMillis(),
                            fileSize = 12345L
                        ),
                        PhotoSelection(
                            photoPath = "$testFolderPath/test2.cr3",
                            status = PhotoStatus.DISCARD.name,
                            lastModified = System.currentTimeMillis(),
                            fileSize = 67890L
                        ),
                        PhotoSelection(
                            photoPath = "$testFolderPath/test3.cr3",
                            status = PhotoStatus.UNDECIDED.name,
                            lastModified = System.currentTimeMillis(),
                            fileSize = 54321L
                        )
                    ),
                    totalPhotos = 3 // Total photos in the test project
                )

                val testFile = File(persistenceDir, "test_project.selections.json")
                val jsonString = json.encodeToString(testProject)
                testFile.writeText(jsonString)
                Logger.persistenceService.info { "Created test cache file: ${testFile.absolutePath}" }
            } else {
                Logger.persistenceService.info { "Found ${existingFiles.size} existing cache files" }
            }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error creating test cache file" }
        }
    }

    actual suspend fun saveSelections(folderPath: String, photos: List<Photo>) = withContext(Dispatchers.IO) {
        try {
            // Save ALL photos with their current status, not just decided ones
            val selections = photos.map { photo ->
                // Always use RAW file for consistency in file metadata
                val file = File(photo.rawPath)
                PhotoSelection(
                    photoPath = photo.rawPath,
                    status = photo.status.name,
                    lastModified = file.lastModified(),
                    fileSize = file.length()
                )
            }

            // Preserve existing thumbnail hashes and only add new ones
            val existingThumbnailHashes = try {
                val selectionFile = getSelectionFile(folderPath)
                if (selectionFile.exists()) {
                    val jsonString = selectionFile.readText()
                    val existingSelections = json.decodeFromString<FolderSelections>(jsonString)
                    existingSelections.thumbnailHashes
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Logger.persistenceService.debug(e) { "Could not load existing thumbnail hashes, will regenerate all" }
                emptyList()
            }

            // Generate thumbnail hashes for cleanup tracking - use same logic as ThumbnailCacheService
            val newThumbnailHashes = if (existingThumbnailHashes.isNotEmpty()) {
                // If we have existing hashes, preserve them (don't regenerate)
                existingThumbnailHashes
            } else {
                // Only generate new hashes if we don't have any (first time saving this project)
                photos.map { photo ->
                    // Use same path logic as ThumbnailCacheService: prefer JPEG, fallback to RAW
                    val path = photo.jpegPath ?: photo.rawPath
                    generateFileHash(path, File(path))
                }
            }

            val folderSelections = FolderSelections(
                folderPath = folderPath,
                lastAccessed = System.currentTimeMillis(),
                selections = selections,
                totalPhotos = photos.size,
                thumbnailHashes = newThumbnailHashes
            )

            val selectionFile = getSelectionFile(folderPath)
            selectionFile.parentFile?.mkdirs() // Ensure directory exists
            val jsonString = json.encodeToString(folderSelections)
            selectionFile.writeText(jsonString)

            Logger.persistenceService.info { "Saved ${photos.size} photo selections for folder: $folderPath (preserved ${newThumbnailHashes.size} thumbnail hashes)" }
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

            // Create a map of saved selections to photo paths
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
            val thumbnailCacheDir = File(System.getProperty("user.home"), ".fotofilter/thumbnails")

            persistenceDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".selections.json")) {
                    try {
                        val jsonString = file.readText()
                        val folderSelections = json.decodeFromString<FolderSelections>(jsonString)

                        if (folderSelections.lastAccessed < cutoffTime) {
                            // Clean up associated thumbnails before deleting selection file
                            folderSelections.thumbnailHashes.forEach { hash ->
                                val thumbnailFile = File(thumbnailCacheDir, "thumb_${hash}.jpg")
                                val previewFile = File(thumbnailCacheDir, "preview_${hash}.jpg")

                                thumbnailFile.takeIf { it.exists() }?.delete()
                                previewFile.takeIf { it.exists() }?.delete()
                            }

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
                Logger.persistenceService.info { "Cleaned up $deletedCount old selection files and their thumbnails" }
            }

            // Also clean up orphaned thumbnails that don't belong to any project
            cleanupOrphanedThumbnails()

        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error cleaning up old selections" }
        }
    }

    /**
     * Clean up orphaned thumbnails that don't belong to any cached project
     */
    private suspend fun cleanupOrphanedThumbnails() = withContext(Dispatchers.IO) {
        try {
            val thumbnailCacheDir = File(System.getProperty("user.home"), ".fotofilter/thumbnails")
            if (!thumbnailCacheDir.exists()) return@withContext

            // Collect all valid project folder names from cached projects
            val validProjectFolders = mutableSetOf<String>()

            persistenceDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".selections.json")) {
                    try {
                        val jsonString = file.readText()
                        val folderSelections = json.decodeFromString<FolderSelections>(jsonString)

                        // Generate the same safe folder name that ThumbnailCacheService uses
                        val safeName = folderSelections.folderPath
                            .replace("/", "_")
                            .replace("\\", "_")
                            .replace(":", "_")
                            .replace(" ", "_")
                            .take(100)

                        validProjectFolders.add(safeName)
                        Logger.persistenceService.debug { "Added valid project folder: $safeName for project: ${folderSelections.folderPath}" }
                    } catch (e: Exception) {
                        Logger.persistenceService.debug(e) { "Error reading project file for orphan cleanup: ${file.name}" }
                    }
                }
            }

            Logger.persistenceService.debug { "Total valid project folders: ${validProjectFolders.size}" }

            // Clean up orphaned project folders and any remaining flat files
            var orphanedCount = 0
            thumbnailCacheDir.listFiles()?.forEach { fileOrFolder ->
                when {
                    fileOrFolder.isDirectory -> {
                        // This is a project folder - check if it's valid
                        if (fileOrFolder.name !in validProjectFolders) {
                            Logger.persistenceService.debug { "Deleting orphaned project folder: ${fileOrFolder.name}" }
                            val deletedFiles = fileOrFolder.listFiles()?.size ?: 0
                            fileOrFolder.deleteRecursively()
                            orphanedCount += deletedFiles
                        }
                    }
                    fileOrFolder.isFile && (fileOrFolder.name.startsWith("thumb_") || fileOrFolder.name.startsWith("preview_")) -> {
                        // This is an old flat file - clean it up since we now use project folders
                        Logger.persistenceService.debug { "Deleting legacy flat thumbnail file: ${fileOrFolder.name}" }
                        fileOrFolder.delete()
                        orphanedCount++
                    }
                }
            }

            if (orphanedCount > 0) {
                Logger.persistenceService.info { "Cleaned up $orphanedCount orphaned thumbnail files and folders" }
            } else {
                Logger.persistenceService.debug { "No orphaned thumbnails found" }
            }

        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error cleaning up orphaned thumbnails" }
        }
    }

    /**
     * Validate and repair cache consistency between selections and thumbnails
     */
    actual suspend fun validateAndRepairCache() = withContext(Dispatchers.IO) {
        try {
            Logger.persistenceService.info { "Starting cache validation and repair..." }

            var repairedProjects = 0
            val thumbnailCacheDir = File(System.getProperty("user.home"), ".fotofilter/thumbnails")

            persistenceDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".selections.json")) {
                    try {
                        val jsonString = file.readText()
                        val folderSelections = json.decodeFromString<FolderSelections>(jsonString)

                        // Check if this is an old cache format without thumbnail tracking
                        if (folderSelections.thumbnailHashes.isEmpty() && folderSelections.selections.isNotEmpty()) {
                            Logger.persistenceService.info { "Repairing project cache: ${folderSelections.folderPath}" }

                            // We need to regenerate thumbnail hashes, but we only have RAW paths in selections
                            // We need to find the corresponding photos to get the correct JPEG/RAW logic
                            // For repair, we'll use the selection's photoPath as-is since that's what we have
                            val newThumbnailHashes = folderSelections.selections.map { selection ->
                                generateFileHash(selection.photoPath, File(selection.photoPath))
                            }

                            // Update the cache file with thumbnail hashes
                            val updatedFolderSelections = folderSelections.copy(
                                thumbnailHashes = newThumbnailHashes,
                                cacheVersion = 2
                            )

                            val updatedJsonString = json.encodeToString(updatedFolderSelections)
                            file.writeText(updatedJsonString)

                            repairedProjects++
                        }

                    } catch (e: Exception) {
                        Logger.persistenceService.error(e) { "Error repairing cache file: ${file.name}" }
                    }
                }
            }

            if (repairedProjects > 0) {
                Logger.persistenceService.info { "Repaired $repairedProjects project cache files" }
            }

            // Clean up orphaned thumbnails after repair
            cleanupOrphanedThumbnails()

        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error during cache validation and repair" }
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

    actual suspend fun listCachedProjects(): List<CachedProject> = withContext(Dispatchers.IO) {
        val projects = mutableListOf<CachedProject>()

        try {
            Logger.persistenceService.info { "Looking for cached projects in directory: ${persistenceDir.absolutePath}" }
            Logger.persistenceService.info { "Directory exists: ${persistenceDir.exists()}, is directory: ${persistenceDir.isDirectory}" }

            val files = persistenceDir.listFiles()
            Logger.persistenceService.info { "Found ${files?.size ?: 0} files in cache directory" }

            files?.forEach { file ->
                Logger.persistenceService.info { "Checking file: ${file.name}, isFile: ${file.isFile}, ends with .selections.json: ${file.name.endsWith(".selections.json")}" }

                if (file.isFile && file.name.endsWith(".selections.json")) {
                    try {
                        val jsonString = file.readText()
                        val folderSelections = json.decodeFromString<FolderSelections>(jsonString)
                        Logger.persistenceService.info { "Parsed folder selection for: ${folderSelections.folderPath}" }

                        // Verify folder still exists
                        val folder = File(folderSelections.folderPath)
                        Logger.persistenceService.info { "Folder exists: ${folder.exists()}, is directory: ${folder.isDirectory}" }

                        if (folder.exists() && folder.isDirectory) {
                            val cachedProject = CachedProject(
                                folderPath = folderSelections.folderPath,
                                folderName = folder.name,
                                lastAccessed = folderSelections.lastAccessed,
                                totalPhotos = folderSelections.selections.size,
                                keptPhotos = folderSelections.selections.count { it.status == PhotoStatus.KEEP.name },
                                discardedPhotos = folderSelections.selections.count { it.status == PhotoStatus.DISCARD.name },
                                remainingPhotos = folderSelections.selections.count { it.status == PhotoStatus.UNDECIDED.name }
                            )
                            projects.add(cachedProject)
                            Logger.persistenceService.info { "Added cached project: ${cachedProject.folderName}" }
                        } else {
                            // Folder no longer exists, delete the cache file
                            file.delete()
                            Logger.persistenceService.info { "Deleted cache for non-existent folder: ${folderSelections.folderPath}" }
                        }
                    } catch (e: Exception) {
                        Logger.persistenceService.error(e) { "Error reading cached project from file: ${file.name}" }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error listing cached projects" }
        }

        Logger.persistenceService.info { "Found ${projects.size} cached projects total" }
        // Sort by last accessed (most recent first)
        return@withContext projects.sortedByDescending { it.lastAccessed }
    }

    private fun generateFileHash(path: String, file: File): String {
        val input = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
        val digest = java.security.MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    actual suspend fun deleteCachedProject(folderPath: String) = withContext(Dispatchers.IO) {
        try {
            val selectionFile = getSelectionFile(folderPath)
            if (selectionFile.exists()) {
                // Read the cached project data to get thumbnail hashes for cleanup
                try {
                    val jsonString = selectionFile.readText()
                    val folderSelections = json.decodeFromString<FolderSelections>(jsonString)

                    // Clean up associated thumbnails using the thumbnail cache service
                    val thumbnailCacheDir = File(System.getProperty("user.home"), ".fotofilter/thumbnails")
                    folderSelections.thumbnailHashes.forEach { hash ->
                        val thumbnailFile = File(thumbnailCacheDir, "thumb_${hash}.jpg")
                        val previewFile = File(thumbnailCacheDir, "preview_${hash}.jpg")

                        if (thumbnailFile.exists()) {
                            thumbnailFile.delete()
                            Logger.persistenceService.debug { "Deleted thumbnail: thumb_${hash}.jpg" }
                        }
                        if (previewFile.exists()) {
                            previewFile.delete()
                            Logger.persistenceService.debug { "Deleted preview: preview_${hash}.jpg" }
                        }
                    }

                    Logger.persistenceService.info { "Cleaned up ${folderSelections.thumbnailHashes.size} thumbnail/preview pairs for project" }
                } catch (e: Exception) {
                    Logger.persistenceService.warn(e) { "Could not clean up thumbnails for deleted project, but proceeding with selection deletion" }
                }

                selectionFile.delete()
                Logger.persistenceService.info { "Deleted cached project for folder: $folderPath" }
            }
        } catch (e: Exception) {
            Logger.persistenceService.error(e) { "Error deleting cached project for folder: $folderPath" }
        }
    }
}
