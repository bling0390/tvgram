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

/**
 * TdAuth — listens to TDLib updates and maintains a StateFlow<AuthState>
 * for the UI to collect.
 *
 * TDLib is the source of truth. We translate:
 *   UpdateAuthorizationState   → AuthState transitions
 *
 * And we send:
 *   RequestQrCodeAuthentication     → triggers WaitOtherDeviceConfirmation
 *   LogOut                          → cancels a pending QR login
 *
 * For MVP we only need the QR-login half. Phone/code/registration flows
 * are NOT implemented (D-003 — QR-only login).
 *
 * v1.0.0 (D-029): switched from JSON RPC (`getLoginUrl` + custom qrCode
 * payload) to the correct TDLib API: `RequestQrCodeAuthentication()`.
 * The QR link comes back in `AuthorizationStateWaitOtherDeviceConfirmation.link`.
 */
class TdAuth(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Guard against duplicate [TdApi.RequestQrCodeAuthentication] calls.
     *
     * The TDLib API is only valid in specific auth states (see
     * TdApi.java:58722 — `authorizationStateWaitPhoneNumber`, or the
     * `waitCode`/`waitRegistration`/`waitPassword` states with no
     * pending query). We set the flag when we send the request and
     * clear it when we receive the resulting
     * `AuthorizationStateWaitOtherDeviceConfirmation` link (or when
     * we transition away from `WaitPhoneNumber` for any other reason).
     */
    @Volatile private var pendingQrRequest: Boolean = false

    init {
        scope.launch {
            client.updates.collect { obj -> handleUpdate(obj) }
        }
    }

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            else -> { /* ignore other updates */ }
        }
    }

    private fun handleAuthState(authState: TdApi.AuthorizationState) {
        val typeName = authState.javaClass.simpleName
        Log.i(TAG, "updateAuthorizationState → $typeName (pendingQr=$pendingQrRequest)")

        when (authState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _state.value = AuthState.WaitTdlibParams
            }
            is TdApi.AuthorizationStateWaitEncryptionKey -> {
                _state.value = AuthState.WaitEncryptionKey
                // For MVP we don't set an encryption key. TDLib will use
                // the default (no encryption). If user has a previously
                // encrypted db this will fail and we surface it via Error.
                client.send(TdApi.CheckDatabaseEncryptionKey(ByteArray(0)))
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                // Clear the QR-pending guard — we've got the link.
                pendingQrRequest = false
                val link = authState.link
                if (link.isNotEmpty()) {
                    _state.value = AuthState.WaitQrCode(link, alreadyLoggedIn = false)
                } else {
                    _state.value = AuthState.Error("QR login: empty link from TDLib")
                }
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> {
                pendingQrRequest = false
                _state.value = AuthState.Closed
            }
            is TdApi.AuthorizationStateReady -> {
                pendingQrRequest = false
                _state.value = AuthState.Ready
                Log.i(TAG, "✅ Authorization complete — Ready")
            }
            // WaitPhoneNumber is the FIRST state where
            // RequestQrCodeAuthentication becomes valid (per TDLib
            // schema). We send the request here — not earlier (TdClient
            // start, MainViewModel.init), because the request would
            // land in WaitTdlibParameters where TDLib ignores it.
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                if (!pendingQrRequest) {
                    Log.i(TAG, "WaitPhoneNumber → sending RequestQrCodeAuthentication")
                    pendingQrRequest = true
                    client.send(TdApi.RequestQrCodeAuthentication())
                    _state.value = AuthState.LoggingIn
                } else {
                    Log.i(TAG, "WaitPhoneNumber (pendingQr=true) → awaiting QR result")
                    // Already requested; TDLib will deliver either
                    // WaitOtherDeviceConfirmation (success) or stay
                    // here. Don't overwrite LoggingIn.
                }
            }
            // Other states we don't support — surface as Error so the
            // UI can show a meaningful message. We expect to never see
            // these in our QR-only flow, but log loudly if TDLib ever
            // forces them (e.g. a previously phone-logged-in db that
            // had a pending code query).
            is TdApi.AuthorizationStateWaitCode -> {
                if (!pendingQrRequest) {
                    Log.w(TAG, "WaitCode (no pending QR) — TDLib may have a stuck code query")
                    pendingQrRequest = true
                    client.send(TdApi.RequestQrCodeAuthentication())
                    _state.value = AuthState.LoggingIn
                } else {
                    _state.value = AuthState.Error("Code login is not supported.")
                }
            }
            is TdApi.AuthorizationStateWaitRegistration ->
                _state.value = AuthState.Error("Registration is not supported.")
            is TdApi.AuthorizationStateWaitPassword -> {
                if (!pendingQrRequest) {
                    Log.w(TAG, "WaitPassword (no pending QR) — TDLib may be 2FA-protected")
                    pendingQrRequest = true
                    client.send(TdApi.RequestQrCodeAuthentication())
                    _state.value = AuthState.LoggingIn
                } else {
                    _state.value = AuthState.Error("2FA password is not supported in this build.")
                }
            }
        }
    }

    /**
     * Ask TDLib for the current user. Returns null if TDLib hasn't
     * returned a `user` yet (e.g. not yet Ready).
     */
    suspend fun getMe(timeoutMs: Long = 5_000L): TdUser? {
        val resp = client.execute(TdApi.GetMe(), timeoutMs) ?: return null
        if (resp !is TdApi.User) return null
        return TdUser(
            id          = resp.id,
            firstName   = resp.firstName,
            lastName    = resp.lastName,
            username    = resp.username ?: "",
            phoneNumber = resp.phoneNumber ?: "",
        )
    }

    /**
     * Explicitly request QR-code authentication. Normally you DON'T
     * need to call this — [handleAuthState] sends the request
     * automatically when it sees `WaitPhoneNumber`. This is exposed
     * for the "Refresh QR" UI affordance: clearing the in-flight
     * flag and re-sending gets a fresh link from TDLib.
     *
     * Works only when the current authorization state is
     * `WaitPhoneNumber`, or if there is no pending authentication query
     * and the state is `WaitCode`, `WaitRegistration`, or `WaitPassword`.
     */
    fun requestQrLogin() {
        pendingQrRequest = true
        client.send(TdApi.RequestQrCodeAuthentication())
    }

    /**
     * Cancel a pending QR login. TDLib will transition to a closed state.
     */
    fun cancelQrLogin() {
        client.send(TdApi.LogOut())
    }

    companion object {
        private const val TAG = "TdAuth"
    }
}

/**
 * TdUser — UI-friendly projection of TDLib's `user` type.
 * Only the fields Tvgram needs for the Settings account row.
 */
data class TdUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val username: String,
    val phoneNumber: String,
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { username.ifBlank { "Telegram User" } }
}