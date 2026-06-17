package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    when {
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
}
