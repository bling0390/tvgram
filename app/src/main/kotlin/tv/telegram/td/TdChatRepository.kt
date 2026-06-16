package tv.telegram.td

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * TdChatRepository — drives the chat list.
 *
 * Flow:
 *   1. Wait for AuthState.Ready
 *   2. Call loadChats() — sends loadChats + getChats
 *   3. The getChats response is a {"@type":"chats", "chat_ids":[...]} object
 *      We catch it via a `@extra` tag and parse the IDs
 *   4. For each chatId, send getChat, match response via @extra, project
 *
 * Triggered by:
 *   - authorizationStateReady
 *   - updateChatList
 *   - updateNewChat
 */
class TdChatRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    /**
     * The full chat list, only updated by loadAllChats().
     * When [_searchQuery] is non-blank, [_items] is the search result instead.
     */
    private val _allChats = MutableStateFlow<List<ChatItem>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // Per-call deferred promises for sendAndAwait()
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val pendingLock = Any()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatchUpdate(obj) }
        }
    }

    private fun dispatchUpdate(obj: JSONObject) {
        val type = obj.optString("@type")
        val extra = obj.optString("@extra", "")
        val expectedExtra = if (extra.startsWith("__req_")) extra else ""

        // 1) Match a pending request via @extra
        if (expectedExtra.isNotEmpty()) {
            val d = synchronized(pendingLock) { pending.remove(expectedExtra) }
            if (d != null) {
                d.complete(obj)
                return
            }
        }

        // 2) Lifecycle updates that should kick a re-load
        when (type) {
            "updateAuthorizationState" -> {
                val sub = obj.optJSONObject("authorization_state")?.optString("@type")
                if (sub == "authorizationStateReady") {
                    Log.i(TAG, "Auth Ready → loading chat list")
                    scope.launch { loadAllChats() }
                }
            }
            "updateNewChat", "updateChatPosition" -> {
                // Throttle: don't reload on every new chat, just refresh
                if (_loaded.value) {
                    Log.i(TAG, "Chat list change ($type) → refreshing")
                    scope.launch { loadAllChats() }
                }
            }
        }
    }

    /**
     * Send a request with a unique @extra, await its direct response.
     */
    private suspend fun sendAndAwait(
        query: JSONObject,
        timeoutMs: Long = 5_000L,
    ): JSONObject? {
        val extra = "__req_${java.util.concurrent.atomic.AtomicLong(1).getAndIncrement()}_${System.nanoTime()}__"
        query.put("@extra", extra)
        val deferred = CompletableDeferred<JSONObject>()
        synchronized(pendingLock) { pending[extra] = deferred }
        client.send(query)
        return withTimeoutOrNull(timeoutMs) {
            try { deferred.await() } catch (_: Throwable) { null }
        }
    }

    /**
     * Load all chats and project to ChatItems. Idempotent.
     */
    suspend fun loadAllChats(limit: Int = 200) {
        if (_loaded.value && _allChats.value.isNotEmpty()) {
            Log.i(TAG, "loadAllChats: already loaded (${_allChats.value.size}); skipping")
            return
        }
        Log.i(TAG, "loadAllChats: requesting top $limit chats")
        // loadChats to ensure the local cache is populated
        client.send(JSONObject().apply {
            put("@type", "loadChats")
            put("chat_list", JSONObject().apply { put("@type", "chatListMain") })
            put("limit", limit)
        })
        // getChats to receive the chat_id list
        val chatsResp = sendAndAwait(JSONObject().apply {
            put("@type", "getChats")
            put("chat_list", JSONObject().apply { put("@type", "chatListMain") })
            put("limit", limit)
        }, timeoutMs = 10_000L)
        if (chatsResp == null) {
            Log.w(TAG, "getChats timed out")
            return
        }
        if (chatsResp.optString("@type") != "chats") {
            Log.w(TAG, "getChats returned ${chatsResp.optString("@type")}")
            return
        }
        val chatIds = chatsResp.optJSONArray("chat_ids")
        if (chatIds == null || chatIds.length() == 0) {
            Log.i(TAG, "getChats returned empty list")
            _loaded.value = true
            return
        }
        val ids = (0 until chatIds.length()).map { chatIds.getLong(it) }
        Log.i(TAG, "getChats returned ${ids.size} chat IDs; fetching each")

        // Fetch each chat in parallel-ish (sequential await is fine for 200 items)
        val items = ids.mapNotNull { fetchChatItem(it) }
        Log.i(TAG, "Projected to ${items.size} ChatItems")
        _allChats.value = items
        _loaded.value = true
        // If we have an active search query, re-run it against the new full list.
        // (TDLib searchChats is offline but uses its own local cache; on first load
        //  the cache may not yet be populated. Falling back to in-memory filter
        //  is a safer UX: typing after the list loads gives instant results.)
        if (_searchQuery.value.isNotEmpty()) {
            applyFilter(_searchQuery.value)
        } else {
            _items.value = items
        }
    }

    /**
     * Set the current search query. If non-blank, [_items] shows the matching
     * chats. If blank, [_items] shows the full list again.
     *
     * Uses TDLib's `searchChats` (offline, instant). Falls back to in-memory
     * filter if TDLib returns nothing (the local index can lag on first load).
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
            val resp = sendAndAwait(JSONObject().apply {
                put("@type", "searchChats")
                put("query", query)
                put("limit", 50)
            }, timeoutMs = 3_000L)
            if (resp == null) {
                Log.w(TAG, "searchChats($query) timed out; falling back to in-memory filter")
                applyFilter(query)
                return
            }
            if (resp.optString("@type") != "chats") {
                Log.w(TAG, "searchChats returned ${resp.optString("@type")}; falling back")
                applyFilter(query)
                return
            }
            val ids = resp.optJSONArray("chat_ids")
            if (ids == null || ids.length() == 0) {
                Log.i(TAG, "searchChats($query): 0 hits from TDLib; using in-memory fallback")
                // If the local cache isn't loaded yet, wait for it (up to 5s).
                // The user already typed a query, so they want results.
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
            val chatIds = (0 until ids.length()).map { ids.getLong(it) }
            // TDLib returns in chat-list order. We fetch each ChatItem; if it's
            // not in _allChats yet, fetchChatItem() will block on a getChat() call.
            val results = chatIds.mapNotNull { id ->
                _allChats.value.firstOrNull { it.id == id } ?: fetchChatItem(id)
            }
            // De-dup by id (in case the in-memory list also matches)
            val seen = results.map { it.id }.toHashSet()
            val merged = results + _allChats.value
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
        val resp = sendAndAwait(JSONObject().apply {
            put("@type", "getChat")
            put("chat_id", chatId)
        }, timeoutMs = 5_000L) ?: run {
            Log.w(TAG, "getChat($chatId) timed out")
            return null
        }
        if (resp.optString("@type") != "chat") {
            Log.w(TAG, "getChat($chatId) returned ${resp.optString("@type")}")
            return null
        }
        val title = resp.optString("title").ifEmpty { "Unnamed chat" }
        val unread = resp.optInt("unread_count", 0)
        // chat.last_message is the full Message; .date is epoch seconds.
        // 0 if there's never been a message in the chat.
        val lastMessageDate = resp.optJSONObject("last_message")?.optInt("date", 0) ?: 0
        val typeObj = resp.optJSONObject("type")
        val typeStr = typeObj?.optString("@type") ?: "unknown"

        val type = when (typeStr) {
            "chatTypePrivate", "chatTypeSecret" -> ChatType.Private
            "chatTypeBasicGroup" -> ChatType.Group
            "chatTypeSupergroup" -> {
                val isChannel = typeObj?.optBoolean("is_channel", false) ?: false
                if (isChannel) ChatType.Channel else ChatType.Group
            }
            "chatTypeSavedMessages" -> ChatType.SavedMessages
            else -> ChatType.Unknown
        }

        // Pull chat photo small id for the avatar (smallest size with file)
        val photo = resp.optJSONObject("photo")
        var small: Int? = null
        if (photo != null) {
            val arr = photo.optJSONArray("sizes")
            if (arr != null) {
                var best: JSONObject? = null
                var bestW = Int.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    if (s.optJSONObject("photo") == null) continue
                    val w = s.optInt("width", 0)
                    if (w in 1..100 && w < bestW) { best = s; bestW = w }
                }
                if (best != null) small = best.optJSONObject("photo")?.optInt("id")
            }
        }

        return ChatItem(
            id = chatId,
            title = title,
            type = type,
            unreadCount = unread,
            lastMessageText = null,
            lastMessageDate = lastMessageDate,
            photoSmallFileId = small,
        )
    }

    companion object {
        private const val TAG = "TdChatRepo"
    }
}
