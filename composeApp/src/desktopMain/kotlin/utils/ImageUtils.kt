package utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import java.awt.RenderingHints
import java.awt.Graphics2D
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility functions for image handling with optimized performance
 * Prioritizes speed over quality for filtering purposes
 */
object ImageUtils {
    // Cache for loaded images
    private val imageCache = ConcurrentHashMap<String, ImageBitmap>()

    // Cache for thumbnails
    private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

    // Maximum image dimensions to limit memory usage
    private const val MAX_PREVIEW_DIMENSION = 1200

    /**
     * Clear the image caches to free memory
     */
    fun clearCaches() {
        imageCache.clear()
        thumbnailCache.clear()
    }

    /**
     * Load an image optimized for preview (JPEG preferred for speed)
     * Falls back to RAW only if JPEG is unavailable
     */
    fun loadPreviewImage(jpegPath: String?, rawPath: String): ImageBitmap? {
        // Always prefer JPEG path for performance if available
        val path = jpegPath ?: rawPath

        // Return from cache if available
        imageCache[path]?.let { return it }

        return runCatching {
            val file = File(path)
            if (!file.exists()) return null

            val bufferedImage = ImageIO.read(file) ?: return null
            val resized = resizeImageIfNeeded(bufferedImage, MAX_PREVIEW_DIMENSION)

            val bitmap = convertToImageBitmap(resized)
            imageCache[path] = bitmap
            bitmap
        }.getOrNull()
    }

    /**
     * Load a thumbnail version of the image with aggressive optimization for grid view
     * Always uses JPEG if available for best performance
     */
    fun loadThumbnail(jpegPath: String?, rawPath: String, maxDimension: Int = 160): ImageBitmap? {
        // Always prefer JPEG for thumbnails
        val path = jpegPath ?: rawPath
        val cacheKey = "$path-$maxDimension"

        // Return from cache if available
        thumbnailCache[cacheKey]?.let { return it }

        return runCatching {
            val file = File(path)
            if (!file.exists()) return null

            // Load and drastically resize for grid
            val original = ImageIO.read(file) ?: return null
            val resized = resizeImage(original, maxDimension, maxDimension)

            val bitmap = convertToImageBitmap(resized)
            thumbnailCache[cacheKey] = bitmap
            bitmap
        }.getOrNull()
    }

    /**
     * Convert a BufferedImage to ImageBitmap
     */
    private fun convertToImageBitmap(bufferedImage: BufferedImage): ImageBitmap {
        val outputStream = ByteArrayOutputStream()
        // Use JPEG format for better compression during conversion
        ImageIO.write(bufferedImage, "jpeg", outputStream)
        val byteArray = outputStream.toByteArray()
        return Image.makeFromEncoded(byteArray).toComposeImageBitmap()
    }

    /**
     * Resize image only if it exceeds maximum dimensions
     */
    private fun resizeImageIfNeeded(image: BufferedImage, maxDimension: Int): BufferedImage {
        val width = image.width
        val height = image.height

        // Only resize if image is too large
        if (width <= maxDimension && height <= maxDimension) {
            return image
        }

        return resizeImage(image, maxDimension, maxDimension)
    }

    /**
     * Resize an image while maintaining aspect ratio
     */
    private fun resizeImage(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
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

        // For very small thumbnails, use faster but lower quality rendering
        val fastRendering = maxWidth <= 200 && maxHeight <= 200

        // Create and draw the resized image
        val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = resized.createGraphics()

        // Set quality based on usage
        if (!fastRendering) {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        } else {
            // Use faster rendering for thumbnails
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        }

        g2d.drawImage(image, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        return resized
    }
}
