package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.ui.home.HomeScreen
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.player.PlayerScreen
import tv.telegram.ui.theme.TvgramTheme

/**
 * Single Activity, Compose-driven.
 *
 * Top-level navigation:
 *   AuthState not Ready                    → QrLoginScreen
 *   AuthState.Ready + player open          → PlayerScreen
 *   AuthState.Ready (no player)            → HomeScreen
 *                                          (Search / Chats / Settings via NavRail)
 *
 * v0.8.0 collapsed the old "ChatListScreen" + "ChatScreen" into a single
 * HomeScreen with an internal NavRail; the old two-screen model is gone.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            TvgramTheme(themeMode = themeMode) {
                AppRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val playerIndex by viewModel.playerMediaIndex.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val showSignOutBanner by viewModel.showSignOutBanner.collectAsStateWithLifecycle()
    val signingIn by viewModel.signingIn.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // During sign-out, stay on the screen that initiated the wipe so
            // SettingsScreen (or whatever was active) doesn't get yanked to
            // QrLoginScreen mid-click. The natural switch happens once
            // [signingOut] flips back to false, which is wired in
            // MainViewModel.init to fire when TDLib reaches WaitQrCode (or
            // Error) after a 350ms delay so the Message overlay's exit tween
            // can finish first.
            signingOut -> {
                if (playerIndex != null) {
                    PlayerScreen(viewModel = viewModel)
                } else {
                    HomeScreen(viewModel = viewModel)
                }
            }
            // During sign-in, hold on QrLoginScreen even after TDLib hits
            // Ready so the "Signing in…" Message overlay can fade in +
            // stay visible on top of the QR page (not the home page). The
            // 500ms delay in MainViewModel.init between auth→Ready and
            // signingIn=false is what keeps AppRoot here long enough for
            // the enter tween (300ms) to complete before the screen swap.
            signingIn -> {
                QrLoginScreen(viewModel = viewModel)
            }
            authState !is AuthState.Ready -> {
                QrLoginScreen(viewModel = viewModel)
            }
            playerIndex != null -> {
                // Dedicated Player route (v0.7.0). Reachable from the
                // Chats module's media grid for any video card.
                PlayerScreen(viewModel = viewModel)
            }
            else -> {
                HomeScreen(viewModel = viewModel)
            }
        }

        // Transient status overlays — pure components, see Message.kt.
        // Both always in composition; AnimatedVisibility inside each hides
        // them when their own visible flag is false, so no layout cost
        // outside the active window. sign-out and sign-in are mutually
        // exclusive in practice, so they never overlap visually.
        Message(
            visible = showSignOutBanner,
            message = stringResource(R.string.signing_out),
        )
        Message(
            visible = signingIn,
            message = stringResource(R.string.signing_in),
        )
    }
}
