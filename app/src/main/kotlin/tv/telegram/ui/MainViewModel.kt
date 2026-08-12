package tv.telegram.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import tv.telegram.BuildConfig
import tv.telegram.TgTvApp
import tv.telegram.td.AuthState
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.TdAuth
import tv.telegram.td.TdChatRepository
import tv.telegram.td.TdClient
import tv.telegram.td.TdFileRepository
import tv.telegram.td.TdMediaRepository
import tv.telegram.td.TdUser

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val auth = TdAuth(client = TdClient, scope = viewModelScope)
    val chatRepo = TdChatRepository(client = TdClient, scope = viewModelScope)
    val mediaRepo = TdMediaRepository(client = TdClient, scope = viewModelScope)
    val fileRepo = TdFileRepository(client = TdClient, scope = viewModelScope)

    val authState: StateFlow<AuthState> = auth.state
    val chatList = chatRepo.items
    val chatListLoaded = chatRepo.loaded
    val archiveChats = chatRepo.archiveChats
    val archiveCount = chatRepo.archiveCount
    val viewingArchive = chatRepo.viewingArchive

    fun setViewingArchive(value: Boolean) {
        chatRepo.setViewingArchive(value)
    }

    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    val searchQuery = chatRepo.searchQuery
    val searchSearching = chatRepo.searching

    val cacheClearProgress: StateFlow<Float?> = TdClient.cacheClearProgress
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<TgTvApp>()
            val dir = java.io.File(app.filesDir, "tdlib-files")
            _cacheSizeBytes.value = TdClient.cacheSize(dir.absolutePath)
        }
    }

    fun clearCache() {
        val app = getApplication<TgTvApp>()
        val dir = java.io.File(app.filesDir, "tdlib-files")
        TdClient.clearCache(dir.absolutePath)
    }

    fun resetCacheClearProgress() {
        TdClient.resetCacheClearProgress()
    }

    val mediaItems = mediaRepo.items
    val mediaLoaded = mediaRepo.loaded
    val mediaError = mediaRepo.error
    val mediaLoadingMore = mediaRepo.loadingMore
    val mediaExhausted = mediaRepo.exhausted
    val currentChatId = mediaRepo.currentChatId

    private val _chatTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatTitles: StateFlow<Map<Long, String>> = _chatTitles.asStateFlow()

    private val _currentChatTitle = MutableStateFlow<String?>(null)
    val currentChatTitle: StateFlow<String?> = _currentChatTitle.asStateFlow()

    private val _playerPlaybackSpeed = MutableStateFlow(1.0f)
    val playerPlaybackSpeed: StateFlow<Float> = _playerPlaybackSpeed.asStateFlow()

    private val _playerResumePositions = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val playerResumePositions: StateFlow<Map<Int, Long>> = _playerResumePositions.asStateFlow()

    private val _sidebarSelectedChatId = MutableStateFlow<Long?>(null)
    val sidebarSelectedChatId: StateFlow<Long?> = _sidebarSelectedChatId.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.Dark)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(Language.English)
    val language: StateFlow<Language> = _language.asStateFlow()

    private val _currentUser = MutableStateFlow<TdUser?>(null)
    val currentUser: StateFlow<TdUser?> = _currentUser.asStateFlow()

    fun selectSidebarChat(chatId: Long?) {
        _sidebarSelectedChatId.value = chatId
        if (chatId != null) {
            openChat(chatId)
        } else {
            closeChat()
        }
    }

    fun setTheme(mode: ThemeMode) {
        _themeMode.value = mode
        SettingsRepository.setTheme(getApplication(), mode)
    }

    fun setLanguage(lang: Language) {
        _language.value = lang
        SettingsRepository.setLanguage(getApplication(), lang)
    }

    fun logout() {
        closeChat()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        auth.cancelQrLogin()
    }

    fun realSignOut() {
        closeChat()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        _signingOut.value = true
        val app = getApplication<TgTvApp>()
        TdClient.realSignOut(
            context = app,
            apiId = BuildConfig.TG_API_ID,
            apiHash = BuildConfig.TG_API_HASH,
            databaseDirectory = java.io.File(app.filesDir, "tdlib").absolutePath,
            filesDirectory = java.io.File(app.filesDir, "tdlib-files").absolutePath,
        )
    }

    fun refreshMe() {
        viewModelScope.launch {
            val user = auth.getMe()
            _currentUser.value = user
        }
    }

    fun cyclePlayerSpeed(): Float {
        val next = when (_playerPlaybackSpeed.value) {
            1.0f  -> 1.25f
            1.25f -> 1.5f
            1.5f  -> 2.0f
            else  -> 1.0f
        }
        _playerPlaybackSpeed.value = next
        return next
    }

    fun savePlayerPosition(fileId: Int, positionMs: Long) {
        if (positionMs <= 0L) return
        _playerResumePositions.value =
            _playerResumePositions.value + (fileId to positionMs)
    }

    fun clearPlayerPosition(fileId: Int) {
        _playerResumePositions.value = _playerResumePositions.value - fileId
    }

    init {

        viewModelScope.launch {
            chatRepo.items.collect { items ->
                _chatTitles.value = items.associate { it.id to it.title }
            }
        }

        viewModelScope.launch {
            mediaRepo.currentChatId.collect { id ->
                _currentChatTitle.value = id?.let { _chatTitles.value[it] }
            }
        }

        val (theme, lang) = SettingsRepository.hydrate(getApplication())
        _themeMode.value = theme
        _language.value = lang
        SettingsRepository.applyLocale(getApplication(), lang)

        viewModelScope.launch {
            auth.state.collect { st ->
                if (st is AuthState.Ready) refreshMe()
            }
        }

        viewModelScope.launch {
            var wasReady = false
            auth.state.collect { st ->
                val isReady = st is AuthState.Ready
                if (wasReady && !isReady) {
                    Log.i("MainViewModel", "auth left Ready (${st.javaClass.simpleName}); clearing chat selection")
                    closeChat()
                    _sidebarSelectedChatId.value = null
                    _currentUser.value = null
                }
                wasReady = isReady
            }
        }

        viewModelScope.launch {
            auth.state.collect { st ->
                if (st is AuthState.WaitQrCode || st is AuthState.Error) {
                    _signingOut.value = false
                }
            }
        }

        viewModelScope.launch {
            var prev: AuthState? = null
            auth.state.collect { st ->
                if (prev is AuthState.WaitQrCode && st !is AuthState.WaitQrCode) {
                    _signingIn.value = true
                }
                if (st is AuthState.Ready && _signingIn.value) {
                    viewModelScope.launch {
                        delay(500)
                        _signingIn.value = false
                    }
                }
                if (st is AuthState.Error) {
                    _signingIn.value = false
                }
                prev = st
            }
        }
    }

    fun openChat(chatId: Long) {
        viewModelScope.launch { mediaRepo.openAndLoad(chatId) }
    }

    fun loadMoreMedia() {
        viewModelScope.launch { mediaRepo.loadMore() }
    }

    fun closeChat() {
        mediaRepo.close()
    }

    fun setSearchQuery(query: String) {
        chatRepo.setSearchQuery(query)
    }

    fun fileStateFor(fileId: Int): FileDownloadState? = fileRepo.stateFor(fileId)

    fun ensureMediaFile(fileId: Int, priority: Int = 16) {
        viewModelScope.launch { fileRepo.ensureLocal(fileId, priority) }
    }

    fun currentChatTitle(id: Long): String? = _chatTitles.value[id]
}
