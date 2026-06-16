@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chat

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import tv.telegram.td.ChatItem
import tv.telegram.td.ChatType
import tv.telegram.td.FileDownloadState
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text

/**
 * ChatListScreen — main TV surface after login.
 *
 * v0.6.0 adds a search bar at the top:
 *   - D-pad up from the grid focuses the search bar
 *   - OK on the bar opens an on-screen D-pad keyboard (overlay)
 *   - Type Latin letters; results filter live (debounced 250ms)
 *   - Back closes the keyboard, then closes the search bar
 *   - Clearing the query (or pressing CLEAR) returns to the full list
 *
 * Layout (when keyboard closed):
 *   Telegram TV
 *   [🔍 Search chats...]   (always visible, shows current query)
 *   [Channels] [Groups] [Private]
 *   <4-col grid of chat cards>
 */
@Composable
fun ChatListScreen(viewModel: MainViewModel) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val loaded by viewModel.chatListLoaded.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchSearching by viewModel.searchSearching.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Channels", "Groups", "Private")
    var keyboardOpen by remember { mutableStateOf(false) }

    // The local edit buffer (what the user is typing). The viewModel's
    // searchQuery is the debounced source of truth that drives filtering.
    // We commit editBuffer -> viewModel after 250ms of inactivity.
    var editBuffer by remember { mutableStateOf("") }
    var lastEditAt by remember { mutableStateOf(0L) }

    // Debounce: 250ms after the last keystroke, push to viewModel.
    LaunchedEffect(editBuffer) {
        if (editBuffer == searchQuery) return@LaunchedEffect
        lastEditAt = System.currentTimeMillis()
        delay(250L)
        viewModel.setSearchQuery(editBuffer)
    }

    // External clears (e.g. pressing CLEAR on the keyboard) should sync
    // the edit buffer too.
    LaunchedEffect(searchQuery) {
        if (searchQuery != editBuffer && !keyboardOpen) {
            editBuffer = searchQuery
        }
    }

    BackHandler(enabled = keyboardOpen) {
        keyboardOpen = false
    }

    val filtered = when (selectedTab) {
        0 -> chats.filter { it.type == ChatType.Channel }
        1 -> chats.filter { it.type == ChatType.Group || it.type == ChatType.SavedMessages }
        else -> chats.filter { it.type == ChatType.Private }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Telegram TV",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            SearchBar(
                query = editBuffer,
                searching = searchSearching,
                isFocused = keyboardOpen,
                onClick = { keyboardOpen = true },
            )
            Spacer(Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onFocus = { selectedTab = idx },
                        onClick = { selectedTab = idx },
                        colors = TabDefaults.underlinedIndicatorTabColors(),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 18.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading chats…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val msg = when {
                        searchQuery.isNotEmpty() -> "No chats matching \"$searchQuery\""
                        else -> "No ${tabs[selectedTab].lowercase()} yet"
                    }
                    Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                }
            } else {
                // Sort: by lastMessageDate desc, then by unreadCount desc.
                // Chats with lastMessageDate=0 (never messaged) go to the bottom.
                val sorted = filtered.sortedWith(
                    compareByDescending<ChatItem> { it.lastMessageDate > 0 }
                        .thenByDescending { it.lastMessageDate }
                        .thenByDescending { it.unreadCount }
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    ChatGrid(
                        items = sorted,
                        onSelect = { id -> viewModel.openChat(id) },
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(8.dp))
                    val statsLine = buildString {
                        append("${filtered.size} ${tabs[selectedTab].lowercase()}")
                        if (searchQuery.isNotEmpty()) append(" matching \"$searchQuery\"")
                        append(" · ${chats.size} total")
                    }
                    Text(
                        statsLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // ── On-screen keyboard overlay ──────────────────────────────
        if (keyboardOpen) {
            DpadKeyboard(
                onChar = { c ->
                    editBuffer = editBuffer + c
                },
                onBackspace = {
                    if (editBuffer.isNotEmpty()) {
                        editBuffer = editBuffer.dropLast(1)
                    }
                },
                onClear = {
                    editBuffer = ""
                },
                onSearch = {
                    // Commit immediately and close the keyboard
                    viewModel.setSearchQuery(editBuffer)
                    keyboardOpen = false
                },
                onClose = { keyboardOpen = false },
            )
        }
    }
}

/**
 * Search bar at the top of ChatListScreen. D-pad up from the grid lands here.
 * OK opens the keyboard.
 */
@Composable
private fun SearchBar(
    query: String,
    searching: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🔍",
                fontSize = 20.sp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (query.isEmpty()) "Search chats..." else query + "_",
                color = if (query.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (searching) {
                Text(
                    "searching…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ChatGrid(
    items: List<ChatItem>,
    onSelect: (Long) -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 0.dp, vertical = 8.dp,
        ),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { chat ->
            ChatCard(chat, onClick = { onSelect(chat.id) }, viewModel = viewModel)
        }
    }
}

@Composable
private fun ChatCard(chat: ChatItem, onClick: () -> Unit, viewModel: MainViewModel) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier
            .width(220.dp)
            .height(140.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarPlaceholder(chat, viewModel)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = chat.type.label(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = chat.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            if (chat.unreadCount > 0) {
                UnreadBadge(chat.unreadCount)
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder(chat: ChatItem, viewModel: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val photoId = chat.photoSmallFileId
    val localPath = if (photoId != null) {
        (viewModel.fileStateFor(photoId) as? FileDownloadState.Local)?.path
    } else null
    LaunchedEffect(photoId) {
        if (photoId != null && localPath == null) {
            viewModel.ensureMediaFile(photoId, priority = 8)
        }
    }

    val color = when (chat.type) {
        ChatType.Channel -> Color(0xFF5288C1)
        ChatType.Group -> Color(0xFF4CAF50)
        ChatType.Private -> Color(0xFFFF9800)
        ChatType.SavedMessages -> Color(0xFF9C27B0)
        ChatType.Unknown -> Color(0xFF607D8B)
    }
    val initial = chat.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (localPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(java.io.File(localPath))
                    .crossfade(true)
                    .build(),
                contentDescription = chat.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
            )
        } else {
            Text(initial, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (count > 999) "999+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * DpadKeyboard — on-screen Latin keyboard for TV search input.
 *
 * Layout (5 cols × 6 rows = 30 keys):
 *   Row 0: A B C D E
 *   Row 1: F G H I J
 *   Row 2: K L M N O
 *   Row 3: P Q R S T
 *   Row 4: U V W X Y
 *   Row 5: Z BKSP CLEAR SEARCH CLOSE
 *
 * D-pad navigation works naturally because LazyVerticalGrid's item
 * traversal is row-major. The first key (A) auto-focuses when the
 * keyboard opens.
 */
@Composable
private fun DpadKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    // First-key focus requester; auto-focus "A" when the keyboard opens.
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.requestFocus() }

    // Backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            onClick = { /* swallow backdrop clicks */ },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(360.dp),
            colors = CardDefaults.colors(containerColor = Color(0xFF1E1E1E)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    "Type to search",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                // 5 cols × 6 rows; use a plain Column of Rows for explicit control.
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
                // Final row: Z + 4 control keys
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyButton("Z", onClick = { onChar('Z') }, modifier = Modifier.weight(1f))
                    KeyButton("⌫", onClick = onBackspace, modifier = Modifier.weight(1f))
                    KeyButton("CLR", onClick = onClear, modifier = Modifier.weight(1f))
                    KeyButton(
                        "SEARCH",
                        onClick = onSearch,
                        modifier = Modifier.weight(1.2f),
                        accent = true,
                    )
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
            containerColor = if (accent) MaterialTheme.colorScheme.primary
                            else Color(0xFF2C2C2C),
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

private fun ChatType.label(): String = when (this) {
    ChatType.Channel -> "Channel"
    ChatType.Group -> "Group"
    ChatType.Private -> "Private"
    ChatType.SavedMessages -> "Saved"
    ChatType.Unknown -> "Chat"
}
