package repositories

import models.Photo

expect class PhotoRepository {
    suspend fun scanFolder(folderPath: String): List<Photo>
    suspend fun exportPhotos(photos: List<Photo>, destinationPath: String)
}
