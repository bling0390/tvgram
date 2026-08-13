package tv.telegram.td

import android.graphics.Bitmap

enum class ChatType { Channel, Group, Private, SavedMessages, Unknown }

data class ChatItem(
    val id: Long,
    val title: String,
    val type: ChatType,
    val unreadCount: Int,
    val lastMessageText: String?,
    val lastMessageDate: Int = 0,
    val lastMessageThumbFileId: Int? = null,
    val photoSmallFileId: Int? = null,
    val photoBigFileId: Int? = null,
    val photo: Bitmap? = null,
)
