package utils

import models.Photo

/**
 * Interface for platform-specific image processing operations
 */
interface ImageProcessing {
    /**
     * Preloads images for efficient display
     */
    fun preloadImages(photos: List<Photo>)

    /**
     * Updates the focus to a specific image index
     */
    fun updateFocusIndex(photos: List<Photo>, index: Int)
}
