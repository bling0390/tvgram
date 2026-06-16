package tv.telegram.td

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * TdAuth — listens to TDLib updates and maintains a StateFlow<AuthState>
 * for the UI to collect.
 *
 * TDLib is the source of truth. We translate:
 *   updateAuthorizationState   → AuthState transitions
 *   updateOption (auth)        → same
 * And we send:
 *   checkAuthenticationEmailCode
 *   checkAuthenticationCode
 *   getLoginUrl                → triggers WaitQrCode
 *   confirmQrCodeAuthentication
 *   registerUser, etc.
 *
 * For MVP we only need the QR-login half. Phone/code/registration flows
 * are NOT implemented (D-003 — QR-only login).
 */
class TdAuth(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var lastQrLink: String? = null
    private var hasHandledReady = false

    init {
        scope.launch {
            client.updates.collect { obj -> handleUpdate(obj) }
        }
    }

    private fun handleUpdate(obj: JSONObject) {
        val type = obj.optString("@type")
        when (type) {
            "updateAuthorizationState" -> handleAuthState(obj.optJSONObject("authorization_state"))
            "updateOption"              -> { /* could re-emit; not needed for MVP */ }
            else -> { /* ignore everything else */ }
        }
    }

    private fun handleAuthState(authState: JSONObject?) {
        if (authState == null) return
        val type = authState.optString("@type")
        Log.i(TAG, "updateAuthorizationState → $type")

        when (type) {
            "authorizationStateWaitTdlibParameters" -> {
                _state.value = AuthState.WaitTdlibParams
            }
            "authorizationStateWaitEncryptionKey" -> {
                _state.value = AuthState.WaitEncryptionKey
                // For MVP we don't set an encryption key. TDLib will use
                // the default (no encryption). If user has a previously
                // encrypted db this will fail and we surface it via Error.
                // Simpler: send checkDatabaseEncryptionKey with empty string.
                client.send(JSONObject().apply {
                    put("@type", "checkDatabaseEncryptionKey")
                    put("encryption_key", "")
                })
            }
            "authorizationStateWaitOtherDeviceConfirmation" -> {
                val link = authState.optString("link")
                if (link.isNotEmpty()) {
                    lastQrLink = link
                    _state.value = AuthState.WaitQrCode(link, alreadyLoggedIn = false)
                } else {
                    _state.value = AuthState.Error("QR login: empty link from TDLib")
                }
            }
            "authorizationStateLoggingOut" -> {
                _state.value = AuthState.Closed
            }
            "authorizationStateClosing" -> {
                _state.value = AuthState.Closed
            }
            "authorizationStateClosed" -> {
                _state.value = AuthState.Closed
            }
            "authorizationStateReady" -> {
                hasHandledReady = true
                _state.value = AuthState.Ready
                Log.i(TAG, "✅ Authorization complete — Ready")
            }
            // Other states (phone/code/registration) — we don't support
            // them, but log loudly so we know if TDLib is forcing the
            // phone flow (e.g. a previous phone login session).
            "authorizationStateWaitPhoneNumber" ->
                _state.value = AuthState.Error("Phone-number login is not supported. Restart the app to use QR login.")
            "authorizationStateWaitCode" ->
                _state.value = AuthState.Error("Code login is not supported.")
            "authorizationStateWaitRegistration" ->
                _state.value = AuthState.Error("Registration is not supported.")
            "authorizationStateWaitPassword" ->
                _state.value = AuthState.Error("2FA password is not supported in this build.")
            else -> {
                Log.w(TAG, "Unhandled authorization state: $type")
            }
        }
    }

    /**
     * Ask TDLib for a fresh QR login URL. Use this on app start to
     * transition from WaitTdlibParams / Closed → WaitQrCode.
     */
    fun requestQrLogin() {
        client.send(JSONObject().apply {
            put("@type", "getLoginUrl")
            put("quarter", JSONObject().apply { put("@type", "qrCode") })
            put("bot_id", 0)  // 0 = non-bot login
        })
    }

    /**
     * Cancel a pending QR login. TDLib will transition to a closed state.
     */
    fun cancelQrLogin() {
        client.send(JSONObject().apply {
            put("@type", "logOut")
        })
    }

    companion object {
        private const val TAG = "TdAuth"
    }
}
