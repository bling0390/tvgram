package tv.telegram.td

sealed class AuthState {

    data object Idle : AuthState()

    data object WaitTdlibParams : AuthState()

    data object WaitEncryptionKey : AuthState()

    data class WaitQrCode(
        val link: String,
    ) : AuthState()

    data object LoggingIn : AuthState()

    data object Ready : AuthState()

    data class Error(val message: String) : AuthState()

    data object Closed : AuthState()
}
