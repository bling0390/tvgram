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
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val showSignOutBanner by viewModel.showSignOutBanner.collectAsStateWithLifecycle()
    val signingIn by viewModel.signingIn.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {

            signingOut -> {
                HomeScreen(viewModel = viewModel, onOpenPlayer = {})
            }
            signingIn -> {
                QrLoginScreen(viewModel = viewModel)
            }
            else -> {
                AppNavHost(
                    viewModel = viewModel,
                    authState = authState,
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

@Composable
private fun AppNavHost(
    viewModel: MainViewModel,
    authState: AuthState,
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

    NavHost(
        navController = navController,
        startDestination = if (authState is AuthState.Ready) "home" else "qrLogin",
    ) {
        composable("qrLogin") { QrLoginScreen(viewModel = viewModel) }
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onOpenPlayer = { index -> navController.navigate("player/$index") },
            )
        }
        composable(
            route = "player/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType }),
        ) { entry ->
            val index = entry.arguments?.getInt("index") ?: 0
            PlayerScreen(
                viewModel = viewModel,
                index = index,
                onClose = { navController.popBackStack() },
                onNavigateTo = { newIndex ->

                    navController.navigate("player/$newIndex") {
                        popUpTo("player/{index}") { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
