@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.R
import tv.telegram.ui.MainViewModel
import tv.telegram.ui.chats.ChatsScreen
import tv.telegram.ui.search.SearchScreen
import tv.telegram.ui.settings.SettingsScreen
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenPlayer: (Int) -> Unit,
) {
    val section by viewModel.navSection.collectAsStateWithLifecycle()
    val saveableStateHolder = rememberSaveableStateHolder()

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavRail(
            current = section,
            onSelect = { viewModel.selectNavSection(it) },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            saveableStateHolder.SaveableStateProvider(section) {
                when (section) {
                    MainViewModel.NavSection.Search  -> SearchScreen(viewModel)
                    MainViewModel.NavSection.Chats   -> ChatsScreen(viewModel, onOpenPlayer)
                    MainViewModel.NavSection.Settings -> SettingsScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun NavRail(
    current: MainViewModel.NavSection,
    onSelect: (MainViewModel.NavSection) -> Unit,
) {
    val entries = listOf(
        NavEntry(MainViewModel.NavSection.Search,   "",   stringResource(R.string.nav_search)),
        NavEntry(MainViewModel.NavSection.Chats,    "",   stringResource(R.string.nav_chats)),
        NavEntry(MainViewModel.NavSection.Settings, "",   stringResource(R.string.nav_settings)),
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
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .background(Color(0xFF141414))
            .padding(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        entries.forEachIndexed { idx, entry ->
            val fr = if (idx == 0) railFocus else null
            RailItem(
                entry = entry,
                selected = current == entry.section,
                onClick = { onSelect(entry.section) },
                fr = fr,
            )
        }
    }
}

private data class NavEntry(
    val section: MainViewModel.NavSection,
    val icon: String,
    val label: String,
)

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
    androidx.tv.material3.Card(
        onClick = onClick,
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = MaterialTheme.colorScheme.secondary,
        ),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.10f),
        modifier = Modifier
            .size(width = 80.dp, height = 80.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = entry.icon,
                color = Color.White,
                fontSize = 28.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
