package services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Photo
import models.PhotoStatus
import java.io.File

class FileService {
    suspend fun scanFolder(folderPath: String): List<Photo> = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return@withContext emptyList()

        val files = folder.listFiles() ?: return@withContext emptyList()

        val rawFiles = files.filter { isRawFile(it.extension) }
        val jpegFiles = files.filter { isJpegFile(it.extension) }

        // Find CR3/JPEG pairs based on filename
        rawFiles.mapNotNull { rawFile ->
            val baseName = rawFile.nameWithoutExtension
            val jpegFile = jpegFiles.find { it.nameWithoutExtension == baseName }

            Photo(
                rawPath = rawFile.absolutePath,
                jpegPath = jpegFile?.absolutePath,
                fileName = baseName,
                dateCreated = rawFile.lastModified(),
                status = PhotoStatus.UNDECIDED
            )
        }.sortedBy { it.dateCreated }
    }

    suspend fun exportPhotos(photos: List<Photo>, destinationPath: String) = withContext(Dispatchers.IO) {
        val destDir = File(destinationPath)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        photos.forEach { photo ->
            // Copy the RAW file
            val rawFile = File(photo.rawPath)
            if (rawFile.exists()) {
                rawFile.copyTo(File(destDir, rawFile.name), overwrite = true)
            }

            // Copy the JPEG file if it exists
            photo.jpegPath?.let {
                val jpegFile = File(it)
                if (jpegFile.exists()) {
                    jpegFile.copyTo(File(destDir, jpegFile.name), overwrite = true)
                }
            }
        }
    }

    private fun isRawFile(extension: String): Boolean {
        return extension.lowercase() in listOf("cr3", "cr2", "nef", "arw", "dng", "raw")
    }

    private fun isJpegFile(extension: String): Boolean {
        return extension.lowercase() in listOf("jpg", "jpeg")
    }
}
