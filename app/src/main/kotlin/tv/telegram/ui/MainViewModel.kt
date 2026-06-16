package tv.telegram.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import tv.telegram.td.AuthState
import tv.telegram.td.TdAuth
import tv.telegram.td.TdClient

/**
 * MainViewModel — top-level ViewModel for MainActivity.
 *
 * Owns the [TdAuth] instance and exposes [authState] to the Compose tree.
 * Once auth reaches [AuthState.Ready] we transition to the (placeholder)
 * home screen.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val auth = TdAuth(client = TdClient, scope = viewModelScope)

    val authState = auth.state

    init {
        // TDLib may already be initializing. Ask for a QR login URL — TdAuth
        // will be a no-op until TDLib is in a state that can use it
        // (specifically, after setTdlibParameters and database key check).
        viewModelScope.launch {
            auth.requestQrLogin()
        }
    }
}
