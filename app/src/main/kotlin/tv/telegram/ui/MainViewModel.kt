package tv.telegram.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
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
    val searchQuery = chatRepo.searchQuery
    val searchSearching = chatRepo.searching

    val mediaItems = mediaRepo.items
    val mediaLoaded = mediaRepo.loaded
    val mediaError = mediaRepo.error
    val mediaLoadingMore = mediaRepo.loadingMore
    val mediaExhausted = mediaRepo.exhausted
    val currentChatId = mediaRepo.currentChatId

    // title cache: chatId -> title (populated when ChatListScreen fetches them)
    private val _chatTitles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatTitles: StateFlow<Map<Long, String>> = _chatTitles.asStateFlow()

    private val _currentChatTitle = MutableStateFlow<String?>(null)
    val currentChatTitle: StateFlow<String?> = _currentChatTitle.asStateFlow()

    // ── Player state (v0.7.0) ──────────────────────────────────────
    // Index into mediaItems of the video currently being played in
    // the dedicated PlayerScreen. null = not in player. Photos don't
    // open the player — they stay in FullScreenMedia.
    private val _playerMediaIndex = MutableStateFlow<Int?>(null)
    val playerMediaIndex: StateFlow<Int?> = _playerMediaIndex.asStateFlow()

    // Playback speed (1.0 = normal). Cycles through [1.0, 1.25, 1.5, 2.0].
    private val _playerPlaybackSpeed = MutableStateFlow(1.0f)
    val playerPlaybackSpeed: StateFlow<Float> = _playerPlaybackSpeed.asStateFlow()

    // Resumed positions per fileId (ms). In-memory only for v0.7.0.
    private val _playerResumePositions = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val playerResumePositions: StateFlow<Map<Int, Long>> = _playerResumePositions.asStateFlow()

    // ── Nav rail state (v0.8.0) ──────────────────────────────────
    // Which top-level section is currently visible. Search / Chats / Settings.
    // NavRail order is Search -> Chats -> Settings, but Chats is the primary
    // action so it gets initial focus.
    enum class NavSection { Search, Chats, Settings }

    private val _navSection = MutableStateFlow(NavSection.Chats)
    val navSection: StateFlow<NavSection> = _navSection.asStateFlow()

    // Which chat is currently selected in the Chats module's left sidebar.
    // null = no chat selected (right pane shows placeholder).
    // Separate from mediaRepo.currentChatId (which is the deep chat the
    // media grid is loading); sidebar selection is the user-facing pick.
    private val _sidebarSelectedChatId = MutableStateFlow<Long?>(null)
    val sidebarSelectedChatId: StateFlow<Long?> = _sidebarSelectedChatId.asStateFlow()

    // ── Settings state (v0.8.0) ───────────────────────────────
    // Stored in SharedPreferences. v0.8.0 keeps both as in-memory StateFlow
    // mirrors; persistence is handled by [SettingsRepository].
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

    /** Logout: clear TDLib session + nav back to login. v0.8.0 just clears
     *  sidebar selection and player index; the auth state going non-Ready
     *  is what routes us back to QrLoginScreen. */
    fun logout() {
        closeChat()
        closePlayer()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        auth.cancelQrLogin()
    }

    /** v0.9.0 real sign-out: stop the TDLib process, wipe on-disk state,
     *  restart TDLib, request a fresh QR login. The TdClient.startWithPaths
     *  call inside this function re-creates the database and files dirs.
     */
    fun realSignOut() {
        closeChat()
        closePlayer()
        _sidebarSelectedChatId.value = null
        _currentUser.value = null
        TdClient.stop()
        viewModelScope.launch { auth.requestQrLogin() }
    }

    /** v0.9.0: fetch the current TG user via TDLib getMe. */
    fun refreshMe() {
        viewModelScope.launch {
            val user = auth.getMe()
            _currentUser.value = user
        }
    }

    /** Open the dedicated PlayerScreen for the video at [index] in [mediaItems]. */
    fun openPlayer(index: Int) {
        _playerMediaIndex.value = index
    }

    /** Close the PlayerScreen and release the index handle. */
    fun closePlayer() {
        _playerMediaIndex.value = null
    }

    /** Step the player to another media item (direction ±1). */
    fun stepPlayer(delta: Int) {
        val cur = _playerMediaIndex.value ?: return
        _playerMediaIndex.value = cur + delta
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

    /** Save the current play position for the given fileId (ms). */
    fun savePlayerPosition(fileId: Int, positionMs: Long) {
        if (positionMs <= 0L) return
        _playerResumePositions.value =
            _playerResumePositions.value + (fileId to positionMs)
    }

    /** Clear resume position (e.g. user watched to the end). */
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
        // Boot QR
        viewModelScope.launch { auth.requestQrLogin() }
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
