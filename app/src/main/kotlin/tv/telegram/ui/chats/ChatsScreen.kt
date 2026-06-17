@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import tv.telegram.td.ChatItem
import tv.telegram.td.ChatType
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.MediaType
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.io.File

/**
 * ChatsScreen — the "Chats" pane of the v0.8.0 HomeScreen (B-plan layout).
 *
 *   ┌──────────────┬────────────────────────────────────────┐
 *   │  Sidebar      │  Media grid for the selected chat        │
 *   │  (chat list)  │  - empty placeholder if no chat picked   │
 *   │  - avatar     │  - 3-row horizontal scrolling grid       │
 *   │  - title      │  - 16:10 cards; video → PlayerScreen    │
 *   │  - last msg   │  - photo  → FullScreenMedia (in-place)   │
 *   │  - date       │                                           │
 *   │               │  When user picks a media card, opens the │
 *   │               │  fullscreen viewer; OK/Back to close.    │
 *   └──────────────┴────────────────────────────────────────┘
 *
 * D-pad flow (v0.8.0 v1):
 *   - Sidebar: Up/Down selects chat, OK selects (right pane activates)
 *   - Media grid: Up/Down/Left/Right nav cells, OK opens player / viewer
 *   - ← on media grid jumps focus back to sidebar
 *   - Back closes fullscreen, then exits the chat selection
 */
@Composable
fun ChatsScreen(viewModel: MainViewModel) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val loaded by viewModel.chatListLoaded.collectAsStateWithLifecycle()
    val selectedChatId by viewModel.sidebarSelectedChatId.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val mediaLoaded by viewModel.mediaLoaded.collectAsStateWithLifecycle()
    val currentChatTitle by viewModel.currentChatTitle.collectAsStateWithLifecycle()

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Sidebar ──────────────────────────────────────────────
        ChatSidebar(
            chats = chats,
            loaded = loaded,
            selectedChatId = selectedChatId,
            onSelect = { viewModel.selectSidebarChat(it) },
            viewModel = viewModel,
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(Color(0xFF161616))
                .padding(vertical = 16.dp, horizontal = 12.dp),
        )

        // ── Right pane ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            if (selectedChatId == null) {
                EmptyMediaPane(modifier = Modifier.fillMaxSize())
            } else {
                MediaPane(
                    title = currentChatTitle,
                    items = mediaItems,
                    loaded = mediaLoaded,
                    onSelect = { idx ->
                        val item = mediaItems.getOrNull(idx) ?: return@MediaPane
                        if (item.type == MediaType.Video) {
                            viewModel.openPlayer(idx)
                        } else {
                            // photos handled internally via FullScreenMedia
                        }
                    },
                    onOpenFullscreen = { idx ->
                        // for photos; videos go to PlayerScreen
                        val item = mediaItems.getOrNull(idx) ?: return@MediaPane
                        if (item.type == MediaType.Photo) {
                            // openFullscreen handled inside MediaPane via state
                        }
                    },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun ChatSidebar(
    chats: List<ChatItem>,
    loaded: Boolean,
    selectedChatId: Long?,
    onSelect: (Long) -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(modifier = modifier) {
        Text(
            "Chats",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${chats.size} conversations",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading chats\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chats, key = { it.id }) { chat ->
                SidebarItem(
                    chat = chat,
                    selected = chat.id == selectedChatId,
                    onClick = { onSelect(chat.id) },
                    fr = if (chat.id == chats.first().id) firstFocus else null,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(
    chat: ChatItem,
    selected: Boolean,
    onClick: () -> Unit,
    fr: FocusRequester? = null,
    viewModel: MainViewModel,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF1F1F1F)
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = MaterialTheme.colorScheme.secondary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(chat = chat, viewModel = viewModel)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chat.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chat.lastMessageText ?: chat.type.name,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (chat.unreadCount > 0) {
                UnreadBadge(count = chat.unreadCount)
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder(chat: ChatItem, viewModel: MainViewModel) {
    val ctx = LocalContext.current
    val photoId = chat.photoSmallFileId
    val localPath = if (photoId != null) {
        (viewModel.fileStateFor(photoId) as? FileDownloadState.Local)?.path
    } else null
    val color = when (chat.type) {
        ChatType.Channel -> Color(0xFF4A90E2)
        ChatType.Group -> Color(0xFF50C878)
        ChatType.Private -> Color(0xFFE67E22)
        else -> Color(0xFF888888)
    }
    val initial = chat.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(color, RoundedCornerShape(22.dp))
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (localPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(localPath)).build(),
                contentDescription = chat.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).background(color, RoundedCornerShape(22.dp)),
            )
        } else {
            Text(initial, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val display = if (count > 99) "99+" else count.toString()
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(display, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Right pane: media grid + fullscreen viewer ─────────────────────

@Composable
private fun EmptyMediaPane(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "\u2190  Select a chat",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a conversation from the sidebar to see its photos and videos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun MediaPane(
    title: String?,
    items: List<MediaItem>,
    loaded: Boolean,
    onSelect: (Int) -> Unit,
    onOpenFullscreen: (Int) -> Unit,
    viewModel: MainViewModel,
) {
    // null = grid mode; non-null = fullscreen index into items
    var openedIndex by remember { mutableStateOf<Int?>(null) }

    val gridState = rememberLazyGridState()

    // Auto-load-more when within 6 cells of end
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(items.size) {
        snapshotFlow { nearEnd }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMoreMedia() }
    }

    if (openedIndex != null) {
        val idx = openedIndex!!
        if (idx in items.indices) {
            PhotoFullscreen(
                item = items[idx],
                positionText = "${idx + 1} / ${items.size}",
                hasPrev = idx > 0,
                hasNext = idx < items.size - 1,
                onPrev = { openedIndex = idx - 1 },
                onNext = { openedIndex = idx + 1 },
                onBack = { openedIndex = null },
                viewModel = viewModel,
            )
        } else {
            openedIndex = null
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title ?: "Chat",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${items.size} media items" + when {
                !loaded -> " (loading\u2026)"
                items.isEmpty() -> ""
                else -> ""
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (loaded) "No photos or videos in this chat" else "Loading media\u2026",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyHorizontalGrid(
            state = gridState,
            rows = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(items, key = { it.messageId }) { item ->
                val realIndex = items.indexOfFirst { it.messageId == item.messageId }
                SidebarMediaCard(
                    item = item,
                    onClick = {
                        if (item.type == MediaType.Video) {
                            viewModel.openPlayer(realIndex)
                        } else {
                            openedIndex = realIndex
                        }
                    },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SidebarMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    viewModel: MainViewModel,
) {
    val ctx = LocalContext.current
    val thumbId = item.thumbnailFileId
    val thumbState = if (thumbId != null) {
        (viewModel.fileStateFor(thumbId) as? FileDownloadState.Local)?.path
    } else null
    LaunchedEffect(thumbId) {
        if (thumbId != null && thumbState == null) {
            viewModel.ensureMediaFile(thumbId, priority = 16)
        }
    }
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .size(280.dp)
            .aspectRatio(16f / 10f),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (thumbState != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(File(thumbState))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.caption ?: "Media",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (item.type == MediaType.Video) "\u25B6  Video" else "\ud83d\uddbc  Photo",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            if (item.type == MediaType.Video) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("\u25B6", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Fullscreen viewer (photos only in v0.8.0; videos go to PlayerScreen) ──

@Composable
private fun PhotoFullscreen(
    item: MediaItem,
    positionText: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel,
) {
    BackHandler(enabled = true) { onBack() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(item.fileId) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft, Key.MediaPrevious -> { if (hasPrev) onPrev(); true }
                    Key.DirectionRight, Key.MediaNext -> { if (hasNext) onNext(); true }
                    else -> false
                }
            },
    ) {
        val ctx = LocalContext.current
        var localPath by remember(item.fileId) { mutableStateOf<String?>(null) }
        var error by remember(item.fileId) { mutableStateOf<String?>(null) }
        LaunchedEffect(item.fileId) {
            try {
                val p = viewModel.fileRepo.ensureLocal(item.fileId, priority = 32, timeoutMs = 90_000L)
                if (p != null) localPath = p else error = "Download timed out"
            } catch (e: Throwable) { error = e.message }
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Text("Error: $error", color = Color.White)
                localPath == null -> Text("Loading image\u2026", color = Color.White)
                else -> AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(localPath!!)).build(),
                    contentDescription = item.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            "\u2190  Back  \u00B7  \u25C0 \u25B6  Prev / Next",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
        )
        Text(
            positionText,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
        )
    }
}


