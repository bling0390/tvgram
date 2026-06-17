@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.telegram.td.ChatItem
import tv.telegram.ui.MainViewModel
import kotlinx.coroutines.delay

/**
 * SearchScreen — full-page global search (v0.8.0).
 *
 * Lifted from v0.6's ChatListScreen. Layout:
 *   ┌────────────────────────────────────────────────────────────┐
 *   │  🔍  Search chats..._                            searching…  │
 *   ├────────────────────────────────────────────────────────────┤
 *   │  4-col grid of matching chat cards (or "No matches" empty) │
 *   │                                                            │
 *   │  When the D-pad keyboard is open, an overlay appears:       │
 *   │                                                            │
 *   │       A B C D E                                            │
 *   │       F G H I J                                            │
 *   │       K L M N O                                            │
 *   │       P Q R S T                                            │
 *   │       U V W X Y Z                                          │
 *   │       Z ⌫ CLR SEARCH CLOSE                                 │
 *   └────────────────────────────────────────────────────────────┘
 *
 * First-focus lands on the search bar; OK opens the keyboard; typing
 * a letter debounces 250ms then calls viewModel.setSearchQuery().
 */
@Composable
fun SearchScreen(viewModel: MainViewModel) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val loaded by viewModel.chatListLoaded.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchSearching by viewModel.searchSearching.collectAsStateWithLifecycle()

    // editBuffer is what the user is typing; searchQuery is the
    // debounced, viewModel-side source of truth that drives the filter.
    var editBuffer by remember { mutableStateOf("") }
    var keyboardOpen by remember { mutableStateOf(false) }

    LaunchedEffect(editBuffer) {
        if (editBuffer == searchQuery) return@LaunchedEffect
        delay(250L)
        viewModel.setSearchQuery(editBuffer)
    }
    LaunchedEffect(searchQuery) {
        if (searchQuery != editBuffer && !keyboardOpen) {
            editBuffer = searchQuery
        }
    }

    val searchBarFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchBarFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
        ) {
            Text(
                "Global search",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            SearchBar(
                query = editBuffer,
                searching = searchSearching,
                fr = searchBarFocus,
                onClick = { keyboardOpen = true },
            )
            Spacer(Modifier.height(12.dp))
            val stats = if (searchQuery.isNotEmpty()) {
                "${chats.count { it.title.contains(searchQuery, ignoreCase = true) }} matches · ${chats.size} total"
            } else {
                "Type to search across all your chats"
            }
            Text(
                stats,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading chats\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val results = if (searchQuery.isBlank()) emptyList()
                              else chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
                if (results.isEmpty() && searchQuery.isNotBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No chats matching \"$searchQuery\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 20.sp)
                    }
                } else {
                    ResultsGrid(
                        items = results,
                        onSelect = { id ->
                            viewModel.selectSidebarChat(id)
                            viewModel.selectNavSection(MainViewModel.NavSection.Chats)
                        },
                    )
                }
            }
        }

        if (keyboardOpen) {
            DpadKeyboard(
                onChar = { c -> editBuffer = editBuffer + c },
                onBackspace = { if (editBuffer.isNotEmpty()) editBuffer = editBuffer.dropLast(1) },
                onClear = { editBuffer = "" },
                onSearch = {
                    viewModel.setSearchQuery(editBuffer)
                    keyboardOpen = false
                },
                onClose = { keyboardOpen = false },
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    searching: Boolean,
    fr: FocusRequester,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .focusRequester(fr),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\ud83d\udd0d", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (query.isEmpty()) "Search chats..." else query + "_",
                color = if (query.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (searching) {
                Text("searching\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    items: List<ChatItem>,
    onSelect: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { chat ->
            ResultCard(chat = chat, onClick = { onSelect(chat.id) })
        }
    }
}

@Composable
private fun ResultCard(chat: ChatItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.height(96.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = chat.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = chat.type.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

// ── D-pad keyboard (lifted from ChatListScreen v0.6) ─────────────────

@Composable
private fun DpadKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            onClick = { /* swallow backdrop clicks */ },
            modifier = Modifier.fillMaxWidth(0.8f).height(380.dp),
            colors = CardDefaults.colors(containerColor = Color(0xFF1E1E1E)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Text(
                    "Type to search",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                val rows = listOf(
                    listOf("A", "B", "C", "D", "E"),
                    listOf("F", "G", "H", "I", "J"),
                    listOf("K", "L", "M", "N", "O"),
                    listOf("P", "Q", "R", "S", "T"),
                    listOf("U", "V", "W", "X", "Y"),
                )
                rows.forEachIndexed { rowIdx, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { letter ->
                            KeyButton(
                                label = letter,
                                onClick = { onChar(letter[0]) },
                                modifier = Modifier.weight(1f),
                                fr = if (rowIdx == 0 && letter == "A") firstKey else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyButton("Z", onClick = { onChar('Z') }, modifier = Modifier.weight(1f))
                    KeyButton("\u232b", onClick = onBackspace, modifier = Modifier.weight(1f))
                    KeyButton("CLR", onClick = onClear, modifier = Modifier.weight(1f))
                    KeyButton("SEARCH", onClick = onSearch, modifier = Modifier.weight(1.2f), accent = true)
                    KeyButton("CLOSE", onClick = onClose, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    fr: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.10f),
        colors = CardDefaults.colors(
            containerColor = if (accent) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C),
        ),
        modifier = modifier
            .height(48.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = if (label.length > 1) 12.sp else 18.sp,
                fontWeight = if (accent) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}
