package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.td.AuthState
import tv.telegram.ui.chat.ChatListScreen
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.theme.TvgramTheme

/**
 * Single Activity, Compose-driven.
 *
 * Top-level switch:
 *   AuthState.Ready  → ChatListScreen
 *   otherwise        → QrLoginScreen
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TvgramTheme {
                val authState by viewModel.authState.collectAsStateWithLifecycle()
                if (authState is AuthState.Ready) {
                    ChatListScreen(viewModel = viewModel)
                } else {
                    QrLoginScreen(viewModel = viewModel)
                }
            }
        }
    }
}
