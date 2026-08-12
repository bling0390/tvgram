package tv.telegram.td

enum class MediaType { Photo, Video, Animation, Unknown }

data class MediaItem(
    val messageId: Long,
    val type: MediaType,
    val fileId: Int,
    val thumbnailFileId: Int? = null,
    val localPath: String? = null,
    val thumbnailLocalPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String? = null,
    val date: Int = 0,
    val chatId: Long = 0,
)
