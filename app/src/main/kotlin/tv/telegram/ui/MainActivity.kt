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
 *
 * v1.0.0 (D-032): stable auth routing delegated to androidx.navigation
 * via [AppNavHost]. Transient hold states (signingOut / signingIn) stay
 * at AppRoot above NavHost so they can override routing during the
 * brief transition window — see the comment on [AppRoot].
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
 * AppRoot — three layers stacked in a Box:
 *
 *   1. Transient hold states (signingOut / signingIn) render the
 *      current screen directly so the "Signing out…" / "Signing in…"
 *      overlays can sit on top of it. Bypasses NavHost entirely.
 *   2. Otherwise, [AppNavHost] handles the three stable routes
 *      (qrLogin / home / player) via NavController.
 *   3. Two [Message] overlays always live in composition, hidden by
 *      their own AnimatedVisibility when not active.
 *
 * Why splitting this way: signingOut / signingIn are real transient
 * flags that need to keep the current screen visible while TDLib
 * settles (e.g., TDLib takes ~1s to walk Ready → Closed →
 * WaitPhoneNumber → WaitOtherDeviceConfirmation before signingOut
 * can be flipped to false). Routing through NavHost during that
 * window would race the auth state changes and tear the layout.
 * Keeping them at AppRoot is the simplest correct fix.
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
 * AppNavHost — NavHost-based stable auth routing.
 *
 * Routes:
 *   - "qrLogin"  → [QrLoginScreen]
 *   - "home"     → [HomeScreen]    (Search / Chats / Settings via internal NavRail)
 *   - "player"   → [PlayerScreen]
 *
 * Player state is still owned by MainViewModel (`_playerMediaIndex`)
 * — kept there so sign-out / signingOut still work without a back
 * stack. NavController reacts to StateFlow changes via two
 * [LaunchedEffect]s, so the screens themselves don't need to know
 * about navigation. This means ChatsScreen / PlayerScreen /
 * HomeScreen did NOT need changes for D-032.
 *
 * Auth-driven navigate() uses popUpTo(startDestination, inclusive=true)
 * so the user can't Back into the wrong auth state. Player open/close
 * is a normal push/pop so the system Back button pops Player → Home.
 */
@Composable
private fun AppNavHost(
    viewModel: MainViewModel,
    authState: AuthState,
    playerIndex: Int?,
) {
    val navController = rememberNavController()

    // Sync auth → nav. When auth flips between Ready and not, clear
    // the back stack and navigate to the new home so the user can't
    // Back into the wrong auth state.
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

    // Sync player → nav. openPlayer() sets _playerMediaIndex, which
    // triggers push to "player". closePlayer() sets null, which pops
    // back to whatever was below (typically "home").
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
