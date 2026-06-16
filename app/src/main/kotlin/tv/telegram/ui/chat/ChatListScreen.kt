@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chat

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
 * SmartTube-style layout:
 *   - 3 tabs at top: Channels / Groups / Private
 *   - Each tab: a horizontal row of large chat cards
 *   - D-pad navigates between cards; OK enters a chat
 *
 * For v0.3.0 we don't yet enter a chat (chat detail comes in v0.4.0).
 */
@Composable
fun ChatListScreen(viewModel: MainViewModel) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val loaded by viewModel.chatListLoaded.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Channels", "Groups", "Private")

    val filtered = when (selectedTab) {
        0 -> chats.filter { it.type == ChatType.Channel }
        1 -> chats.filter { it.type == ChatType.Group || it.type == ChatType.SavedMessages }
        else -> chats.filter { it.type == ChatType.Private }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Telegram TV",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
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
            return@Column
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No ${tabs[selectedTab].lowercase()} yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 20.sp,
                )
            }
            return@Column
        }

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
            Text(
                "${filtered.size} ${tabs[selectedTab].lowercase()} · ${chats.size} total",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
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

private fun ChatType.label(): String = when (this) {
    ChatType.Channel -> "Channel"
    ChatType.Group -> "Group"
    ChatType.Private -> "Private"
    ChatType.SavedMessages -> "Saved"
    ChatType.Unknown -> "Chat"
}
