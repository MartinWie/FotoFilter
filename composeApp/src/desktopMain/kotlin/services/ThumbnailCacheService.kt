package services

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import models.Photo
import org.jetbrains.skia.Image
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import utils.Logger

class ThumbnailCacheService {
    private val baseCacheDir = File(System.getProperty("user.home"), ".fotofilter/thumbnails")
    private val thumbnailSize = 200 // Even smaller thumbnails to reduce memory
    private val previewSize = 1200   // Smaller previews for better memory management

    // Sliding window cache to track what's currently needed
    private val slidingWindowCache = mutableSetOf<String>()
    private val maxSlidingWindowSize = 50

    init {
        // Ensure base cache directory exists
        baseCacheDir.mkdirs()
    }

    /**
     * Import process: Generate all thumbnails and previews for a folder (MEMORY-OPTIMIZED PARALLEL)
     */
    suspend fun importFolder(photos: List<Photo>, folderPath: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext

        val totalPhotos = photos.size
        val processedCount = AtomicInteger(0)
        val maxConcurrency = Runtime.getRuntime().availableProcessors()

        val batchSize = maxOf(1, totalPhotos / maxConcurrency)

        Logger.cacheService.info { "Starting memory-optimized parallel import for project: $folderPath with $maxConcurrency threads, batch size: $batchSize" }

        // Process in smaller chunks to avoid memory overflow
        val chunks = photos.chunked(batchSize)

        chunks.forEach { chunk ->
            // Process each chunk with limited concurrency
            val semaphore = Semaphore(maxConcurrency)

            chunk.map { photo ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        // Skip if already cached to save memory
                        val thumbnailFile = getThumbnailCacheFile(photo, folderPath)
                        val previewFile = getPreviewCacheFile(photo, folderPath)

                        if (!thumbnailFile.exists()) {
                            generateThumbnailCacheOptimized(photo, folderPath)
                        }

                        if (!previewFile.exists()) {
                            generatePreviewCacheOptimized(photo, folderPath)
                        }

                        // Update progress
                        val completed = processedCount.incrementAndGet()
                        onProgress(completed, totalPhotos)

                        // Force garbage collection every 10 images to prevent heap buildup
                        if (completed % 10 == 0) {
                            System.gc()
                        }

                    } catch (e: Exception) {
                        Logger.cacheService.warn(e) { "Error processing ${photo.fileName}" }
                        val completed = processedCount.incrementAndGet()
                        onProgress(completed, totalPhotos)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()

            // Force cleanup between chunks
            System.gc()
            Thread.sleep(50) // Brief pause to let GC complete
        }

        Logger.cacheService.info { "Memory-optimized parallel import completed for project: $folderPath. Processed $totalPhotos photos." }
    }

    /**
     * Sliding window preloading: preload thumbnails in a window around current position
     */
    suspend fun preloadSlidingWindow(photos: List<Photo>, folderPath: String, centerIndex: Int, windowSize: Int = 40) = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext

        val startIndex = maxOf(0, centerIndex - windowSize)
        val endIndex = minOf(photos.size - 1, centerIndex + windowSize)

        // Create new sliding window set
        val newWindow = mutableSetOf<String>()

        // Preload thumbnails in sliding window - prioritize closer images
        for (i in startIndex..endIndex) {
            val photoId = photos[i].id
            newWindow.add(photoId)

            // Only generate if not already cached on disk
            val thumbnailFile = getThumbnailCacheFile(photos[i], folderPath)
            if (!thumbnailFile.exists()) {
                try {
                    generateThumbnailCache(photos[i], folderPath)
                } catch (e: Exception) {
                    Logger.cacheService.debug(e) { "Error preloading thumbnail for ${photos[i].fileName}" }
                }
            }
        }

        // Update sliding window
        slidingWindowCache.clear()
        slidingWindowCache.addAll(newWindow)

        // Preload more aggressive neighbor previews
        preloadImmediatePreviews(photos, folderPath, centerIndex)
    }

    private suspend fun preloadImmediatePreviews(photos: List<Photo>, folderPath: String, centerIndex: Int) = withContext(Dispatchers.IO) {
        // Preload previews for current and more neighbors (7 total)
        val previewIndices = listOf(
            centerIndex - 3, centerIndex - 2, centerIndex - 1,
            centerIndex,
            centerIndex + 1, centerIndex + 2, centerIndex + 3
        ).filter { it in 0 until photos.size }

        previewIndices.forEach { index ->
            val photo = photos[index]
            val previewFile = getPreviewCacheFile(photo, folderPath)

            if (!previewFile.exists()) {
                try {
                    generatePreviewCache(photo, folderPath)
                } catch (e: Exception) {
                    Logger.cacheService.debug(e) { "Error preloading preview for ${photo.fileName}" }
                }
            }
        }
    }

    /**
     * Get thumbnail from disk cache or generate if not exists
     */
    suspend fun getThumbnail(photo: Photo, folderPath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheFile = getThumbnailCacheFile(photo, folderPath)

        if (cacheFile.exists()) {
            // Load from disk cache
            try {
                val bytes = cacheFile.readBytes()
                return@withContext Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                Logger.cacheService.debug(e) { "Error loading cached thumbnail, regenerating" }
                // Fall through to regenerate
            }
        }

        // Generate and cache thumbnail
        return@withContext generateThumbnailCache(photo, folderPath)
    }

    /**
     * Get preview from disk cache or generate if not exists
     */
    suspend fun getPreview(photo: Photo, folderPath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheFile = getPreviewCacheFile(photo, folderPath)

        if (cacheFile.exists()) {
            // Load from disk cache
            try {
                val bytes = cacheFile.readBytes()
                return@withContext Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                Logger.cacheService.debug(e) { "Error loading cached preview, regenerating" }
                // Fall through to regenerate
            }
        }

        // Generate and cache preview
        return@withContext generatePreviewCache(photo, folderPath)
    }

    private fun generateThumbnailCache(photo: Photo, folderPath: String): ImageBitmap? {
        try {
            val originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.rawPath))
                ?: return null

            val thumbnail = resizeImage(originalImage, thumbnailSize)
            val cacheFile = getThumbnailCacheFile(photo, folderPath)

            // Save to disk
            cacheFile.parentFile?.mkdirs()
            ImageIO.write(thumbnail, "jpeg", cacheFile)

            // Return as ImageBitmap
            val bytes = cacheFile.readBytes()
            return Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error generating thumbnail cache for ${photo.fileName}" }
            return null
        }
    }

    private fun generatePreviewCache(photo: Photo, folderPath: String): ImageBitmap? {
        try {
            val originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.rawPath))
                ?: return null

            val preview = resizeImage(originalImage, previewSize)
            val cacheFile = getPreviewCacheFile(photo, folderPath)

            // Save to disk
            cacheFile.parentFile?.mkdirs()
            ImageIO.write(preview, "jpeg", cacheFile)

            // Return as ImageBitmap
            val bytes = cacheFile.readBytes()
            return Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error generating preview cache for ${photo.fileName}" }
            return null
        }
    }

    private fun getThumbnailCacheFile(photo: Photo, folderPath: String): File {
        val projectDir = getProjectCacheDir(folderPath)
        val hash = generateFileHash(photo)
        return File(projectDir, "thumb_${hash}.jpg")
    }

    private fun getPreviewCacheFile(photo: Photo, folderPath: String): File {
        val projectDir = getProjectCacheDir(folderPath)
        val hash = generateFileHash(photo)
        return File(projectDir, "preview_${hash}.jpg")
    }

    private fun generateFileHash(photo: Photo): String {
        val path = photo.jpegPath ?: photo.rawPath
        val file = File(path)
        val input = "${file.absolutePath}_${file.lastModified()}_${file.length()}"

        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadImageWithCorrectOrientation(file: File): BufferedImage? {
        try {
            val originalImage = ImageIO.read(file) ?: return null

            val orientation = try {
                val metadata = ImageMetadataReader.readMetadata(file)
                val exifDirectory = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                exifDirectory?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
            } catch (e: Exception) {
                detectOrientationFromContext(file, originalImage)
            }

            return applyOrientation(originalImage, orientation)
        } catch (e: Exception) {
            return ImageIO.read(file)
        }
    }

    private fun detectOrientationFromContext(file: File, image: BufferedImage): Int {
        val aspectRatio = image.width.toDouble() / image.height.toDouble()
        val fileName = file.name.lowercase()

        return when {
            aspectRatio < 0.6 -> 6
            aspectRatio > 1.8 -> 8
            fileName.contains("portrait") || fileName.contains("vert") -> 6
            else -> 1
        }
    }

    private fun applyOrientation(image: BufferedImage, orientation: Int): BufferedImage {
        return when (orientation) {
            1 -> image
            3 -> rotateImage(image, 180.0)
            6 -> rotateImage(image, 90.0)
            8 -> rotateImage(image, -90.0)
            2 -> flipHorizontal(image)
            4 -> flipVertical(image)
            5 -> flipHorizontal(rotateImage(image, 90.0))
            7 -> flipHorizontal(rotateImage(image, -90.0))
            else -> image
        }
    }

    private fun rotateImage(image: BufferedImage, degrees: Double): BufferedImage {
        val radians = Math.toRadians(degrees)
        val sin = abs(kotlin.math.sin(radians))
        val cos = abs(kotlin.math.cos(radians))

        val newWidth = (image.width * cos + image.height * sin).toInt()
        val newHeight = (image.width * sin + image.height * cos).toInt()

        val rotatedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = rotatedImage.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val transform = AffineTransform()
        transform.translate(newWidth / 2.0, newHeight / 2.0)
        transform.rotate(radians)
        transform.translate(-image.width / 2.0, -image.height / 2.0)

        g2d.transform = transform
        g2d.drawImage(image, 0, 0, null)
        g2d.dispose()

        return rotatedImage
    }

    private fun flipHorizontal(image: BufferedImage): BufferedImage {
        val flipped = BufferedImage(image.width, image.height, image.type)
        val g2d = flipped.createGraphics()
        g2d.drawImage(image, image.width, 0, 0, image.height, 0, 0, image.width, image.height, null)
        g2d.dispose()
        return flipped
    }

    private fun flipVertical(image: BufferedImage): BufferedImage {
        val flipped = BufferedImage(image.width, image.height, image.type)
        val g2d = flipped.createGraphics()
        g2d.drawImage(image, 0, image.height, image.width, 0, 0, 0, image.width, image.height, null)
        g2d.dispose()
        return flipped
    }

    private fun resizeImage(originalImage: BufferedImage, maxSize: Int): BufferedImage {
        val originalWidth = originalImage.width
        val originalHeight = originalImage.height

        val ratio = minOf(maxSize.toDouble() / originalWidth, maxSize.toDouble() / originalHeight)
        val newWidth = (originalWidth * ratio).toInt()
        val newHeight = (originalHeight * ratio).toInt()

        val resizedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = resizedImage.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        return resizedImage
    }

    /**
     * Clean up old cache files
     */
    fun cleanupCache(maxAgeHours: Int = 24 * 7) { // Default: 1 week
        val cutoffTime = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000)

        baseCacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }

    /**
     * Get cache size in MB
     */
    fun getCacheSizeMB(): Double {
        val totalBytes = baseCacheDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
        return totalBytes / (1024.0 * 1024.0)
    }

    /**
     * Clear all cache
     */
    fun clearCache() {
        try {
            baseCacheDir.deleteRecursively()
            baseCacheDir.mkdirs()
            Logger.cacheService.info { "Cache cleared successfully" }
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error clearing cache" }
        }
    }

    /**
     * Cleanup cache on app shutdown - REMOVED: Keep cache persistent
     */
    fun setupShutdownHook() {
        // No longer cleaning cache on shutdown to maintain persistence
        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.cacheService.info { "App shutting down - cache preserved for next session" }
        })
    }

    /**
     * Clean up cache for specific photos (useful after export)
     */
    fun cleanupPhotosCache(photos: List<Photo>, folderPath: String) {
        try {
            var deletedCount = 0
            photos.forEach { photo ->
                val thumbnailFile = getThumbnailCacheFile(photo, folderPath)
                val previewFile = getPreviewCacheFile(photo, folderPath)

                if (thumbnailFile.exists() && thumbnailFile.delete()) deletedCount++
                if (previewFile.exists() && previewFile.delete()) deletedCount++
            }
            Logger.cacheService.info { "Cleaned up cache for ${photos.size} photos ($deletedCount files deleted)" }
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error cleaning up photos cache" }
        }
    }

    /**
     * Clean up cache for a specific project
     */
    fun cleanupProjectCache(folderPath: String) {
        try {
            val projectDir = getProjectCacheDir(folderPath)
            if (projectDir.exists()) {
                val deletedCount = projectDir.listFiles()?.size ?: 0
                projectDir.deleteRecursively()
                Logger.cacheService.info { "Cleaned up project cache for $folderPath ($deletedCount files deleted)" }
            }
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error cleaning up project cache for $folderPath" }
        }
    }

    /**
     * Memory-optimized thumbnail generation that doesn't return ImageBitmap
     */
    private fun generateThumbnailCacheOptimized(photo: Photo, folderPath: String): Boolean {
        var originalImage: BufferedImage? = null
        var thumbnail: BufferedImage? = null

        try {
            originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.rawPath))
                ?: return false

            thumbnail = resizeImage(originalImage, thumbnailSize)
            val cacheFile = getThumbnailCacheFile(photo, folderPath)

            // Save to disk immediately and don't keep in memory
            cacheFile.parentFile?.mkdirs()
            ImageIO.write(thumbnail, "jpeg", cacheFile)

            return true
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error generating thumbnail cache for ${photo.fileName}" }
            return false
        } finally {
            // Explicitly clear references to help GC
            originalImage?.flush()
            thumbnail?.flush()
            originalImage = null
            thumbnail = null
        }
    }

    /**
     * Memory-optimized preview generation that doesn't return ImageBitmap
     */
    private fun generatePreviewCacheOptimized(photo: Photo, folderPath: String): Boolean {
        var originalImage: BufferedImage? = null
        var preview: BufferedImage? = null

        try {
            originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.rawPath))
                ?: return false

            preview = resizeImage(originalImage, previewSize)
            val cacheFile = getPreviewCacheFile(photo, folderPath)

            // Save to disk immediately and don't keep in memory
            cacheFile.parentFile?.mkdirs()
            ImageIO.write(preview, "jpeg", cacheFile)

            return true
        } catch (e: Exception) {
            Logger.cacheService.error(e) { "Error generating preview cache for ${photo.fileName}" }
            return false
        } finally {
            // Explicitly clear references to help GC
            originalImage?.flush()
            preview?.flush()
            originalImage = null
            preview = null
        }
    }

    /**
     * Get project-specific cache directory
     */
    private fun getProjectCacheDir(folderPath: String): File {
        // Create a safe folder name from the project path
        val safeName = folderPath
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace(" ", "_")
            .take(100) // Limit length

        val projectDir = File(baseCacheDir, safeName)
        projectDir.mkdirs()
        return projectDir
    }
}
