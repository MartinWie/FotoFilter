package models

data class Photo(
    val rawPath: String,
    val jpegPath: String? = null,
    val fileName: String,
    val dateCreated: Long,
    var status: PhotoStatus = PhotoStatus.UNDECIDED
) {
    // Use the file path as unique identifier instead of generating UUIDs
    val id: String get() = rawPath
}

enum class PhotoStatus {
    UNDECIDED, KEEP, DISCARD
}
