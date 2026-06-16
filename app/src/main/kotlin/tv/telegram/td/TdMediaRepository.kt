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
 *   2. We call openChat(chatId), then getChatHistory(chatId, fromMessageId, offset, limit)
 *   3. For each message, check if it has .content.photo / .content.video
 *   4. If yes, project to MediaItem and append
 *   5. Continue paginating until no more messages
 *
 * MVP: load the most recent 100 messages and filter to media-only.
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
        // New message in a visible chat → append
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
     * Open a chat and load its media. Resets state for a new chat.
     */
    suspend fun openAndLoad(chatId: Long, limit: Int = 200) {
        Log.i(TAG, "openAndLoad(chatId=$chatId)")
        _currentChatId.value = chatId
        _items.value = emptyList()
        _loaded.value = false

        // openChat — let TDLib know we're now actively viewing it
        client.send(JSONObject().apply {
            put("@type", "openChat")
            put("chat_id", chatId)
        })

        // getChatHistory with offset=0, from_message_id=0 → most recent `limit` messages
        val resp = sendAndAwait(JSONObject().apply {
            put("@type", "getChatHistory")
            put("chat_id", chatId)
            put("from_message_id", 0L)
            put("offset", 0)
            put("limit", limit)
        })
        if (resp == null) {
            Log.w(TAG, "getChatHistory timed out")
            _loaded.value = true
            return
        }
        if (resp.optString("@type") != "messages") {
            Log.w(TAG, "getChatHistory returned ${resp.optString("@type")}")
            _loaded.value = true
            return
        }
        val arr = resp.optJSONArray("messages") ?: org.json.JSONArray()
        val items = (0 until arr.length())
            .mapNotNull { i -> parseMessage(arr.getJSONObject(i), chatId) }
        Log.i(TAG, "Loaded ${items.size} media items from ${arr.length()} messages")
        _items.value = items
        _loaded.value = true
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
