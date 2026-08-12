@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import tv.telegram.ui.login.ColdStartScreen
import tv.telegram.ui.login.QrCodeScreen
import tv.telegram.ui.player.PlayerScreen
import tv.telegram.ui.search.SearchScreen
import tv.telegram.ui.settings.SettingsScreen
import tv.telegram.ui.nav.Routes
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

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val inHome = currentRoute?.startsWith(Routes.HOME) == true

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            val target = when (event) {
                NavEvent.GoToQrCode -> Routes.QR_LOGIN
                NavEvent.GoToHome -> Routes.HOME
            }
            navController.navigate(target) {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    BackHandler(enabled = currentRoute == Routes.HOME_SEARCH || currentRoute == Routes.HOME_SETTINGS) {
        navController.navigate(Routes.HOME_CHATS) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Decide the initial destination once, at first composition. Making this
    // reactive to authState would rebuild the whole NavGraph whenever auth
    // leaves Ready (e.g. mid sign-out), resetting the UI to COLD_START and
    // racing the GoToQrCode/GoToHome events. Navigation is event-driven only.
    val startDestination = remember {
        when {
            authState is AuthState.Ready -> Routes.HOME
            authState is AuthState.WaitTdlibParams ||
                authState is AuthState.WaitEncryptionKey ||
                authState is AuthState.Idle -> Routes.COLD_START
            else -> Routes.QR_LOGIN
        }
    }

    // Sidebar: expands (icon + label) while it holds focus, collapses to
    // icon-only once focus moves to the content area. Width animates and the
    // NavHost padding follows so content never gets covered.
    var railExpanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) 220.dp else 96.dp,
        label = "railWidth",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (inHome) railWidth else 0.dp),
        ) {
            composable(Routes.COLD_START) { ColdStartScreen(viewModel = viewModel) }

            composable(Routes.QR_LOGIN) { QrCodeScreen(viewModel = viewModel) }

            navigation(startDestination = Routes.HOME_CHATS, route = Routes.HOME) {
                composable(Routes.HOME_SEARCH) {
                    SearchScreen(
                        viewModel = viewModel,
                        onOpenChats = {
                            navController.navigate(Routes.HOME_CHATS) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Routes.HOME_CHATS) {
                    ChatsScreen(
                        viewModel = viewModel,
                        onOpenPlayer = { index -> navController.navigate(Routes.player(index)) },
                    )
                }
                composable(Routes.HOME_SETTINGS) { SettingsScreen(viewModel = viewModel) }
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { entry ->
                val index = entry.arguments?.getInt("index") ?: 0
                PlayerScreen(
                    viewModel = viewModel,
                    index = index,
                    onClose = { navController.popBackStack() },
                    onNavigateTo = { newIndex ->
                        navController.navigate(Routes.player(newIndex)) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }

        if (inHome) {
            NavRail(
                current = currentRoute,
                expanded = railExpanded,
                onExpandedChange = { railExpanded = it },
                onSelect = { route ->
                    navController.navigate(route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(railWidth)
                    .fillMaxHeight(),
            )
        }
    }
}

private data class NavEntry(val route: String, val label: String)

@Composable
private fun NavRail(
    current: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        NavEntry(Routes.HOME_CHATS, stringResource(R.string.nav_chats)),
        NavEntry(Routes.HOME_SEARCH, stringResource(R.string.nav_search)),
        NavEntry(Routes.HOME_SETTINGS, stringResource(R.string.nav_settings)),
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
            .onFocusChanged { onExpandedChange(it.hasFocus) }
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        entries.forEachIndexed { idx, entry ->
            RailItem(
                entry = entry,
                selected = current == entry.route,
                expanded = expanded,
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
    expanded: Boolean,
    onClick: () -> Unit,
    fr: FocusRequester? = null,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        else -> Color.Transparent
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        ),
        shape = CardDefaults.shape(
            RoundedCornerShape(14.dp),
            RoundedCornerShape(14.dp),
            RoundedCornerShape(14.dp),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Placeholder icon: white circle. Swap for a real icon later.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            if (expanded) {
                Text(
                    text = entry.label,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else Color(0xFFB0B0B0),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
