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
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.ExecuteWatchdog

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
            val originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.primaryPath))
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
            val originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.primaryPath))
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
        val path = photo.jpegPath ?: photo.primaryPath
        val file = File(path)
        val input = "${file.absolutePath}_${file.lastModified()}_${file.length()}"

        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadImageWithCorrectOrientation(file: File): BufferedImage? {
        try {
            // Try to load the image with enhanced format support
            val originalImage = loadImageWithFormatSupport(file) ?: return null

            val orientation = try {
                val metadata = ImageMetadataReader.readMetadata(file)
                val exifDirectory = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                exifDirectory?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
            } catch (e: Exception) {
                detectOrientationFromContext(file, originalImage)
            }

            return applyOrientation(originalImage, orientation)
        } catch (e: Exception) {
            Logger.cacheService.warn(e) { "Error loading image with orientation: ${file.name}" }
            return loadImageWithFormatSupport(file)
        }
    }

    private fun loadImageWithFormatSupport(file: File): BufferedImage? {
        val extension = file.extension.lowercase()

        return when (extension) {
            "heic", "heif" -> loadHeicImage(file)
            // RAW file formats
            "cr3", "cr2", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng", "raw",
            "raf", "orf", "rw2", "pef", "rwl", "dcs", "3fr", "mef", "iiq", "x3f" -> loadRawImage(file)
            else -> {
                try {
                    // Try standard ImageIO first (now enhanced with TwelveMonkeys plugins)
                    ImageIO.read(file)
                } catch (e: Exception) {
                    Logger.cacheService.debug(e) { "Standard ImageIO failed for ${file.name}, trying alternative methods" }
                    null
                }
            }
        }
    }

    private fun loadHeicImage(file: File): BufferedImage? {
        return try {
            // First, try to use macOS native conversion via sips command (available on macOS)
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                convertHeicWithSips(file)
            } else {
                // On other platforms, try ImageIO with plugins or fallback
                ImageIO.read(file) ?: generateHeicPlaceholder(file)
            }
        } catch (e: Exception) {
            Logger.cacheService.warn(e) { "Failed to load HEIC image: ${file.name}" }
            generateHeicPlaceholder(file)
        }
    }

    private fun loadRawImage(file: File): BufferedImage? {
        return try {
            // Try to load with TwelveMonkeys ImageIO plugins (which may support some RAW formats)
            Logger.cacheService.debug { "Attempting to load RAW file with TwelveMonkeys ImageIO: ${file.name}" }
            val image = ImageIO.read(file)

            if (image != null) {
                Logger.cacheService.info { "Successfully loaded RAW file with ImageIO: ${file.name} (${image.width}x${image.height})" }
                return image
            } else {
                Logger.cacheService.debug { "TwelveMonkeys ImageIO could not read RAW file: ${file.name}" }
                return generateUnsupportedPlaceholder(file)
            }
        } catch (e: Exception) {
            Logger.cacheService.debug(e) { "Error loading RAW file with ImageIO: ${file.name}" }
            generateUnsupportedPlaceholder(file)
        }
    }

    private fun generateUnsupportedPlaceholder(file: File): BufferedImage {
        // Create a placeholder image indicating the format is not supported
        val width = 400
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()

        // Set background to a neutral color
        g2d.color = java.awt.Color(245, 245, 245)
        g2d.fillRect(0, 0, width, height)

        // Draw border
        g2d.color = java.awt.Color(200, 200, 200)
        g2d.drawRect(0, 0, width - 1, height - 1)

        // Draw file extension
        g2d.color = java.awt.Color(60, 60, 60)
        g2d.font = java.awt.Font("Arial", java.awt.Font.BOLD, 28)

        val extension = file.extension.uppercase()
        val extensionMetrics = g2d.fontMetrics
        val extensionWidth = extensionMetrics.stringWidth(extension)

        g2d.drawString(extension, (width - extensionWidth) / 2, height / 2 - 40)

        // Draw "Not Supported" message
        g2d.font = java.awt.Font("Arial", java.awt.Font.PLAIN, 16)
        val message = "Currently not supported"
        val messageMetrics = g2d.fontMetrics
        val messageWidth = messageMetrics.stringWidth(message)

        g2d.drawString(message, (width - messageWidth) / 2, height / 2 - 5)

        // Draw filename
        g2d.font = java.awt.Font("Arial", java.awt.Font.PLAIN, 12)
        val fileName = file.name
        val fileNameMetrics = g2d.fontMetrics
        val fileNameWidth = fileNameMetrics.stringWidth(fileName)

        // If filename is too long, truncate it
        val displayName = if (fileNameWidth > width - 20) {
            val truncated = fileName.take(35) + "..."
            truncated
        } else {
            fileName
        }

        val displayWidth = g2d.fontMetrics.stringWidth(displayName)
        g2d.drawString(displayName, (width - displayWidth) / 2, height / 2 + 25)

        g2d.dispose()
        return image
    }

    private fun convertRawWithSips(file: File): BufferedImage? {
        return try {
            // Create a temporary JPEG file
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tempJpegFile = File(tempDir, "raw_temp_${System.currentTimeMillis()}.jpg")

            // Use macOS sips command to convert RAW to JPEG
            val cmdLine = CommandLine("sips")
            cmdLine.addArgument("-s")
            cmdLine.addArgument("format")
            cmdLine.addArgument("jpeg")
            cmdLine.addArgument(file.absolutePath)
            cmdLine.addArgument("--out")
            cmdLine.addArgument(tempJpegFile.absolutePath)

            val executor = DefaultExecutor()
            executor.setExitValue(0)

            // Set a timeout for the conversion (30 seconds)
            executor.setWatchdog(ExecuteWatchdog(30000))

            Logger.cacheService.debug { "Attempting to convert RAW file: ${file.name}" }
            val result = executor.execute(cmdLine)

            if (result == 0 && tempJpegFile.exists() && tempJpegFile.length() > 0) {
                val image = ImageIO.read(tempJpegFile)
                tempJpegFile.delete() // Clean up temp file
                if (image != null) {
                    Logger.cacheService.debug { "Successfully converted RAW file: ${file.name} (${image.width}x${image.height})" }
                    return image
                } else {
                    Logger.cacheService.debug { "Converted file exists but ImageIO couldn't read it: ${file.name}" }
                }
            } else {
                Logger.cacheService.debug { "sips RAW conversion failed for ${file.name} - exit code: $result, file exists: ${tempJpegFile.exists()}, size: ${if (tempJpegFile.exists()) tempJpegFile.length() else "N/A"}" }
            }

            // Clean up temp file if it exists
            if (tempJpegFile.exists()) {
                tempJpegFile.delete()
            }

            return null
        } catch (e: Exception) {
            Logger.cacheService.debug(e) { "sips RAW conversion error for ${file.name}: ${e.message}" }
            null
        }
    }

    private fun convertHeicWithSips(file: File): BufferedImage? {
        return try {
            // Create a temporary JPEG file
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tempJpegFile = File(tempDir, "heic_temp_${System.currentTimeMillis()}.jpg")

            // Use macOS sips command to convert HEIC to JPEG
            val cmdLine = CommandLine("sips")
            cmdLine.addArgument("-s")
            cmdLine.addArgument("format")
            cmdLine.addArgument("jpeg")
            cmdLine.addArgument(file.absolutePath)
            cmdLine.addArgument("--out")
            cmdLine.addArgument(tempJpegFile.absolutePath)

            val executor = DefaultExecutor()
            executor.setExitValue(0)

            val result = executor.execute(cmdLine)

            if (result == 0 && tempJpegFile.exists()) {
                val image = ImageIO.read(tempJpegFile)
                tempJpegFile.delete() // Clean up temp file
                image
            } else {
                Logger.cacheService.debug { "sips conversion failed for ${file.name}" }
                null
            }
        } catch (e: Exception) {
            Logger.cacheService.debug(e) { "sips conversion error for ${file.name}" }
            null
        }
    }

    private fun generateHeicPlaceholder(file: File): BufferedImage {
        // Create a placeholder image with HEIC file info
        val width = 400
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()

        // Set background
        g2d.color = java.awt.Color(240, 240, 240)
        g2d.fillRect(0, 0, width, height)

        // Draw border
        g2d.color = java.awt.Color(200, 200, 200)
        g2d.drawRect(0, 0, width - 1, height - 1)

        // Draw HEIC icon/text
        g2d.color = java.awt.Color(100, 100, 100)
        g2d.font = java.awt.Font("Arial", java.awt.Font.BOLD, 24)

        val text = "HEIC"
        val fontMetrics = g2d.fontMetrics
        val textWidth = fontMetrics.stringWidth(text)
        val textHeight = fontMetrics.height

        g2d.drawString(text, (width - textWidth) / 2, (height + textHeight) / 2 - 20)

        // Draw filename
        g2d.font = java.awt.Font("Arial", java.awt.Font.PLAIN, 12)
        val fileName = file.name
        val fileNameMetrics = g2d.fontMetrics
        val fileNameWidth = fileNameMetrics.stringWidth(fileName)

        g2d.drawString(fileName, (width - fileNameWidth) / 2, (height + textHeight) / 2 + 20)

        g2d.dispose()
        return image
    }

    private fun generateRawPlaceholder(file: File): BufferedImage {
        // Create a placeholder image with RAW file info
        val width = 400
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()

        // Set background to a darker color to distinguish from HEIC
        g2d.color = java.awt.Color(220, 220, 220)
        g2d.fillRect(0, 0, width, height)

        // Draw border
        g2d.color = java.awt.Color(180, 180, 180)
        g2d.drawRect(0, 0, width - 1, height - 1)

        // Draw RAW icon/text
        g2d.color = java.awt.Color(80, 80, 80)
        g2d.font = java.awt.Font("Arial", java.awt.Font.BOLD, 24)

        val extension = file.extension.uppercase()
        val text = if (extension.isNotEmpty()) extension else "RAW"
        val fontMetrics = g2d.fontMetrics
        val textWidth = fontMetrics.stringWidth(text)
        val textHeight = fontMetrics.height

        g2d.drawString(text, (width - textWidth) / 2, (height + textHeight) / 2 - 20)

        // Draw filename
        g2d.font = java.awt.Font("Arial", java.awt.Font.PLAIN, 12)
        val fileName = file.name
        val fileNameMetrics = g2d.fontMetrics
        val fileNameWidth = fileNameMetrics.stringWidth(fileName)

        g2d.drawString(fileName, (width - fileNameWidth) / 2, (height + textHeight) / 2 + 20)

        // Add "RAW File" subtitle
        g2d.font = java.awt.Font("Arial", java.awt.Font.ITALIC, 10)
        val subtitle = "RAW File"
        val subtitleMetrics = g2d.fontMetrics
        val subtitleWidth = subtitleMetrics.stringWidth(subtitle)

        g2d.drawString(subtitle, (width - subtitleWidth) / 2, (height + textHeight) / 2 + 40)

        g2d.dispose()
        return image
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
            originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.primaryPath))
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
            originalImage = loadImageWithCorrectOrientation(File(photo.jpegPath ?: photo.primaryPath))
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

    private fun convertRawWithSipsAdvanced(file: File): BufferedImage? {
        return try {
            // Create a temporary JPEG file
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tempJpegFile = File(tempDir, "raw_temp_${System.currentTimeMillis()}.jpg")

            // Use simpler sips command - the advanced options were causing issues
            val cmdLine = CommandLine("sips")
            cmdLine.addArgument("-s")
            cmdLine.addArgument("format")
            cmdLine.addArgument("jpeg")
            cmdLine.addArgument(file.absolutePath)
            cmdLine.addArgument("--out")
            cmdLine.addArgument(tempJpegFile.absolutePath)

            val executor = DefaultExecutor()
            executor.setExitValue(0)

            // Shorter timeout since we're not doing heavy processing
            executor.setWatchdog(ExecuteWatchdog(10000)) // 10 seconds

            Logger.cacheService.debug { "Attempting simple sips conversion for RAW file: ${file.name}" }
            val result = executor.execute(cmdLine)

            if (result == 0 && tempJpegFile.exists() && tempJpegFile.length() > 1000) { // At least 1KB
                val image = ImageIO.read(tempJpegFile)
                tempJpegFile.delete() // Clean up temp file
                if (image != null && image.width > 10 && image.height > 10) { // Sanity check
                    Logger.cacheService.info { "Successfully converted RAW file with sips: ${file.name} (${image.width}x${image.height})" }
                    return image
                } else {
                    Logger.cacheService.debug { "Converted file exists but appears invalid: ${file.name}" }
                }
            } else {
                Logger.cacheService.debug { "sips RAW conversion failed for ${file.name} - exit code: $result, file exists: ${tempJpegFile.exists()}, size: ${if (tempJpegFile.exists()) tempJpegFile.length() else "N/A"}" }
            }

            // Clean up temp file if it exists
            if (tempJpegFile.exists()) {
                tempJpegFile.delete()
            }

            return null
        } catch (e: Exception) {
            Logger.cacheService.debug(e) { "sips RAW conversion error for ${file.name}: ${e.message}" }
            null
        }
    }

    private fun convertRawWithQuickLook(file: File): BufferedImage? {
        return try {
            // Create a temporary directory for QuickLook output
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tempPngFile = File(tempDir, "ql_temp_${System.currentTimeMillis()}.png")

            // Use qlmanage to generate a preview
            val cmdLine = CommandLine("qlmanage")
            cmdLine.addArgument("-t")
            cmdLine.addArgument("-s")
            cmdLine.addArgument("1024") // Preview size
            cmdLine.addArgument("-o")
            cmdLine.addArgument(tempDir.absolutePath)
            cmdLine.addArgument(file.absolutePath)

            val executor = DefaultExecutor()
            executor.setExitValue(0)
            executor.setWatchdog(ExecuteWatchdog(30000))

            Logger.cacheService.debug { "Attempting QuickLook conversion for RAW file: ${file.name}" }
            val result = executor.execute(cmdLine)

            if (result == 0) {
                // qlmanage creates files with specific naming convention
                val expectedFile = File(tempDir, "${file.nameWithoutExtension}.png")
                if (expectedFile.exists() && expectedFile.length() > 1000) {
                    val image = ImageIO.read(expectedFile)
                    expectedFile.delete() // Clean up temp file
                    if (image != null && image.width > 10 && image.height > 10) {
                        Logger.cacheService.info { "Successfully converted RAW file with QuickLook: ${file.name} (${image.width}x${image.height})" }
                        return image
                    }
                }
            }

            Logger.cacheService.debug { "QuickLook RAW conversion failed for ${file.name}" }
            return null
        } catch (e: Exception) {
            Logger.cacheService.debug(e) { "QuickLook RAW conversion error for ${file.name}: ${e.message}" }
            null
        }
    }
}
