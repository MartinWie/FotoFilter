package models

data class Photo(
    val rawPath: String? = null,
    val jpegPath: String? = null,
    val fileName: String,
    val dateCreated: Long,
    var status: PhotoStatus = PhotoStatus.UNDECIDED
) {
    // Use the first available file path as unique identifier
    val id: String get() = rawPath ?: jpegPath ?: fileName

    // Get the primary file path (RAW if available, otherwise processed image)
    val primaryPath: String get() = rawPath ?: jpegPath ?: ""

    // Check if this photo has any files
    val hasFiles: Boolean get() = rawPath != null || jpegPath != null
}

enum class PhotoStatus {
    UNDECIDED, KEEP, DISCARD
}
