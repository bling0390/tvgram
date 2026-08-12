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

/**
 * Top-level ViewModel for MainActivity. Owns auth, chatRepo, mediaRepo,
 * fileRepo. Exposes StateFlows the Compose tree collects.
 */
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

    /** True during realSignOut() until TDLib reaches WaitQrCode/Error. */
    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    /**
     * Decoupled from signingOut so the banner slide-out tween (250ms)
     * finishes before AppRoot swaps the screen. The 350ms gap covers
     * the 250ms tween + 100ms buffer.
     */
    private val _showSignOutBanner = MutableStateFlow(false)
    val showSignOutBanner: StateFlow<Boolean> = _showSignOutBanner.asStateFlow()

    /**
     * Hold for 500ms after Ready so the "Signing in…" overlay's exit tween
     * (250ms) can play before AppRoot swaps to HomeScreen. Longer than
     * sign-out 350ms because WaitQrCode → Ready is typically a single
     * transition with no visible intermediate state.
     */
    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    val searchQuery = chatRepo.searchQuery
    val searchSearching = chatRepo.searching

    /** D-031. Cache cleanup is explicit user action (Settings → 清理缓存),
     *  not part of realSignOut. null = idle, 0..1 = in progress, 1 = finished. */
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

    // title cache: chatId -> title
    private val _chatTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatTitles: StateFlow<Map<Long, String>> = _chatTitles.asStateFlow()

    private val _currentChatTitle = MutableStateFlow<String?>(null)
    val currentChatTitle: StateFlow<String?> = _currentChatTitle.asStateFlow()

    // NOTE (D-033): the player's current media index is NOT held here
    // anymore. It lives in the NavHost route as "player/{index}", so the
    // back stack is the single source of truth for "is the player open".
    // This kills the stale-playerIndex bug where a session kicked to
    // Closed left the index behind and re-login auto-reopened the player.

    // Playback speed (1.0 = normal). Cycles through [1.0, 1.25, 1.5, 2.0].
    private val _playerPlaybackSpeed = MutableStateFlow(1.0f)
    val playerPlaybackSpeed: StateFlow<Float> = _playerPlaybackSpeed.asStateFlow()

    // Resumed positions per fileId (ms). In-memory only for v0.7.0.
    private val _playerResumePositions = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val playerResumePositions: StateFlow<Map<Int, Long>> = _playerResumePositions.asStateFlow()

    enum class NavSection { Search, Chats, Settings }

    private val _navSection = MutableStateFlow(NavSection.Chats)
    val navSection: StateFlow<NavSection> = _navSection.asStateFlow()

    /** null = no chat selected. Separate from mediaRepo.currentChatId (sidebar selection vs deep chat). */
    private val _sidebarSelectedChatId = MutableStateFlow<Long?>(null)
    val sidebarSelectedChatId: StateFlow<Long?> = _sidebarSelectedChatId.asStateFlow()

    /** In-memory mirrors; persistence handled by SettingsRepository. */
    private val _themeMode = MutableStateFlow(ThemeMode.Dark)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(Language.English)
    val language: StateFlow<Language> = _language.asStateFlow()

    // v0.9.0: TDLib getMe result, refreshed on auth Ready
    private val _currentUser = MutableStateFlow<TdUser?>(null)
    val currentUser: StateFlow<TdUser?> = _currentUser.asStateFlow()

    fun selectNavSection(section: NavSection) {
        _navSection.value = section
    }

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

    /** Pre-D-031 logout. Kept for backward compat; the auth state going non-Ready is what routes us back to QrLoginScreen. */
    fun logout() {
        closeChat()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        auth.cancelQrLogin()
    }

    /** v1.0.0 real sign-out. [TdClient.realSignOut] wipes DB only (not file cache — D-031). */
    fun realSignOut() {
        closeChat()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        _showSignOutBanner.value = true
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

    /** v0.9.0: fetch the current TG user via TDLib getMe. */
    fun refreshMe() {
        viewModelScope.launch {
            val user = auth.getMe()
            _currentUser.value = user
        }
    }

    /** Cycle the playback speed: 1.0 → 1.25 → 1.5 → 2.0 → 1.0. */
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
        // Hydrate settings from SharedPreferences
        val (theme, lang) = SettingsRepository.hydrate(getApplication())
        _themeMode.value = theme
        _language.value = lang
        SettingsRepository.applyLocale(getApplication(), lang)
        // Whenever auth hits Ready, fetch the current user
        viewModelScope.launch {
            auth.state.collect { st ->
                if (st is AuthState.Ready) refreshMe()
            }
        }

        // D-033 stale-state guard: any transition Ready → non-Ready
        // (session revoked on another device → Closed, Error, …) must
        // clear the chat-selection state. The old code only cleared it in
        // logout()/realSignOut(); a passive kick left sidebarSelectedChatId
        // alive, and re-login would land on a stale chat. (The player
        // index can't go stale anymore — it lives in the NavHost route.)
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
        // After WaitQrCode/Error, clear banner then delay 350ms before
        // clearing signingOut (lets banner tween finish).
        viewModelScope.launch {
            auth.state.collect { st ->
                if (st is AuthState.WaitQrCode || st is AuthState.Error) {
                    _showSignOutBanner.value = false
                    delay(350)
                    _signingOut.value = false
                }
            }
        }

        // Drive signingIn so AppRoot can show the "Signing in…" Message
        // overlay above QrLoginScreen. The 500ms hold on Ready gives the
        // Message exit tween time to play before HomeScreen swaps in.
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
