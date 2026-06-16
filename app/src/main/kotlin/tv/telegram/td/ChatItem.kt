package tv.telegram.td

import android.graphics.Bitmap

/**
 * ChatType — classification of a TDLib Chat for our 3-tab layout.
 *
 *   Channel    → public/supergroup with `isChannel == true` and a single
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
 *   photo          → loaded async; null until downloaded
 */
data class ChatItem(
    val id: Long,
    val title: String,
    val type: ChatType,
    val unreadCount: Int,
    val lastMessageText: String?,
    val photo: Bitmap? = null,
)
