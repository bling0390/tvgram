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
import tv.telegram.ui.chat.ChatListScreen
import tv.telegram.ui.chat.ChatScreen
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.theme.TvgramTheme

/**
 * Single Activity, Compose-driven.
 *
 * Top-level navigation:
 *   AuthState.Ready + no chat open         → ChatListScreen
 *   AuthState.Ready + chat open            → ChatScreen(chatId)
 *   otherwise                              → QrLoginScreen
 *
 * We deliberately keep this as a simple when expression (no NavHost lib)
 * to avoid adding navigation-compose as a dep for v0.4.0.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TvgramTheme {
                AppRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val openChatId by viewModel.currentChatId.collectAsStateWithLifecycle()

    when {
        authState !is AuthState.Ready -> {
            QrLoginScreen(viewModel = viewModel)
        }
        openChatId != null -> {
            ChatScreen(
                viewModel = viewModel,
                chatId = openChatId!!,
                onBack = { viewModel.closeChat() },
            )
        }
        else -> {
            ChatListScreen(viewModel = viewModel)
        }
    }
}
