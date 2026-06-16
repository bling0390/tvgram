package tv.telegram.td

/**
 * MediaType — what kind of media a TDLib message contains.
 * We only support photo + video in MVP.
 */
enum class MediaType { Photo, Video, Animation, Unknown }

/**
 * MediaItem — UI-friendly projection of a TDLib Message that contains
 * a photo or video.
 *
 * TDLib Message.Photo / Video objects have a .photo / .video field
 * referencing Photo / Video objects; those have sizes[] with various
 * resolution variants. We project to MediaItem by:
 *   - taking the largest photo or video
 *   - the file.remote.id is the download handle for getFile()
 *   - thumbnail also has file.remote.id (lower-res preview)
 *
 * @param messageId  TDLib message id (for back-fwd nav / context)
 * @param type       Photo or Video
 * @param localPath  null until TdFileRepository finishes download
 * @param thumbnailPath null until TdFileRepository downloads the thumb
 * @param width / height best-effort from the largest size
 * @param caption    optional message caption
 * @param date       epoch seconds
 * @param chatId     source chat (so we can build permalinks later)
 */
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
