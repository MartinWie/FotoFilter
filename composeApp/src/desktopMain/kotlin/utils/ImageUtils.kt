package utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import models.Photo
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Utility functions for image handling with optimized quality and memory usage
 * Enhanced for better image quality assessment and faster loading for large folders
 */
object ImageUtils : ImageProcessing, CoroutineScope {
    // Create a custom dispatcher for image loading operations specifically for desktop
    // This avoids using Dispatchers.IO which may try to reference Android classes
    private val imageProcessingDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher() // Increased from 4 to 8 threads

    // Coroutine context for image loading operations
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = imageProcessingDispatcher + job

    // Cache for loaded full-resolution images
    private val imageCache = ConcurrentHashMap<String, ImageBitmap>()

    // Cache for thumbnails (all thumbnails are kept in memory)
    private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

    // Loading tracking
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    // Track how many files are being loaded concurrently
    private val activeLoadsCounter = AtomicInteger(0)
    private const val MAX_CONCURRENT_LOADS = 16

    // Current preloading range
    private var currentPreloadRangeStart = 0
    private var currentPreloadRangeEnd = 0
    private var currentPhotosList = listOf<Photo>()

    // Maximum image dimensions to limit memory usage while maintaining enough detail for quality assessment
    private const val MAX_PREVIEW_DIMENSION = 2400 // Increased from 1800 for better quality
    private const val MIN_THUMBNAIL_DIMENSION = 400 // Increased from 300 for better thumbnails

    // Number of images to preload before and after the current image
    private const val PRELOAD_RANGE = 3 // Reduced from 5 to be more efficient with memory while still maintaining responsiveness

    /**
     * Update the current focus index and preload images in the range around it
     */
    override fun updateFocusIndex(photos: List<Photo>, currentIndex: Int) {
        currentPhotosList = photos
        val newRangeStart = maxOf(0, currentIndex - PRELOAD_RANGE)
        val newRangeEnd = minOf(photos.size - 1, currentIndex + PRELOAD_RANGE)

        // If the range has changed significantly, update the preloading
        if (newRangeStart != currentPreloadRangeStart || newRangeEnd != currentPreloadRangeEnd) {
            launch {
                // Release memory for images outside the preload range
                pruneImageCache(photos, newRangeStart, newRangeEnd)

                // Preload new range
                preloadRangeAround(photos, currentIndex)
            }

            currentPreloadRangeStart = newRangeStart
            currentPreloadRangeEnd = newRangeEnd
        }
    }

    /**
     * Clean up images outside the current range to conserve memory
     */
    private fun pruneImageCache(photos: List<Photo>, rangeStart: Int, rangeEnd: Int) {
        if (photos.isEmpty()) return

        // Build set of paths that should remain in cache
        val pathsToKeep = mutableSetOf<String>()
        for (i in rangeStart..rangeEnd) {
            if (i >= 0 && i < photos.size) {
                val photo = photos[i]
                val path = photo.jpegPath ?: photo.rawPath
                pathsToKeep.add(path)
            }
        }

        // Remove all cached full-resolution images not in the keep set
        val keysToRemove = mutableListOf<String>()
        imageCache.keys().asSequence().forEach { path ->
            if (!pathsToKeep.contains(path)) {
                keysToRemove.add(path)
            }
        }

        // Remove each item outside of range
        keysToRemove.forEach { imageCache.remove(it) }
    }

    /**
     * Preload all images in range around current index
     */
    fun preloadRangeAround(photos: List<Photo>, currentIndex: Int) {
        if (photos.isEmpty()) return

        // Calculate range ensuring it's within bounds
        val startIndex = maxOf(0, currentIndex - PRELOAD_RANGE)
        val endIndex = minOf(photos.size - 1, currentIndex + PRELOAD_RANGE)

        // Cancel any loading jobs outside the range
        val pathsInRange = (startIndex..endIndex).mapNotNull { index ->
            if (index >= 0 && index < photos.size) {
                val photo = photos[index]
                photo.jpegPath ?: photo.rawPath
            } else null
        }.toSet()

        loadingJobs.keys.toList().forEach { path ->
            if (!path.contains("-") && !pathsInRange.contains(path)) {  // Not a thumbnail (doesn't contain "-")
                loadingJobs[path]?.cancel()
                loadingJobs.remove(path)
            }
        }

        // Always preload thumbnails for all photos
        launch {
            photos.forEach { photo ->
                // Always load thumbnails for all photos
                loadThumbnailAsync(photo.jpegPath, photo.rawPath)
            }
        }

        // Load full-resolution images only for the range
        launch {
            // Start with current photo for best user experience
            if (currentIndex >= 0 && currentIndex < photos.size) {
                val currentPhoto = photos[currentIndex]
                loadPreviewImageAsync(currentPhoto.jpegPath, currentPhoto.rawPath)
            }

            // Then preload next and previous in alternating order
            var nextIndex = currentIndex + 1
            var prevIndex = currentIndex - 1

            while (nextIndex <= endIndex || prevIndex >= startIndex) {
                if (nextIndex <= endIndex && nextIndex < photos.size) {
                    val nextPhoto = photos[nextIndex]
                    loadPreviewImageAsync(nextPhoto.jpegPath, nextPhoto.rawPath)
                    nextIndex++
                }

                if (prevIndex >= startIndex && prevIndex >= 0) {
                    val prevPhoto = photos[prevIndex]
                    loadPreviewImageAsync(prevPhoto.jpegPath, prevPhoto.rawPath)
                    prevIndex--
                }

                // Brief delay to allow other operations
                delay(5)
            }
        }
    }

    /**
     * Preload all thumbnails but only nearby full-resolution images
     * Optimized for large folders (1000+ images)
     */
    override fun preloadImages(photos: List<Photo>) {
        if (photos.isEmpty()) return

        currentPhotosList = photos

        // For large collections, use a more efficient batch loading approach
        val isBatchMode = photos.size > 1000

        launch {
            if (isBatchMode) {
                // For very large folders, load thumbnails in batches
                photos.chunked(100).forEach { batch ->
                    batch.forEach { photo ->
                        while (activeLoadsCounter.get() >= MAX_CONCURRENT_LOADS) {
                            delay(10) // Wait until some loads complete
                        }
                        loadThumbnailAsync(photo.jpegPath, photo.rawPath)
                    }
                    delay(50) // Small pause between batches to allow UI updates
                }
            } else {
                // Standard loading for smaller folders
                photos.forEach { photo ->
                    loadThumbnailAsync(photo.jpegPath, photo.rawPath)
                }
            }

            // For full images, only preload the first few
            val initialPreloadCount = minOf(photos.size, 2 * PRELOAD_RANGE + 1)
            for (i in 0 until initialPreloadCount) {
                val photo = photos[i]
                loadPreviewImageAsync(photo.jpegPath, photo.rawPath)
            }

            currentPreloadRangeStart = 0
            currentPreloadRangeEnd = initialPreloadCount - 1
        }
    }

    /**
     * Asynchronously load a thumbnail with concurrency control
     */
    private fun loadThumbnailAsync(jpegPath: String?, rawPath: String, maxDimension: Int = MIN_THUMBNAIL_DIMENSION): Job {
        val path = jpegPath ?: rawPath
        val cacheKey = "$path-$maxDimension"

        // Don't start duplicate jobs
        if (loadingJobs.containsKey(cacheKey)) {
            return loadingJobs[cacheKey]!!
        }

        // If already cached, return completed job
        if (thumbnailCache.containsKey(cacheKey)) {
            return CompletableDeferred<Unit>().apply { complete(Unit) }
        }

        val job = launch {
            activeLoadsCounter.incrementAndGet()
            try {
                val file = File(path)
                if (!file.exists()) return@launch

                // Load and resize with better quality
                val original = withContext(imageProcessingDispatcher) {
                    try {
                        ImageIO.read(file)
                    } catch (e: OutOfMemoryError) {
                        System.gc()
                        delay(100)
                        null
                    }
                } ?: return@launch

                try {
                    val resized = resizeImageHighQuality(original, maxDimension, maxDimension)

                    // Clear reference to original to save memory
                    val bitmap = convertToHighQualityImageBitmap(resized)
                    thumbnailCache[cacheKey] = bitmap

                    // Help garbage collector
                    System.gc()
                } catch (e: OutOfMemoryError) {
                    System.gc()
                }
            } finally {
                activeLoadsCounter.decrementAndGet()
                loadingJobs.remove(cacheKey)
            }
        }

        loadingJobs[cacheKey] = job
        return job
    }

    /**
     * Asynchronously load a preview image with concurrency control
     */
    private fun loadPreviewImageAsync(jpegPath: String?, rawPath: String): Job {
        val path = jpegPath ?: rawPath

        // Don't start duplicate jobs
        if (loadingJobs.containsKey(path)) {
            return loadingJobs[path]!!
        }

        // If already cached, return completed job
        if (imageCache.containsKey(path)) {
            return CompletableDeferred<Unit>().apply { complete(Unit) }
        }

        val job = launch {
            activeLoadsCounter.incrementAndGet()
            try {
                val file = File(path)
                if (!file.exists()) return@launch

                val bufferedImage = withContext(imageProcessingDispatcher) {
                    try {
                        ImageIO.read(file)
                    } catch (e: OutOfMemoryError) {
                        System.gc()
                        delay(100)
                        null
                    }
                } ?: return@launch

                try {
                    val resized = resizeImageIfNeeded(bufferedImage, MAX_PREVIEW_DIMENSION)
                    val bitmap = convertToHighQualityImageBitmap(resized)
                    imageCache[path] = bitmap

                    // Help garbage collector
                    System.gc()
                } catch (e: OutOfMemoryError) {
                    System.gc()
                }
            } finally {
                activeLoadsCounter.decrementAndGet()
                loadingJobs.remove(path)
            }
        }

        loadingJobs[path] = job
        return job
    }

    /**
     * Load an image optimized for preview with highest quality for focus assessment
     * Falls back to RAW only if JPEG is unavailable
     */
    fun loadPreviewImage(jpegPath: String?, rawPath: String): ImageBitmap? {
        // Always prefer JPEG path for performance if available
        val path = jpegPath ?: rawPath

        // Return from cache if available
        imageCache[path]?.let { return it }

        // Start async loading if not already started
        if (!loadingJobs.containsKey(path)) {
            loadPreviewImageAsync(jpegPath, rawPath)
        }

        // For synchronous use, load immediately but with safety measures
        return runCatching {
            val file = File(path)
            if (!file.exists()) return null

            try {
                // Use runBlocking with our custom dispatcher to avoid Android references
                val bufferedImage = runBlocking(imageProcessingDispatcher) {
                    ImageIO.read(file)
                } ?: return null

                // Preserve more detail for focus assessment
                val resized = resizeImageIfNeeded(bufferedImage, MAX_PREVIEW_DIMENSION)
                val bitmap = convertToHighQualityImageBitmap(resized)
                imageCache[path] = bitmap
                bitmap
            } catch (e: OutOfMemoryError) {
                // If we encounter memory issues, suggest garbage collection and return null
                System.gc()
                null
            }
        }.getOrNull()
    }

    /**
     * Load a thumbnail version of the image with better quality for focus assessment
     */
    fun loadThumbnail(jpegPath: String?, rawPath: String, maxDimension: Int = MIN_THUMBNAIL_DIMENSION): ImageBitmap? {
        // Always prefer JPEG for thumbnails
        val path = jpegPath ?: rawPath
        val cacheKey = "$path-$maxDimension"

        // Return from cache if available
        thumbnailCache[cacheKey]?.let { return it }

        // Start async loading if not already started
        if (!loadingJobs.containsKey(cacheKey)) {
            loadThumbnailAsync(jpegPath, rawPath, maxDimension)
        }

        // For synchronous use, load immediately but with safety measures
        return runCatching {
            val file = File(path)
            if (!file.exists()) return null

            try {
                // Use runBlocking with our custom dispatcher to avoid Android references
                val original = runBlocking(imageProcessingDispatcher) {
                    ImageIO.read(file)
                } ?: return null

                val resized = resizeImageHighQuality(original, maxDimension, maxDimension)
                val bitmap = convertToHighQualityImageBitmap(resized)
                thumbnailCache[cacheKey] = bitmap
                bitmap
            } catch (e: OutOfMemoryError) {
                // If we encounter memory issues, suggest garbage collection and return null
                System.gc()
                null
            }
        }.getOrNull()
    }

    /**
     * Convert a BufferedImage to highest quality ImageBitmap
     */
    private fun convertToHighQualityImageBitmap(bufferedImage: BufferedImage): ImageBitmap {
        val outputStream = ByteArrayOutputStream()

        // Use PNG for lossless conversion of important images
        ImageIO.write(bufferedImage, "png", outputStream)
        val byteArray = outputStream.toByteArray()
        return Image.makeFromEncoded(byteArray).toComposeImageBitmap()
    }

    /**
     * Resize image only if it exceeds maximum dimensions, preserving quality
     */
    private fun resizeImageIfNeeded(image: BufferedImage, maxDimension: Int): BufferedImage {
        val width = image.width
        val height = image.height

        // Only resize if image is too large
        if (width <= maxDimension && height <= maxDimension) {
            return image
        }

        // Use highest quality resizing for focus assessment
        return resizeImageHighQuality(image, maxDimension, maxDimension)
    }

    /**
     * Resize an image while maintaining aspect ratio with highest quality settings
     */
    private fun resizeImageHighQuality(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
        val originalWidth = image.width
        val originalHeight = image.height

        // Calculate dimensions while maintaining aspect ratio
        var newWidth = originalWidth
        var newHeight = originalHeight

        // Scale down if needed
        if (originalWidth > maxWidth || originalHeight > maxHeight) {
            val widthRatio = maxWidth.toDouble() / originalWidth
            val heightRatio = maxHeight.toDouble() / originalHeight
            val scaleFactor = minOf(widthRatio, heightRatio)

            newWidth = (originalWidth * scaleFactor).toInt()
            newHeight = (originalHeight * scaleFactor).toInt()
        }

        // Create and draw the resized image with maximum quality
        val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = resized.createGraphics()

        // Use highest quality rendering for focus assessment
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)

        g2d.drawImage(image, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        return resized
    }

    /**
     * Clear the image caches to free memory and cancel any pending jobs
     */
    fun clearCaches() {
        imageCache.clear()
        thumbnailCache.clear()

        // Cancel all loading jobs
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
    }

    /**
     * Clean up resources when application shuts down
     */
    fun shutdown() {
        job.cancel()
    }
}
