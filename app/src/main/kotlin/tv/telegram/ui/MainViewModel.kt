package tv.telegram.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.telegram.td.AuthState
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.TdAuth
import tv.telegram.td.TdChatRepository
import tv.telegram.td.TdClient
import tv.telegram.td.TdFileRepository
import tv.telegram.td.TdMediaRepository

/**
 * MainViewModel — top-level ViewModel for MainActivity.
 *
 * Owns:
 *   - auth      (TDLib authorization state machine)
 *   - chatRepo  (chat list loader)
 *   - mediaRepo (per-chat media loader)
 *   - fileRepo  (download manager for chat photos + media files)
 *
 * Exposes StateFlows the Compose tree collects.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val auth = TdAuth(client = TdClient, scope = viewModelScope)
    val chatRepo = TdChatRepository(client = TdClient, scope = viewModelScope)
    val mediaRepo = TdMediaRepository(client = TdClient, scope = viewModelScope)
    val fileRepo = TdFileRepository(client = TdClient, scope = viewModelScope)

    val authState: StateFlow<AuthState> = auth.state
    val chatList = chatRepo.items
    val chatListLoaded = chatRepo.loaded

    val mediaItems = mediaRepo.items
    val mediaLoaded = mediaRepo.loaded
    val currentChatId = mediaRepo.currentChatId

    // title cache: chatId -> title (populated when ChatListScreen fetches them)
    private val _chatTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatTitles: StateFlow<Map<Long, String>> = _chatTitles.asStateFlow()

    private val _currentChatTitle = MutableStateFlow<String?>(null)
    val currentChatTitle: StateFlow<String?> = _currentChatTitle.asStateFlow()

    init {
        // Mirror chat list → chatTitles
        viewModelScope.launch {
            chatRepo.items.collect { items ->
                _chatTitles.value = items.associate { it.id to it.title }
            }
        }
        // Mirror current chat id → title
        viewModelScope.launch {
            mediaRepo.currentChatId.collect { id ->
                _currentChatTitle.value = id?.let { _chatTitles.value[it] }
            }
        }
        // Boot QR
        viewModelScope.launch { auth.requestQrLogin() }
    }

    fun openChat(chatId: Long) {
        viewModelScope.launch { mediaRepo.openAndLoad(chatId) }
    }

    fun closeChat() {
        mediaRepo.close()
    }

    fun fileStateFor(fileId: Int): FileDownloadState? = fileRepo.stateFor(fileId)

    fun ensureMediaFile(fileId: Int, priority: Int = 16) {
        viewModelScope.launch { fileRepo.ensureLocal(fileId, priority) }
    }

    fun currentChatTitle(id: Long): String? = _chatTitles.value[id]
}
