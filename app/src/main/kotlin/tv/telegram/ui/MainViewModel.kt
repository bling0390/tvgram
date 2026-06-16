package tv.telegram.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.telegram.td.AuthState
import tv.telegram.td.TdAuth
import tv.telegram.td.TdChatRepository
import tv.telegram.td.TdClient

/**
 * MainViewModel — top-level ViewModel for MainActivity.
 *
 * Owns:
 *   - [auth]        (TDLib authorization state machine)
 *   - [chatRepo]    (chat list loader)
 * And exposes:
 *   - [authState]       for the login screen
 *   - [chatList]        for the chat list screen
 *   - [chatListLoaded]  loading flag
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val auth = TdAuth(client = TdClient, scope = viewModelScope)
    val chatRepo = TdChatRepository(client = TdClient, scope = viewModelScope)

    val authState: StateFlow<AuthState> = auth.state
    val chatList = chatRepo.items
    val chatListLoaded = chatRepo.loaded

    init {
        // Ask TDLib for a QR login URL after the TDLib has been started
        // by TgTvApp.onCreate(). TdAuth will no-op until TDLib is in a
        // state that can use it.
        viewModelScope.launch {
            auth.requestQrLogin()
        }
    }
}
