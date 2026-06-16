package tv.telegram.td

/**
 * AuthState — finite state machine of TDLib's `updateAuthorizationState`
 * updates, simplified for our MVP.
 *
 * The TDLib `AuthorizationState` types we care about:
 *   - waitTdlibParameters     → TDLib just started, needs setTdlibParameters
 *   - waitEncryptionKey       → needs database encryption key
 *   - waitPhoneNumber         → flow we're NOT using (we go via QR)
 *   - waitOtherDeviceConfirmation → we are the OTHER device (QR login flow)
 *   - waitCode                → not used in QR flow
 *   - waitRegistration        → not used (single-user, no sign-up)
 *   - ready                   → logged in, proceed to home
 *   - loggingOut              → user logged out from another device
 *   - closed                  → TDLib closed
 *   - closing                 → TDLib closing
 */
sealed class AuthState {
    /** TDLib hasn't sent its first auth update yet. */
    data object Idle : AuthState()

    /** TDLib needs `setTdlibParameters`. TdClient sends it on start. */
    data object WaitTdlibParams : AuthState()

    /** TDLib wants us to check (and provide) the database encryption key. */
    data object WaitEncryptionKey : AuthState()

    /**
     * TDLib is showing a QR code (we are a secondary client).
     * The user scans with their phone; we wait.
     *
     * @param link The `tg://login?token=...` URL to render in QR.
     * @param alreadyLoggedIn When true, the user has scanned but hasn't
     *        confirmed yet; show "Waiting for confirmation…".
     */
    data class WaitQrCode(
        val link: String,
        val alreadyLoggedIn: Boolean = false,
    ) : AuthState()

    /** TDLib accepted credentials, transitioning to Ready. */
    data object LoggingIn : AuthState()

    /** Logged in — proceed to home screen. */
    data object Ready : AuthState()

    /** Something went wrong. The message is human-readable. */
    data class Error(val message: String) : AuthState()

    /** TDLib closed (e.g. user logged out from another device). */
    data object Closed : AuthState()
}
