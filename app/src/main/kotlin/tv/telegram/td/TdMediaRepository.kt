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
import java.util.concurrent.ConcurrentHashMap

/**
 * TdMediaRepository — load image/video messages for a chat.
 *
 * Flow:
 *   1. User picks a chat (chatId) from ChatListScreen
 *   2. We call openAndLoad(chatId), then getChatHistory(chatId, fromMessageId, offset, limit)
 *   3. For each message, check if it has .content.photo / .content.video
 *   4. If yes, project to MediaItem and append
 *   5. Continue paginating on demand via loadMore()
 *
 * v0.5.0: pagination. Page size = 100 messages. loadMore() fetches the next
 * older page using `from_message_id = <oldest_seen_id>`. Stops when
 * the server returns fewer than `limit` messages, or the response is empty.
 */
class TdMediaRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _currentChatId = MutableStateFlow<Long?>(null)
    val currentChatId: StateFlow<Long?> = _currentChatId.asStateFlow()

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * True once we've paginated and found no more messages.
     * Lets the UI hide the "load more" affordance.
     */
    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    private val pendingGet = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatch(obj) }
        }
    }

    private fun dispatch(obj: JSONObject) {
        val type = obj.optString("@type")
        val extra = obj.optString("@extra", "")
        if (extra.startsWith("__req_")) {
            val d = pendingGet.remove(extra)
            if (d != null) { d.complete(obj); return }
        }
        // New message in a visible chat → prepend
        if (type == "updateNewMessage" || type == "updateMessageContent") {
            scope.launch {
                val chatId = _currentChatId.value ?: return@launch
                val msg = obj.optJSONObject("message") ?: return@launch
                val item = parseMessage(msg, chatId) ?: return@launch
                if (item.messageId !in _items.value.map { it.messageId }) {
                    _items.value = listOf(item) + _items.value
                }
            }
        }
    }

    private suspend fun sendAndAwait(query: JSONObject, timeoutMs: Long = 10_000L): JSONObject? {
        val extra = "__req_${System.nanoTime()}_${query.optString("@type")}"
        query.put("@extra", extra)
        val deferred = CompletableDeferred<JSONObject>()
        pendingGet[extra] = deferred
        client.send(query)
        return withTimeoutOrNull(timeoutMs) {
            try { deferred.await() } catch (_: Throwable) { null }
        }
    }

    /**
     * Open a chat and load its most recent page of media. Resets state for a new chat.
     */
    suspend fun openAndLoad(chatId: Long, limit: Int = 100) {
        Log.i(TAG, "openAndLoad(chatId=$chatId, limit=$limit)")
        _currentChatId.value = chatId
        _items.value = emptyList()
        _loaded.value = false
        _exhausted.value = false
        _error.value = null

        // openChat — let TDLib know we're now actively viewing it
        client.send(JSONObject().apply {
            put("@type", "openChat")
            put("chat_id", chatId)
        })

        val resp = sendAndAwait(JSONObject().apply {
            put("@type", "getChatHistory")
            put("chat_id", chatId)
            put("from_message_id", 0L)
            put("offset", 0)
            put("limit", limit)
        })
        if (resp == null) {
            Log.w(TAG, "getChatHistory timed out")
            _error.value = "Loading timed out. Press OK to retry."
            _loaded.value = true
            return
        }
        if (resp.optString("@type") != "messages") {
            val errMsg = resp.optString("message").ifEmpty { resp.optString("@type") }
            Log.w(TAG, "getChatHistory returned ${resp.optString("@type")}: $errMsg")
            _error.value = "Error: $errMsg"
            _loaded.value = true
            return
        }
        val arr = resp.optJSONArray("messages") ?: org.json.JSONArray()
        val items = (0 until arr.length())
            .mapNotNull { i -> parseMessage(arr.getJSONObject(i), chatId) }
        Log.i(TAG, "Loaded ${items.size} media items from ${arr.length()} messages (page 1)")
        _items.value = items
        _loaded.value = true
        if (items.isEmpty() || arr.length() < limit) {
            // Either nothing to load, or the server already gave us everything
            _exhausted.value = true
        }
    }

    /**
     * Fetch the next page of older messages and append media items.
     * No-op if already loading, or if the current chat is exhausted.
     */
    suspend fun loadMore(limit: Int = 100) {
        if (_loadingMore.value) {
            Log.d(TAG, "loadMore: already in flight")
            return
        }
        val chatId = _currentChatId.value
        if (chatId == null) {
            Log.w(TAG, "loadMore: no current chat")
            return
        }
        if (_exhausted.value) {
            Log.d(TAG, "loadMore: already exhausted")
            return
        }
        val current = _items.value
        if (current.isEmpty()) {
            Log.w(TAG, "loadMore: no items yet; call openAndLoad first")
            return
        }
        // Oldest message id = the last one (TDLib returns newest-first).
        val oldestMessageId = current.last().messageId

        _loadingMore.value = true
        try {
            val resp = sendAndAwait(JSONObject().apply {
                put("@type", "getChatHistory")
                put("chat_id", chatId)
                put("from_message_id", oldestMessageId)
                put("offset", 0)
                put("limit", limit)
            })
            if (resp == null) {
                Log.w(TAG, "loadMore: getChatHistory timed out")
                return
            }
            if (resp.optString("@type") != "messages") {
                Log.w(TAG, "loadMore: unexpected type ${resp.optString("@type")}")
                return
            }
            val arr = resp.optJSONArray("messages") ?: org.json.JSONArray()
            val newItems = (0 until arr.length())
                .mapNotNull { i -> parseMessage(arr.getJSONObject(i), chatId) }
            Log.i(TAG, "loadMore: got ${newItems.size} media items from ${arr.length()} messages")
            // De-dup by messageId
            val seen = current.map { it.messageId }.toHashSet()
            val merged = current + newItems.filter { it.messageId !in seen }
            _items.value = merged
            if (newItems.isEmpty() || arr.length() < limit) {
                _exhausted.value = true
            }
        } finally {
            _loadingMore.value = false
        }
    }

    fun close() {
        val chatId = _currentChatId.value ?: return
        client.send(JSONObject().apply {
            put("@type", "closeChat")
            put("chat_id", chatId)
        })
        _currentChatId.value = null
        _items.value = emptyList()
        _loaded.value = false
        _error.value = null
    }

    private fun parseMessage(message: JSONObject, chatId: Long): MediaItem? {
        val id = message.optLong("id")
        val date = message.optInt("date", 0)
        val content = message.optJSONObject("content") ?: return null
        val type = content.optString("@type")
        val caption = content.optJSONObject("caption")?.optString("text")
        return when (type) {
            "messagePhoto" -> {
                val photo = content.optJSONObject("photo") ?: return null
                val largest = pickLargest(photo.optJSONArray("sizes")) ?: return null
                val photoObj = largest.optJSONObject("photo") ?: return null
                MediaItem(
                    messageId = id,
                    type = MediaType.Photo,
                    fileId = photoObj.optInt("id"),
                    thumbnailFileId = pickThumbnail(photo.optJSONArray("sizes"))?.optJSONObject("photo")?.optInt("id"),
                    width = largest.optInt("width", 0),
                    height = largest.optInt("height", 0),
                    caption = caption,
                    date = date,
                    chatId = chatId,
                )
            }
            "messageVideo" -> {
                val video = content.optJSONObject("video") ?: return null
                val thumb = video.optJSONObject("thumbnail")?.optJSONObject("file")
                val videoFile = video.optJSONObject("video") ?: return null
                MediaItem(
                    messageId = id,
                    type = MediaType.Video,
                    fileId = videoFile.optInt("id"),
                    thumbnailFileId = thumb?.optInt("id"),
                    width = video.optInt("width", 0),
                    height = video.optInt("height", 0),
                    caption = caption,
                    date = date,
                    chatId = chatId,
                )
            }
            "messageAnimation" -> {
                val anim = content.optJSONObject("animation") ?: return null
                val animFile = anim.optJSONObject("animation") ?: return null
                val thumb = anim.optJSONObject("thumbnail")?.optJSONObject("file")
                MediaItem(
                    messageId = id,
                    type = MediaType.Animation,
                    fileId = animFile.optInt("id"),
                    thumbnailFileId = thumb?.optInt("id"),
                    width = anim.optInt("width", 0),
                    height = anim.optInt("height", 0),
                    caption = caption,
                    date = date,
                    chatId = chatId,
                )
            }
            else -> null
        }
    }

    private fun pickLargest(sizes: org.json.JSONArray?): JSONObject? {
        if (sizes == null) return null
        var best: JSONObject? = null
        var bestArea = -1
        for (i in 0 until sizes.length()) {
            val s = sizes.getJSONObject(i)
            val w = s.optInt("width", 0)
            val h = s.optInt("height", 0)
            val area = w * h
            if (area > bestArea) { best = s; bestArea = area }
        }
        return best
    }

    private fun pickThumbnail(sizes: org.json.JSONArray?): JSONObject? {
        if (sizes == null) return null
        // Smallest photo size with a .photo.file
        var best: JSONObject? = null
        var bestArea = Int.MAX_VALUE
        for (i in 0 until sizes.length()) {
            val s = sizes.getJSONObject(i)
            if (s.optJSONObject("photo") == null) continue
            val w = s.optInt("width", 0)
            val h = s.optInt("height", 0)
            val area = w * h
            if (area > 0 && area < bestArea) { best = s; bestArea = area }
        }
        return best
    }

    companion object {
        private const val TAG = "TdMediaRepo"
    }
}
