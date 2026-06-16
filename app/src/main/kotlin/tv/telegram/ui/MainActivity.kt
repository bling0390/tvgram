package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.theme.TvgramTheme

/**
 * Single Activity, Compose-driven.
 *
 * The actual top-level navigation graph (Login → Home → Chat → Player)
 * will be hosted here as a `NavHost` once we wire up navigation.
 *
 * For now, the entry surface is the QR login screen.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TvgramTheme {
                QrLoginScreen(viewModel = viewModel)
            }
        }
    }
}
