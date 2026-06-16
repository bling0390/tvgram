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
        if (_loaded.value && _items.value.isNotEmpty()) {
            Log.i(TAG, "loadAllChats: already loaded (${_items.value.size}); skipping")
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
        _items.value = items
        _loaded.value = true
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
        return ChatItem(
            id = chatId,
            title = title,
            type = type,
            unreadCount = unread,
            lastMessageText = null,
        )
    }

    companion object {
        private const val TAG = "TdChatRepo"
    }
}
