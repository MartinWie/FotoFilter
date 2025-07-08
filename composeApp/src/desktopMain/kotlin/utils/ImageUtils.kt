package utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import models.Photo
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.lang.ref.SoftReference
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory

/**
 * Optimized image utilities with memory management and lazy loading
 */
object ImageUtils {
    // Use SoftReference cache to allow GC to reclaim memory when needed
    private val imageCache = ConcurrentHashMap<String, SoftReference<ImageBitmap>>(100)

    // Dedicated thread pool for image operations to prevent blocking UI
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val imageDispatcher = imageExecutor.asCoroutineDispatcher()

    // Maximum dimensions to prevent memory issues
    private const val MAX_DIMENSION = 1200  // Reduced from 1920
    private const val THUMBNAIL_SIZE = 200   // Reduced from 300

    /**
     * Load an image with automatic caching and size optimization
     */
    suspend fun loadImage(photo: Photo, isThumbnail: Boolean = false): ImageBitmap? = withContext(imageDispatcher) {
        val path = photo.jpegPath ?: photo.rawPath
        val cacheKey = if (isThumbnail) "$path-thumb" else path

        // Check cache first - handle SoftReference
        imageCache[cacheKey]?.get()?.let { return@withContext it }

        try {
            val file = File(path)
            if (!file.exists()) return@withContext null

            val originalImage = ImageIO.read(file) ?: return@withContext null

            // Apply EXIF orientation
            val orientedImage = applyExifOrientation(originalImage, getExifOrientation(file))

            // Resize aggressively to save memory
            val finalImage = if (isThumbnail) {
                resizeImage(orientedImage, THUMBNAIL_SIZE)
            } else {
                resizeImage(orientedImage, MAX_DIMENSION)
            }

            val imageBitmap = convertToImageBitmap(finalImage)

            // Cache using SoftReference - GC can reclaim if memory is low
            imageCache[cacheKey] = SoftReference(imageBitmap)

            // Aggressive cache size management
            if (imageCache.size > 50) {
                cleanupCache()
            }

            imageBitmap
        } catch (e: OutOfMemoryError) {
            // Handle OOM by clearing cache and forcing GC
            clearCache()
            System.gc()
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get thumbnail for a photo with aggressive caching
     */
    suspend fun getThumbnail(photo: Photo): ImageBitmap? = loadImage(photo, true)

    /**
     * Get full-size preview for a photo
     */
    suspend fun getPreview(photo: Photo): ImageBitmap? = loadImage(photo, false)

    /**
     * Clear cache and force garbage collection
     */
    fun clearCache() {
        imageCache.clear()
        System.gc()
    }

    /**
     * Clean up cache by removing entries with cleared SoftReferences
     */
    private fun cleanupCache() {
        val keysToRemove = mutableListOf<String>()
        imageCache.forEach { (key, softRef) ->
            if (softRef.get() == null) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { imageCache.remove(it) }

        // If still too large, remove oldest entries
        if (imageCache.size > 30) {
            val keysToRemoveMore = imageCache.keys.take(imageCache.size - 20)
            keysToRemoveMore.forEach { imageCache.remove(it) }
        }

        System.gc() // Suggest garbage collection
    }

    private fun resizeImage(image: BufferedImage, maxDimension: Int): BufferedImage {
        val width = image.width
        val height = image.height

        // Don't upscale and be more aggressive with downsizing
        if (width <= maxDimension && height <= maxDimension) return image

        val scale = minOf(maxDimension.toDouble() / width, maxDimension.toDouble() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // Use TYPE_INT_RGB instead of original type to save memory
        val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = resized.createGraphics()

        // Use faster interpolation for better performance
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED) // Changed from QUALITY to SPEED
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF) // Disabled for speed

        g2d.drawImage(image, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        return resized
    }

    private fun getExifOrientation(file: File): Int {
        return try {
            val metadata = ImageMetadataReader.readMetadata(file)
            val exifDirectory = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            exifDirectory?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
        } catch (e: Exception) {
            1
        }
    }

    private fun applyExifOrientation(image: BufferedImage, orientation: Int): BufferedImage {
        return when (orientation) {
            3 -> rotateImage(image, 180.0)
            6 -> rotateImage(image, 90.0)
            8 -> rotateImage(image, -90.0)
            else -> image
        }
    }

    private fun rotateImage(image: BufferedImage, degrees: Double): BufferedImage {
        val radians = Math.toRadians(degrees)
        val sin = kotlin.math.abs(kotlin.math.sin(radians))
        val cos = kotlin.math.abs(kotlin.math.cos(radians))

        val newWidth = (image.width * cos + image.height * sin).toInt()
        val newHeight = (image.width * sin + image.height * cos).toInt()

        val rotated = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = rotated.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.translate(newWidth / 2, newHeight / 2)
        g2d.rotate(radians)
        g2d.drawImage(image, -image.width / 2, -image.height / 2, null)
        g2d.dispose()

        return rotated
    }

    private fun convertToImageBitmap(bufferedImage: BufferedImage): ImageBitmap {
        return Image.makeFromEncoded(
            bufferedImageToByteArray(bufferedImage)
        ).toComposeImageBitmap()
    }

    private fun bufferedImageToByteArray(image: BufferedImage): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "JPEG", baos) // Use JPEG instead of PNG for smaller file size
        return baos.toByteArray()
    }
}
