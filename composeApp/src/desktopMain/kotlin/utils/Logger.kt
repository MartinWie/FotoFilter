package utils

import io.github.oshai.kotlinlogging.KotlinLogging

object Logger {
    // Create loggers for different components
    val viewModel = KotlinLogging.logger("FotoFilterViewModel")
    val imageUtils = KotlinLogging.logger("ImageUtils")
    val cacheService = KotlinLogging.logger("ThumbnailCacheService")
    val persistenceService = KotlinLogging.logger("SelectionPersistenceService")
    val fileService = KotlinLogging.logger("FileService")
    val ui = KotlinLogging.logger("UI")
}
