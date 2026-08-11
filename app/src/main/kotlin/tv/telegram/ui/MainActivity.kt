package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.ui.home.HomeScreen
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.player.PlayerScreen
import tv.telegram.ui.theme.TvgramTheme

/**
 * Single Activity, Compose-driven. D-032: stable auth routing via
 * [AppNavHost]; transient hold states (signingOut / signingIn) stay
 * at AppRoot above NavHost so they can override routing during the
 * brief transition window.
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

/**
 * AppRoot. Why split hold states above NavHost: signingOut / signingIn
 * are transient flags that need to keep the current screen visible
 * while TDLib settles (~1s to walk Ready → Closed → WaitPhoneNumber →
 * WaitOtherDeviceConfirmation). Routing through NavHost during that
 * window races auth state changes and tears the layout.
 */
@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val playerIndex by viewModel.playerMediaIndex.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val showSignOutBanner by viewModel.showSignOutBanner.collectAsStateWithLifecycle()
    val signingIn by viewModel.signingIn.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            signingOut -> {
                if (playerIndex != null) {
                    PlayerScreen(viewModel = viewModel)
                } else {
                    HomeScreen(viewModel = viewModel)
                }
            }
            signingIn -> {
                QrLoginScreen(viewModel = viewModel)
            }
            else -> {
                AppNavHost(
                    viewModel = viewModel,
                    authState = authState,
                    playerIndex = playerIndex,
                )
            }
        }

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

/**
 * AppNavHost. Auth-driven navigate() uses popUpTo(startDestination,
 * inclusive=true) so the user can't Back into the wrong auth state.
 * Player open/close is a normal push/pop so the system Back button
 * pops Player → Home.
 */
@Composable
private fun AppNavHost(
    viewModel: MainViewModel,
    authState: AuthState,
    playerIndex: Int?,
) {
    val navController = rememberNavController()

    LaunchedEffect(authState) {
        val target = if (authState is AuthState.Ready) "home" else "qrLogin"
        val current = navController.currentDestination?.route
        if (current != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(playerIndex) {
        val current = navController.currentDestination?.route
        when {
            playerIndex != null && current != "player" -> {
                navController.navigate("player")
            }
            playerIndex == null && current == "player" -> {
                navController.popBackStack()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (authState is AuthState.Ready) "home" else "qrLogin",
    ) {
        composable("qrLogin") { QrLoginScreen(viewModel = viewModel) }
        composable("home") { HomeScreen(viewModel = viewModel) }
        composable("player") { PlayerScreen(viewModel = viewModel) }
    }
}
