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

        // Group files by base name for RAW/processed image pairs
        val filesByBaseName = files.groupBy { it.nameWithoutExtension }

        filesByBaseName.mapNotNull { (baseName, files) ->
            val rawFile = files.find { isRawFile(it.extension) }
            val processedFile = files.find { isProcessedImageFile(it.extension) }

            // Include if we have at least one supported file type
            when {
                rawFile != null && processedFile != null -> {
                    // Both RAW and processed image exist
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = processedFile.absolutePath,
                        fileName = baseName,
                        dateCreated = rawFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                rawFile != null -> {
                    // Only RAW file exists
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = null,
                        fileName = baseName,
                        dateCreated = rawFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                processedFile != null -> {
                    // Only processed image exists
                    Photo(
                        rawPath = null,
                        jpegPath = processedFile.absolutePath,
                        fileName = baseName,
                        dateCreated = processedFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                else -> null
            }
        }.sortedBy { it.dateCreated }
    }

    suspend fun exportPhotos(photos: List<Photo>, destinationPath: String) = withContext(Dispatchers.IO) {
        val destDir = File(destinationPath)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        photos.forEach { photo ->
            // Copy RAW file if it exists
            photo.rawPath?.let { rawPath ->
                copyFileIfExists(rawPath, destDir)
            }

            // Copy processed image file if it exists
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
        return extension.lowercase() in setOf(
            // Canon
            "cr3", "cr2", "crw",
            // Nikon
            "nef", "nrw",
            // Sony
            "arw", "srf", "sr2",
            // Adobe
            "dng",
            // Generic
            "raw",
            // Fujifilm
            "raf",
            // Olympus
            "orf",
            // Panasonic
            "rw2",
            // Pentax
            "pef",
            // Leica
            "rwl", "dcs",
            // Hasselblad
            "3fr",
            // Mamiya
            "mef",
            // Phase One
            "iiq",
            // Sigma
            "x3f"
        )
    }

    private fun isProcessedImageFile(extension: String): Boolean {
        return extension.lowercase() in setOf(
            // Standard JPEG
            "jpg", "jpeg",
            // Apple HEIC/HEIF
            "heic", "heif",
            // PNG
            "png",
            // WebP (Google)
            "webp",
            // TIFF
            "tiff", "tif",
            // BMP
            "bmp",
            // Android/Google formats
            "avif",
            // Additional mobile formats
            "jfif"
        )
    }
}
