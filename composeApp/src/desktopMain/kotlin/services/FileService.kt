package services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Photo
import models.PhotoStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileService {
    suspend fun scanFolder(folderPath: String): List<Photo> = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return@withContext emptyList()

        val files = folder.listFiles() ?: return@withContext emptyList()

        // Group files by base name for RAW/JPEG pairs
        val filesByBaseName = files.groupBy { it.nameWithoutExtension }

        filesByBaseName.mapNotNull { (baseName, files) ->
            val rawFile = files.find { isRawFile(it.extension) }
            val jpegFile = files.find { isJpegFile(it.extension) }

            // Only include if we have at least a RAW file
            rawFile?.let {
                Photo(
                    rawPath = it.absolutePath,
                    jpegPath = jpegFile?.absolutePath,
                    fileName = baseName,
                    dateCreated = it.lastModified(),
                    status = PhotoStatus.UNDECIDED
                )
            }
        }.sortedBy { it.dateCreated }
    }

    suspend fun exportPhotos(photos: List<Photo>, destinationPath: String) = withContext(Dispatchers.IO) {
        val destDir = File(destinationPath)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        photos.forEach { photo ->
            // Copy RAW file
            copyFileIfExists(photo.rawPath, destDir)

            // Copy JPEG file if it exists
            photo.jpegPath?.let { jpegPath ->
                copyFileIfExists(jpegPath, destDir)
            }
        }
    }

    private fun copyFileIfExists(sourcePath: String, destDir: File) {
        val sourceFile = File(sourcePath)
        if (sourceFile.exists()) {
            val destFile = File(destDir, sourceFile.name)
            Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isRawFile(extension: String): Boolean {
        return extension.lowercase() in setOf("cr3", "cr2", "nef", "arw", "dng", "raw", "raf", "orf")
    }

    private fun isJpegFile(extension: String): Boolean {
        return extension.lowercase() in setOf("jpg", "jpeg")
    }
}
