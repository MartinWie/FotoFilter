package models

import java.util.UUID

data class Photo(
    val id: String = UUID.randomUUID().toString(),
    val rawPath: String,
    val jpegPath: String? = null,
    val fileName: String,
    val dateCreated: Long,
    var status: PhotoStatus = PhotoStatus.UNDECIDED
)

enum class PhotoStatus {
    UNDECIDED, KEEP, DISCARD
}
