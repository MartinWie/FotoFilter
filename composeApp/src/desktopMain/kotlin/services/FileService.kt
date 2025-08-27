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

        // Recursively collect all files
        val allFiles = folder.walkTopDown().filter { it.isFile }.toList()
        if (allFiles.isEmpty()) return@withContext emptyList()

        // Filter only supported files first to reduce grouping size
        val supported = allFiles.filter { f ->
            val ext = f.extension.lowercase()
            isRawFile(ext) || isProcessedImageFile(ext)
        }

        // Group by (relativeDirectory + baseName) to avoid mixing same name from different folders
        val basePathLength = folder.absolutePath.length + 1
        val grouped = supported.groupBy { f ->
            val relDir = if (f.parentFile == null) "" else f.parentFile.absolutePath.let { p ->
                if (p.length > basePathLength) p.substring(basePathLength) else ""
            }
            relDir + "|" + f.nameWithoutExtension
        }

        grouped.mapNotNull { (_, files) ->
            val rawFile = files.find { isRawFile(it.extension) }
            val processedFile = files.find { isProcessedImageFile(it.extension) }
            when {
                rawFile != null && processedFile != null -> {
                    val date = minOf(rawFile.lastModified(), processedFile.lastModified())
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = processedFile.absolutePath,
                        fileName = rawFile.nameWithoutExtension,
                        dateCreated = date,
                        status = PhotoStatus.UNDECIDED
                    )
                }
                rawFile != null -> {
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = null,
                        fileName = rawFile.nameWithoutExtension,
                        dateCreated = rawFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                processedFile != null -> {
                    Photo(
                        rawPath = null,
                        jpegPath = processedFile.absolutePath,
                        fileName = processedFile.nameWithoutExtension,
                        dateCreated = processedFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                else -> null
            }
        }.sortedBy { it.dateCreated }
    }

    suspend fun scanFolderWithSkipped(folderPath: String): Pair<List<Photo>, List<String>> = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return@withContext Pair(emptyList(), emptyList())

        val allFiles = folder.walkTopDown().filter { it.isFile }.toList()
        if (allFiles.isEmpty()) return@withContext Pair(emptyList(), emptyList())

        val supported = allFiles.filter { f ->
            val ext = f.extension.lowercase()
            isRawFile(ext) || isProcessedImageFile(ext)
        }
        val unsupported = allFiles - supported

        val basePathLength = folder.absolutePath.length + 1
        val grouped = supported.groupBy { f ->
            val relDir = if (f.parentFile == null) "" else f.parentFile.absolutePath.let { p ->
                if (p.length > basePathLength) p.substring(basePathLength) else ""
            }
            relDir + "|" + f.nameWithoutExtension
        }

        val photos = grouped.mapNotNull { (_, files) ->
            val rawFile = files.find { isRawFile(it.extension) }
            val processedFile = files.find { isProcessedImageFile(it.extension) }
            when {
                rawFile != null && processedFile != null -> {
                    val date = minOf(rawFile.lastModified(), processedFile.lastModified())
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = processedFile.absolutePath,
                        fileName = rawFile.nameWithoutExtension,
                        dateCreated = date,
                        status = PhotoStatus.UNDECIDED
                    )
                }
                rawFile != null -> {
                    Photo(
                        rawPath = rawFile.absolutePath,
                        jpegPath = null,
                        fileName = rawFile.nameWithoutExtension,
                        dateCreated = rawFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                processedFile != null -> {
                    Photo(
                        rawPath = null,
                        jpegPath = processedFile.absolutePath,
                        fileName = processedFile.nameWithoutExtension,
                        dateCreated = processedFile.lastModified(),
                        status = PhotoStatus.UNDECIDED
                    )
                }
                else -> null
            }
        }.sortedBy { it.dateCreated }

        val skippedRel = unsupported.mapNotNull { f ->
            // Skip hidden/system files starting with dot with no extension, and macOS .DS_Store meta files
            if ((f.name.startsWith('.') && f.extension.isEmpty()) || f.name.equals(".DS_Store", ignoreCase = true)) null else {
                val abs = f.absolutePath
                if (abs.length > basePathLength) abs.substring(basePathLength) else f.name
            }
        }.sorted()

        Pair(photos, skippedRel)
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

    suspend fun deleteDiscardedPhotos(photos: List<Photo>) = withContext(Dispatchers.IO) {
        photos.forEach { photo ->
            if (photo.status == PhotoStatus.DISCARD) {
                photo.rawPath?.let { deleteFileIfExists(it) }
                photo.jpegPath?.let { deleteFileIfExists(it) }
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

    private fun deleteFileIfExists(path: String) {
        try {
            val f = File(path)
            if (f.exists()) {
                f.delete()
            }
        } catch (_: Exception) { }
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
