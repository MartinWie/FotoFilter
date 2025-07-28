package utils

import androidx.compose.ui.graphics.ImageBitmap
import models.Photo
import services.ThumbnailCacheService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object ImageUtils {
    // Very minimal in-memory cache - only for immediate display
    private val immediateCache = ConcurrentHashMap<String, ImageBitmap>()

    // Extremely small memory cache - just for current view
    private const val MAX_IMMEDIATE_CACHE = 10  // Only immediate items in memory

    private val thumbnailCacheService = ThumbnailCacheService()

    /**
     * Import folder: Generate all thumbnails and previews on disk
     */
    suspend fun importFolder(photos: List<Photo>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        thumbnailCacheService.importFolder(photos, onProgress)
    }

    suspend fun getThumbnail(photo: Photo): ImageBitmap? = withContext(Dispatchers.IO) {
        // Check immediate cache first
        immediateCache[photo.id]?.let { return@withContext it }

        // Load from disk cache (very fast)
        val thumbnail = thumbnailCacheService.getThumbnail(photo)

        // Add to immediate cache only
        if (thumbnail != null) {
            // Keep cache very small
            if (immediateCache.size >= MAX_IMMEDIATE_CACHE) {
                immediateCache.clear()
                System.gc()
            }
            immediateCache[photo.id] = thumbnail
        }

        thumbnail
    }

    suspend fun getPreview(photo: Photo): ImageBitmap? = withContext(Dispatchers.IO) {
        // Always load previews from disk to save memory
        thumbnailCacheService.getPreview(photo)
    }

    /**
     * Sliding window preloading - called when user scrolls or navigates
     */
    fun updateSlidingWindow(photos: List<Photo>, currentIndex: Int, isScrolling: Boolean = false) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val windowSize = if (isScrolling) 15 else 25 // Smaller window when scrolling fast

                // Update sliding window for thumbnails
                thumbnailCacheService.preloadSlidingWindow(photos, currentIndex, windowSize)

                // Clear memory cache when scrolling to prevent heap errors
                if (isScrolling) {
                    immediateCache.clear()
                    System.gc()
                }
            } catch (e: Exception) {
                // Emergency cleanup on any error
                emergencyCleanup()
            }
        }
    }

    /**
     * Preload images around current position - called on photo selection
     */
    fun preloadImagesAround(photos: List<Photo>, currentIndex: Int, range: Int = 5) {
        updateSlidingWindow(photos, currentIndex, isScrolling = false)
    }

    /**
     * Handle fast scrolling - called when user scrolls quickly through grid
     */
    fun handleFastScrolling(photos: List<Photo>, currentIndex: Int) {
        updateSlidingWindow(photos, currentIndex, isScrolling = true)
    }

    /**
     * Emergency memory cleanup
     */
    fun emergencyCleanup() {
        immediateCache.clear()
        System.gc()
        // Force multiple garbage collections to free memory
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
    }

    // Cache management functions
    fun getCacheSizeMB(): Double = thumbnailCacheService.getCacheSizeMB()
    fun cleanupDiskCache() = thumbnailCacheService.cleanupCache()
    fun clearDiskCache() = thumbnailCacheService.clearCache()
}
