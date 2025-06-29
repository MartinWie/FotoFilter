package models

data class PhotoLibrary(
    val photos: List<Photo> = emptyList(),
    val selectedIndex: Int = 0,
    val isLoading: Boolean = false,
    val folderPath: String? = null
) {
    val selectedPhoto: Photo?
        get() = photos.getOrNull(selectedIndex)

    val totalPhotos: Int
        get() = photos.size

    val keptPhotos: Int
        get() = photos.count { it.status == PhotoStatus.KEEP }

    val discardedPhotos: Int
        get() = photos.count { it.status == PhotoStatus.DISCARD }

    val remainingPhotos: Int
        get() = photos.count { it.status == PhotoStatus.UNDECIDED }
}
