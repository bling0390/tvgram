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

/**
 * TdChatRepository — drives the chat list.
 *
 * Flow:
 *   1. Wait for AuthState.Ready
 *   2. Call loadAllChats() — sends loadChats + getChats
 *   3. The getChats response is a [TdApi.Chats] object with chat IDs.
 *   4. For each chatId, send getChat, project to ChatItem.
 *
 * Triggered by:
 *   - authorizationStateReady
 *   - updateChatList
 *   - updateNewChat
 *
 * v1.0.0 (D-029): migrated from JSON RPC to typed [TdApi] objects.
 */
class TdChatRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _allChats = MutableStateFlow<List<ChatItem>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

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
                }
            }
            is TdApi.UpdateNewChat, is TdApi.UpdateChatPosition -> {
                if (_loaded.value) {
                    Log.i(TAG, "Chat list change (${obj.javaClass.simpleName}) → refreshing")
                    scope.launch { loadAllChats() }
                }
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * Load all chats and project to ChatItems. Idempotent.
     */
    suspend fun loadAllChats(limit: Int = 200) {
        if (_loaded.value && _allChats.value.isNotEmpty()) {
            Log.d(TAG, "loadAllChats: already loaded (${_allChats.value.size}); skipping")
            return
        }
        Log.i(TAG, "loadAllChats: requesting top $limit chats")
        // loadChats to populate the local cache (TDLib sends updates as it loads)
        client.send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
        // getChats returns the current top of the chat list (offline)
        val chatsObj = client.execute(
            TdApi.GetChats(TdApi.ChatListMain(), limit),
            timeoutMs = 10_000L,
        )
        if (chatsObj !is TdApi.Chats) {
            Log.w(TAG, "getChats returned ${chatsObj?.javaClass?.simpleName ?: "null"}")
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

    /**
     * Set the current search query. If non-blank, [_items] shows the matching
     * chats. If blank, [_items] shows the full list again.
     */
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
        val lastMessageDate = resp.lastMessage?.date ?: 0

        val type = when (val t = resp.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> ChatType.Private
            is TdApi.ChatTypeBasicGroup -> ChatType.Group
            is TdApi.ChatTypeSupergroup ->
                if (t.isChannel) ChatType.Channel else ChatType.Group
            else -> ChatType.Unknown
        }

        // Pull the smallest photo size file_id for the avatar (≤ 100px wide).
        // ChatPhotoInfo has small/big directly; no sizes array here.
        val photoSmallFileId: Int? = resp.photo?.small?.id

        return ChatItem(
            id = chatId,
            title = title,
            type = type,
            unreadCount = unread,
            lastMessageText = null,
            lastMessageDate = lastMessageDate,
            photoSmallFileId = photoSmallFileId,
        )
    }

    companion object {
        private const val TAG = "TdChatRepo"
    }
}