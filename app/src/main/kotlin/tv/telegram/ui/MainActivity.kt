@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.ui.chats.ChatsScreen
import tv.telegram.ui.login.QrLoginScreen
import tv.telegram.ui.player.PlayerScreen
import tv.telegram.ui.search.SearchScreen
import tv.telegram.ui.settings.SettingsScreen
import tv.telegram.ui.theme.TvgramTheme
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            TvgramTheme(themeMode = themeMode) {
                AppNavHost(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppNavHost(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val signingIn by viewModel.signingIn.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val inHome = currentRoute?.startsWith("home") == true

    LaunchedEffect(authState, signingIn) {
        val target = when {
            signingIn -> "qrLogin"
            authState is AuthState.Ready -> "home"
            else -> "qrLogin"
        }
        val current = navController.currentDestination?.route
        if (current != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    BackHandler(enabled = currentRoute == "home/search" || currentRoute == "home/settings") {
        navController.navigate("home/chats") {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (authState is AuthState.Ready) "home" else "qrLogin",
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (inHome) 96.dp else 0.dp),
        ) {
            composable("qrLogin") { QrLoginScreen(viewModel = viewModel) }

            navigation(startDestination = "home/chats", route = "home") {
                composable("home/search") {
                    SearchScreen(
                        viewModel = viewModel,
                        onOpenChats = {
                            navController.navigate("home/chats") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable("home/chats") {
                    ChatsScreen(
                        viewModel = viewModel,
                        onOpenPlayer = { index -> navController.navigate("player/$index") },
                    )
                }
                composable("home/settings") { SettingsScreen(viewModel = viewModel) }
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

        if (inHome) {
            NavRail(
                current = currentRoute,
                onSelect = { route ->
                    navController.navigate(route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(96.dp)
                    .fillMaxHeight(),
            )
        }

        Message(
            visible = signingIn,
            message = stringResource(R.string.signing_in),
        )
    }
}

private data class NavEntry(val route: String, val label: String)

@Composable
private fun NavRail(
    current: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        NavEntry("home/search", stringResource(R.string.nav_search)),
        NavEntry("home/chats", stringResource(R.string.nav_chats)),
        NavEntry("home/settings", stringResource(R.string.nav_settings)),
    )

    val railFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        try {
            railFocus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF141414))
            .padding(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        entries.forEachIndexed { idx, entry ->
            RailItem(
                entry = entry,
                selected = current == entry.route,
                onClick = { onSelect(entry.route) },
                fr = if (idx == 0) railFocus else null,
            )
        }
    }
}

@Composable
private fun RailItem(
    entry: NavEntry,
    selected: Boolean,
    onClick: () -> Unit,
    fr: FocusRequester? = null,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF2A2A2A)
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = MaterialTheme.colorScheme.secondary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.10f),
        modifier = Modifier
            .size(width = 80.dp, height = 80.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
