package tv.telegram.td

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi

class TdChatRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _allChats = MutableStateFlow<List<ChatItem>>(emptyList())

    private val _archiveChats = MutableStateFlow<List<ChatItem>>(emptyList())
    val archiveChats: StateFlow<List<ChatItem>> = _archiveChats.asStateFlow()

    private val _archiveCount = MutableStateFlow(0)
    val archiveCount: StateFlow<Int> = _archiveCount.asStateFlow()

    private val _viewingArchive = MutableStateFlow(false)
    val viewingArchive: StateFlow<Boolean> = _viewingArchive.asStateFlow()

    fun setViewingArchive(value: Boolean) {
        _viewingArchive.value = value
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatchUpdate(obj) }
        }
    }

    private fun dispatchUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                if (obj.authorizationState is TdApi.AuthorizationStateReady) {
                    Log.i(TAG, "Auth Ready → loading chat list")
                    scope.launch { loadAllChats() }
                    scope.launch { loadArchiveChats() }
                }
            }
            is TdApi.UpdateNewChat, is TdApi.UpdateChatPosition -> {
                if (_loaded.value) {
                    Log.i(TAG, "Chat list change (${obj.javaClass.simpleName}) → refreshing")
                    scope.launch { loadAllChats() }
                    scope.launch { loadArchiveChats() }
                }
            }
            is TdApi.UpdateChatReadInbox -> {
                applyUnreadCount(obj.chatId, obj.unreadCount)
            }
            is TdApi.UpdateChatNotificationSettings -> {
                applyMuted(obj.chatId, obj.notificationSettings)
            }
            else -> {  }
        }
    }

    /**
     * Live-update the unread count for a chat without a full list reload.
     * Only the dot indicator (present/absent) is rendered from this value,
     * so we just patch the matching ChatItem in every published list.
     */
    private fun applyUnreadCount(chatId: Long, count: Int) {
        fun patch(list: List<ChatItem>): List<ChatItem> =
            list.map { if (it.id == chatId) it.copy(unreadCount = count) else it }
        _allChats.value = patch(_allChats.value)
        _archiveChats.value = patch(_archiveChats.value)
        _items.value = patch(_items.value)
    }

    /** Patch a chat's muted state from a notification-settings update. */
    private fun applyMuted(chatId: Long, settings: TdApi.ChatNotificationSettings) {
        val muted = isMuted(settings)
        fun patch(list: List<ChatItem>): List<ChatItem> =
            list.map { if (it.id == chatId) it.copy(isMuted = muted) else it }
        _allChats.value = patch(_allChats.value)
        _archiveChats.value = patch(_archiveChats.value)
        _items.value = patch(_items.value)
    }

    private fun isMuted(settings: TdApi.ChatNotificationSettings): Boolean =
        !settings.useDefaultMuteFor && settings.muteFor > 0

    suspend fun loadAllChats(limit: Int = 200) {
        if (_loaded.value && _allChats.value.isNotEmpty()) {
            Log.d(TAG, "loadAllChats: already loaded (${_allChats.value.size}); skipping")
            return
        }
        Log.i(TAG, "loadAllChats: requesting top $limit chats")
        _error.value = null

        client.send(TdApi.LoadChats(TdApi.ChatListMain(), limit))

        val chatsObj = client.execute(
            TdApi.GetChats(TdApi.ChatListMain(), limit),
            timeoutMs = 10_000L,
        )
        if (chatsObj !is TdApi.Chats) {
            val msg = when {
                chatsObj == null -> "Loading timed out. Press OK to retry."
                chatsObj is TdApi.Error -> "${chatsObj.code}: ${chatsObj.message}"
                else -> "Unexpected response: ${chatsObj.javaClass.simpleName}"
            }
            Log.w(TAG, "getChats failed: $msg")
            _error.value = msg
            return
        }
        val ids = chatsObj.chatIds
        if (ids.isEmpty()) {
            Log.i(TAG, "getChats returned empty list")
            _loaded.value = true
            return
        }
        Log.i(TAG, "getChats returned ${ids.size} chat IDs; fetching each")
        val items: List<ChatItem> = ids.toList().mapNotNull { id: Long -> fetchChatItem(id) }
        Log.i(TAG, "Projected to ${items.size} ChatItems")
        _allChats.value = items
        _loaded.value = true
        if (_searchQuery.value.isNotEmpty()) {
            applyFilter(_searchQuery.value)
        } else {
            _items.value = items
        }
    }

    suspend fun loadArchiveChats(limit: Int = 100) {
        try {
            Log.i(TAG, "loadArchiveChats: requesting top $limit archived chats")
            client.send(TdApi.LoadChats(TdApi.ChatListArchive(), limit))
            val chatsObj = client.execute(
                TdApi.GetChats(TdApi.ChatListArchive(), limit),
                timeoutMs = 10_000L,
            )
            if (chatsObj !is TdApi.Chats) {
                Log.w(TAG, "getChats(archive) returned ${chatsObj?.javaClass?.simpleName ?: "null"}")
                return
            }
            val ids = chatsObj.chatIds
            val items: List<ChatItem> = ids.toList().mapNotNull { id: Long -> fetchChatItem(id) }
            _archiveChats.value = items
            _archiveCount.value = items.size
            Log.i(TAG, "Loaded ${items.size} archived chats")
        } catch (e: Throwable) {
            Log.w(TAG, "loadArchiveChats failed", e)
        }
    }

    fun setSearchQuery(query: String) {
        val q = query.trim()
        if (q == _searchQuery.value) return
        _searchQuery.value = q
        if (q.isEmpty()) {
            _items.value = _allChats.value
            return
        }
        _searching.value = true
        scope.launch { runSearch(q) }
    }

    private suspend fun runSearch(query: String) {
        try {
            val resp = client.execute(
                TdApi.SearchChats(query, 50),
                timeoutMs = 3_000L,
            )
            if (resp !is TdApi.Chats) {
                Log.w(TAG, "searchChats($query) returned ${resp?.javaClass?.simpleName}; falling back")
                applyFilter(query)
                return
            }
            val ids = resp.chatIds
            if (ids.isEmpty()) {
                Log.i(TAG, "searchChats($query): 0 hits; using in-memory fallback")
                if (_allChats.value.isEmpty() && !_loaded.value) {
                    Log.d(TAG, "runSearch: waiting for loadAllChats to populate _allChats")
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (_allChats.value.isEmpty() && System.currentTimeMillis() < deadline) {
                        kotlinx.coroutines.delay(100L)
                    }
                }
                applyFilter(query)
                return
            }
            val results: List<ChatItem> = ids.toList().mapNotNull { id: Long ->
                _allChats.value.firstOrNull { it.id == id } ?: fetchChatItem(id)
            }
            val seen: HashSet<Long> = results.map { it.id }.toHashSet()
            val merged: List<ChatItem> = results + _allChats.value
                .filter { it.id !in seen && it.title.contains(query, ignoreCase = true) }
            _items.value = merged
        } finally {
            _searching.value = false
        }
    }

    private fun applyFilter(query: String) {
        _items.value = _allChats.value.filter {
            it.title.contains(query, ignoreCase = true)
        }
    }

    private suspend fun fetchChatItem(chatId: Long): ChatItem? {
        val resp = client.execute(TdApi.GetChat(chatId), timeoutMs = 5_000L) ?: run {
            Log.w(TAG, "getChat($chatId) timed out")
            return null
        }
        if (resp !is TdApi.Chat) {
            Log.w(TAG, "getChat($chatId) returned ${resp.javaClass.simpleName}")
            return null
        }
        val title = resp.title.ifEmpty { "Unnamed chat" }
        val unread = resp.unreadCount
        val muted = resp.notificationSettings?.let { isMuted(it) } ?: false
        val lastMessageText = resp.lastMessage?.let { messageText(it) }
        val lastMessageThumbFileId = resp.lastMessage?.let { messageThumbFileId(it) }
        val lastMessageDate = resp.lastMessage?.date ?: 0

        val type = when (val t = resp.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> ChatType.Private
            is TdApi.ChatTypeBasicGroup -> ChatType.Group
            is TdApi.ChatTypeSupergroup ->
                if (t.isChannel) ChatType.Channel else ChatType.Group
            else -> ChatType.Unknown
        }

        val photoSmallFileId: Int? = resp.photo?.small?.id

        return ChatItem(
            id = chatId,
            title = title,
            type = type,
            unreadCount = unread,
            isMuted = muted,
            lastMessageText = lastMessageText,
            lastMessageDate = lastMessageDate,
            lastMessageThumbFileId = lastMessageThumbFileId,
            photoSmallFileId = photoSmallFileId,
        )
    }

    /**
     * Summary for the chat list second line. tvgram is a photo/video-focused
     * TV app, so only media messages produce a summary; everything else
     * returns null and the UI falls back to the chat type name.
     */
    private fun messageText(message: TdApi.Message): String? = when (val c = message.content) {
        is TdApi.MessagePhoto -> "Photo"
        is TdApi.MessageVideo -> "Video"
        else -> null
    }

    /** Thumbnail file id of the last media message, for the small preview before the summary text. */
    private fun messageThumbFileId(message: TdApi.Message): Int? = when (val c = message.content) {
        is TdApi.MessagePhoto -> c.photo.sizes
            .filter { it.photo.id != 0 }
            .minByOrNull { it.width * it.height }
            ?.photo?.id
        is TdApi.MessageVideo -> c.video.thumbnail?.file?.id
        else -> null
    }

    companion object {
        private const val TAG = "TdChatRepo"
    }
}
