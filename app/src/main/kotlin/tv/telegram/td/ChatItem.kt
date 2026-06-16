package tv.telegram.td

import android.graphics.Bitmap

/**
 * ChatType — classification of a TDLib Chat for our 3-tab layout.
 *
 *   Channel    → public/supergroup with `is_channel == true` and a single
 *                poster (channels) or open discussion (supergroup-as-channel)
 *   Group      → basic group or non-broadcast supergroup
 *   Private    → 1:1 chat
 *   SavedMessages → "Saved Messages" / "My Notes" (chat with self)
 */
enum class ChatType { Channel, Group, Private, SavedMessages, Unknown }

/**
 * ChatItem — the UI-friendly projection of a TDLib Chat + User.
 *
 * Hydration:
 *   id             → TDLib chatId
 *   title          → from chat.title (group/channel) or user.first_name+last_name
 *   type           → from chat.type (basicGroup/supergroup/private/secret)
 *   unreadCount    → from chat.unread_count
 *   lastMessageText→ best-effort preview of the most recent message
 *   photoSmallFileId → the small (≤100px) chat photo file_id for avatar
 *                      (null if no photo)
 *   photoBigFileId   → the large version (≥ 640px) for fullscreen
 *   photo           → loaded async; null until downloaded
 */
data class ChatItem(
    val id: Long,
    val title: String,
    val type: ChatType,
    val unreadCount: Int,
    val lastMessageText: String?,
    val photoSmallFileId: Int? = null,
    val photoBigFileId: Int? = null,
    val photo: Bitmap? = null,
)
