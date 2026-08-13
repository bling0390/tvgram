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

    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatch(obj) }
        }
    }

    private fun dispatch(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateNewMessage -> handleNewMessage(obj.message)

            else -> {  }
        }
    }

    private fun handleNewMessage(message: TdApi.Message) {
        val chatId = _currentChatId.value ?: return
        val item = parseMessage(message, chatId) ?: return
        if (item.messageId !in _items.value.map { it.messageId }) {
            _items.value = listOf(item) + _items.value
        }
    }

    suspend fun openAndLoad(chatId: Long, limit: Int = 100) {
        Log.i(TAG, "openAndLoad(chatId=$chatId, limit=$limit)")
        _currentChatId.value = chatId
        _items.value = emptyList()
        _loaded.value = false
        _exhausted.value = false
        _error.value = null

        client.send(TdApi.OpenChat(chatId))

        // Pure media pagination: search returns photo/video messages only,
        // no need to pull full history and filter. fromMessageId=0 starts
        // from the newest message; each later page advances the cursor.
        val resp = client.execute(
            TdApi.SearchChatMessages(
                chatId, "", null, 0L, 0, limit,
                TdApi.SearchMessagesFilterPhotoAndVideo(), 0L,
            ),
            timeoutMs = 10_000L,
        )
        if (resp == null) {
            Log.w(TAG, "searchChatMessages timed out")
            _error.value = "Loading timed out. Press OK to retry."
            _loaded.value = true
            return
        }
        if (resp !is TdApi.Messages) {
            val errMsg = (resp as? TdApi.Error)?.message ?: resp.javaClass.simpleName
            Log.w(TAG, "searchChatMessages returned ${resp.javaClass.simpleName}: $errMsg")
            _error.value = "Error: $errMsg"
            _loaded.value = true
            return
        }
        val items = resp.messages
            .mapNotNull { parseMessage(it, chatId) }
        Log.i(TAG, "Loaded ${items.size} media items from ${resp.messages.size} search results (page 1)")
        _items.value = items
        _loaded.value = true
        if (items.isEmpty() || resp.messages.size < limit) {
            _exhausted.value = true
        }

        // Opening a chat = reading it (TV OK is an explicit "I'm looking at
        // this chat" action, not a hover). Mark the newest message as viewed
        // so TDLib emits UpdateChatReadInbox → the unread dot clears.
        val newestId = resp.messages.firstOrNull()?.id
        if (newestId != null) {
            client.send(TdApi.ViewMessages(chatId, 0L, longArrayOf(newestId), true))
            Log.i(TAG, "openAndLoad: marked chat $chatId read (newest msg $newestId)")
        }
    }

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
        val oldestMessageId = current.last().messageId
        _loadingMore.value = true
        try {
            // Advance the search cursor past the oldest loaded media message.
            // offset=0 returns matches before fromMessageId; the seen-set below
            // dedupes just in case a message comes back twice.
            val resp = client.execute(
                TdApi.SearchChatMessages(
                    chatId, "", null, oldestMessageId, 0, limit,
                    TdApi.SearchMessagesFilterPhotoAndVideo(), 0L,
                ),
            )
            if (resp !is TdApi.Messages) {
                Log.w(TAG, "loadMore: unexpected type ${resp?.javaClass?.simpleName}")
                return
            }
            val newItems = resp.messages.mapNotNull { parseMessage(it, chatId) }
            Log.i(TAG, "loadMore: got ${newItems.size} media items from ${resp.messages.size} search results")
            val seen = current.map { it.messageId }.toHashSet()
            val merged = current + newItems.filter { it.messageId !in seen }
            _items.value = merged
            if (newItems.isEmpty() || resp.messages.size < limit) {
                _exhausted.value = true
            }
        } finally {
            _loadingMore.value = false
        }
    }

    fun close() {
        val chatId = _currentChatId.value ?: return
        client.send(TdApi.CloseChat(chatId))
        _currentChatId.value = null
        _items.value = emptyList()
        _loaded.value = false
        _error.value = null
    }

    private fun parseMessage(message: TdApi.Message, chatId: Long): MediaItem? {
        return when (val c = message.content) {
            is TdApi.MessagePhoto -> {
                val photo = c.photo
                val largest = pickLargest(photo.sizes) ?: return null
                MediaItem(
                    messageId = message.id,
                    type = MediaType.Photo,
                    fileId = largest.photo.id,
                    thumbnailFileId = pickSmallestPhoto(photo.sizes)?.photo?.id,
                    width = largest.width,
                    height = largest.height,
                    caption = c.caption.text,
                    date = message.date,
                    chatId = chatId,
                )
            }
            is TdApi.MessageVideo -> {
                val video = c.video
                val thumbFile = video.thumbnail?.file
                MediaItem(
                    messageId = message.id,
                    type = MediaType.Video,
                    fileId = video.video.id,
                    thumbnailFileId = thumbFile?.id,
                    width = video.width,
                    height = video.height,
                    caption = c.caption.text,
                    date = message.date,
                    chatId = chatId,
                )
            }
            is TdApi.MessageAnimation -> {
                val anim = c.animation
                val thumbFile = anim.thumbnail?.file
                MediaItem(
                    messageId = message.id,
                    type = MediaType.Animation,
                    fileId = anim.animation.id,
                    thumbnailFileId = thumbFile?.id,
                    width = anim.width,
                    height = anim.height,
                    caption = c.caption.text,
                    date = message.date,
                    chatId = chatId,
                )
            }
            else -> null
        }
    }

    private fun pickLargest(sizes: Array<TdApi.PhotoSize>): TdApi.PhotoSize? =
        sizes.maxByOrNull { it.width * it.height }

    private fun pickSmallestPhoto(sizes: Array<TdApi.PhotoSize>): TdApi.PhotoSize? =
        sizes.filter { it.photo.id != 0 }
            .minByOrNull { it.width * it.height }

    companion object {
        private const val TAG = "TdMediaRepo"
    }
}
