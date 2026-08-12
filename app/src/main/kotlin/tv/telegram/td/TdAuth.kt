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

class TdAuth(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile private var pendingQrRequest: Boolean = false
    private var qrRequestAttempts = 0

    init {

        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(pendingQrTimeoutMs)
                if (!pendingQrRequest) {
                    qrRequestAttempts = 0
                    continue
                }
                qrRequestAttempts++
                if (qrRequestAttempts > maxQrRequestAttempts) {
                    Log.e(TAG, "QR request stuck after ${maxQrRequestAttempts}×${pendingQrTimeoutMs}ms; failing")
                    pendingQrRequest = false
                    _state.value = AuthState.Error("QR login timed out")
                    qrRequestAttempts = 0
                } else {
                    Log.w(TAG, "QR request pending >${pendingQrTimeoutMs}ms (attempt $qrRequestAttempts); re-requesting")
                    requestQrCodeAuth()
                }
            }
        }
        scope.launch {
            client.updates.collect { obj -> handleUpdate(obj) }
        }

    client.send(TdApi.GetAuthorizationState()) { result ->
        if (result is TdApi.AuthorizationState) {
            handleAuthState(result)
        } else if (result is TdApi.Error) {
            Log.w(TAG, "GetAuthorizationState bootstrap failed: ${result.code} ${result.message}")
        }
    }
}

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            else -> {  }
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

                client.send(TdApi.CheckDatabaseEncryptionKey(ByteArray(0)))
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {

                pendingQrRequest = false
                val link = authState.link
                if (link.isNotEmpty()) {
                    _state.value = AuthState.WaitQrCode(link)
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
                Log.i(TAG, "Authorization complete — Ready")
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                if (!pendingQrRequest) {
                    Log.i(TAG, "WaitPhoneNumber → sending RequestQrCodeAuthentication")
                    requestQrCodeAuth()
                } else {
                    Log.i(TAG, "WaitPhoneNumber (pendingQr=true) → awaiting QR result")

                }
            }

            is TdApi.AuthorizationStateWaitCode -> {
                pendingQrRequest = false
                _state.value = AuthState.Error("Code login is not supported.")
            }
            is TdApi.AuthorizationStateWaitRegistration ->
                _state.value = AuthState.Error("Registration is not supported.")
            is TdApi.AuthorizationStateWaitPassword -> {
                pendingQrRequest = false
                _state.value = AuthState.Error("2FA password is not supported in this build.")
            }
        }
    }

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

    fun requestQrLogin() {
        requestQrCodeAuth()
    }

    fun cancelQrLogin() {
        // TDLib only allows RequestQrCodeAuthentication from WaitPhoneNumber; sending
        // LogOut() while unauthenticated dead-ends the client in Closed with no way
        // back. Just drop the guard so the next state update can drive a fresh QR.
        Log.i(TAG, "cancelQrLogin: resetting pending QR guard")
        pendingQrRequest = false
        qrRequestAttempts = 0
    }

    private fun requestQrCodeAuth() {
        pendingQrRequest = true
        _state.value = AuthState.LoggingIn
        client.send(TdApi.RequestQrCodeAuthentication()) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "RequestQrCodeAuthentication failed: ${result.code} ${result.message}")
                pendingQrRequest = false
                _state.value = AuthState.Error("QR login unavailable: ${result.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TdAuth"
        private const val pendingQrTimeoutMs: Long = 30_000L
        private const val maxQrRequestAttempts: Int = 3
    }
}

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
