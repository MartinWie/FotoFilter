package repositories

import models.Photo
import services.FileService

actual class PhotoRepository {
    private val fileService = FileService()

    actual suspend fun scanFolder(folderPath: String): List<Photo> {
        return fileService.scanFolder(folderPath)
    }

    actual suspend fun exportPhotos(photos: List<Photo>, destinationPath: String) {
        fileService.exportPhotos(photos, destinationPath)
    }
}
