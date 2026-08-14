@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import tv.telegram.R
import tv.telegram.td.ChatItem
import tv.telegram.td.ChatType
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.MediaType
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.io.File

@Composable
fun ChatsScreen(
    viewModel: MainViewModel,
    onOpenPlayer: (Int) -> Unit,
) {
    val chats by viewModel.chatList.collectAsStateWithLifecycle()
    val archiveChats by viewModel.archiveChats.collectAsStateWithLifecycle()
    val viewingArchive by viewModel.viewingArchive.collectAsStateWithLifecycle()
    val archiveCount by viewModel.archiveCount.collectAsStateWithLifecycle()
    val loaded by viewModel.chatListLoaded.collectAsStateWithLifecycle()
    val chatListError by viewModel.chatListError.collectAsStateWithLifecycle()
    val selectedChatId by viewModel.sidebarSelectedChatId.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val mediaLoaded by viewModel.mediaLoaded.collectAsStateWithLifecycle()

    // Toast 即时提醒：聊天列表加载失败时弹一次
    val context = LocalContext.current
    LaunchedEffect(chatListError) {
        if (chatListError != null) {
            Toast.makeText(context, chatListError, Toast.LENGTH_LONG).show()
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {

        ChatSidebar(
            chats = if (viewingArchive) archiveChats else chats,
            loaded = loaded,
            error = chatListError,
            onRetry = { viewModel.retryLoadChats() },
            selectedChatId = selectedChatId,
            archiveCount = archiveCount,
            viewingArchive = viewingArchive,
            onSelect = { viewModel.selectSidebarChat(it) },
            onShowArchive = { viewModel.setViewingArchive(true) },
            onShowMain = { viewModel.setViewingArchive(false) },
            viewModel = viewModel,
            modifier = Modifier
                .width(296.dp)
                .fillMaxHeight()
                .background(Color(0xFF161616))
                .padding(vertical = 16.dp, horizontal = 8.dp),
        )

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
                    items = mediaItems,
                    loaded = mediaLoaded,
                    onOpenPlayer = onOpenPlayer,
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
    error: String?,
    onRetry: () -> Unit,
    selectedChatId: Long?,
    archiveCount: Int,
    viewingArchive: Boolean,
    onSelect: (Long) -> Unit,
    onShowArchive: () -> Unit,
    onShowMain: () -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    // True while focus sits on the first focusable list item. When it does,
    // DirectionUp is consumed so focus stays put instead of jumping to the
    // NavRail (Compose's default directional focus search would find Search
    // at top-left). Left key is the deliberate path back to the rail.
    var firstItemFocused by remember { mutableStateOf(false) }

    LaunchedEffect(viewingArchive) {
        withFrameNanos { }
        try { firstFocus.requestFocus() }
        catch (_: IllegalStateException) {}
    }

    Column(modifier = modifier) {
        if (error != null) {
            ChatListError(
                error = error,
                onRetry = onRetry,
            )
            return@Column
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (chats.isEmpty() && !viewingArchive) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chats_empty),
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && firstItemFocused) {
                    true // at the top of the list — consume, stay put
                } else {
                    false
                }
            },
        ) {
            if (viewingArchive) {
                item(key = "back-to-main") {
                    Box(Modifier.onFocusChanged { firstItemFocused = it.hasFocus }) {
                        ArchiveEntry(
                            label = "Back to Chats",
                            icon = Icons.Default.NorthWest,
                            onClick = onShowMain,
                            fr = firstFocus,
                        )
                    }
                }
            } else if (archiveCount > 0) {
                item(key = "show-archive") {
                    Box(Modifier.onFocusChanged { firstItemFocused = it.hasFocus }) {
                        ArchiveEntry(
                            label = "Archived Chats ($archiveCount)",
                            icon = Icons.Default.VisibilityOff,
                            onClick = onShowArchive,
                            fr = firstFocus,
                        )
                    }
                }
            }
            items(chats, key = { it.id }) { chat ->
                val isFirst = chat.id == chats.firstOrNull()?.id && viewingArchive.not() && archiveCount == 0
                if (isFirst) {
                    Box(Modifier.onFocusChanged { firstItemFocused = it.hasFocus }) {
                        SidebarItem(
                            chat = chat,
                            selected = chat.id == selectedChatId,
                            onClick = { onSelect(chat.id) },
                            fr = firstFocus,
                            viewModel = viewModel,
                        )
                    }
                } else {
                    SidebarItem(
                        chat = chat,
                        selected = chat.id == selectedChatId,
                        onClick = { onSelect(chat.id) },
                        fr = null,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatListError(
    error: String,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Card(
                onClick = onRetry,
                colors = CardDefaults.colors(
                    containerColor = Color(0xFF2A2A2A),
                    focusedContainerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Text(
                    text = "Retry",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ArchiveEntry(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    fr: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = Color(0xFF2A2A2A),
            focusedContainerColor = MaterialTheme.colorScheme.secondary,
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(
            Border.None,
            Border.None,
            Border.None,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
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
    val ctx = LocalContext.current
    val containerColor = when {
        selected -> Color(0xFF2E3A48) // 胶囊高亮（与侧边栏一致）
        else -> Color.Transparent
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = Color(0xFF3A4A5C),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
        shape = CardDefaults.shape(
            RoundedCornerShape(50),
            RoundedCornerShape(50),
            RoundedCornerShape(50),
        ),
        border = CardDefaults.border(
            Border.None,
            Border.None,
            Border.None,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(chat = chat, viewModel = viewModel)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.title,
                        color = if (selected) Color(0xFF9BDCFE) else Color(0xFFD0D0D0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chat.isVerified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = Color(0xFF4A9EF5),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    val typeIcon = chat.type.typeIcon()
                    if (typeIcon != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            tint = if (selected) Color(0xFF9BDCFE) else Color(0xFF909090),
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    if (chat.isMuted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = if (selected) Color(0xFF9BDCFE) else Color(0xFF808080),
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val thumbId = chat.lastMessageThumbFileId
                    val thumbState = if (thumbId != null) {
                        (viewModel.fileStateFor(thumbId) as? FileDownloadState.Local)?.path
                    } else null
                    LaunchedEffect(thumbId) {
                        if (thumbId != null && thumbState == null) {
                            viewModel.ensureMediaFile(thumbId, priority = 8)
                        }
                    }
                    if (thumbState != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(File(thumbState)).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        // Empty string keeps the row height stable when the last
                        // message is not photo/video, so the title never shifts.
                        text = chat.lastMessageText ?: "",
                        color = if (selected) Color(0xFF9BDCFE).copy(alpha = 0.85f) else Color(0xFF909090),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            if (chat.unreadCount > 0) {
                UnreadDot()
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
    var imageFailed by remember(photoId, localPath) { mutableStateOf(false) }
    LaunchedEffect(photoId) {
        if (photoId != null && localPath == null) {
            viewModel.ensureMediaFile(photoId, priority = 16)
        }
    }
    val color = when (chat.type) {
        ChatType.Channel -> Color(0xFF4A90E2)
        ChatType.Group -> Color(0xFF50C878)
        ChatType.Private -> Color(0xFFE67E22)
        else -> Color(0xFF888888)
    }
    val initial = chat.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(color, RoundedCornerShape(16.dp))
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (localPath != null && !imageFailed) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(localPath)).build(),
                contentDescription = chat.title,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(color, RoundedCornerShape(16.dp)),
            )
        } else {
            Text(initial, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UnreadDot() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
    )
}

/** Chat-type glyph for the title row: Person (private) / Groups (group) / Campaign (channel). */
private fun ChatType.typeIcon(): ImageVector? = when (this) {
    ChatType.Private -> Icons.Default.Person
    ChatType.Group -> Icons.Default.Groups
    ChatType.Channel -> Icons.Default.Campaign
    else -> null
}

@Composable
private fun EmptyMediaPane(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.chats_select_prompt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.chats_select_detail),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun MediaPane(
    items: List<MediaItem>,
    loaded: Boolean,
    onOpenPlayer: (Int) -> Unit,
    viewModel: MainViewModel,
) {

    var openedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // True while focus sits on a card in the grid's first row (index < 3).
    // When it does, DirectionUp is consumed so focus stays in the media
    // grid instead of jumping to the chats list (Compose's global focus
    // search). Left key remains the deliberate path back to the sidebar.
    var firstRowFocused by remember { mutableStateOf(false) }

    // Hover preview: one shared muted ExoPlayer reused across all cards.
    // Focus on a video card for 2.5s → play the first chunk of the file
    // inline; losing focus stops it and restores the thumbnail.
    val context = LocalContext.current
    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply { volume = 0f }
    }
    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }
    var focusedMessageId by remember { mutableStateOf<Long?>(null) }
    var previewingMessageId by remember { mutableStateOf<Long?>(null) }
    // Set while the preview file is being downloaded (before playback starts),
    // so the card can show a loading spinner in place of the play icon.
    var previewLoadingMessageId by remember { mutableStateOf<Long?>(null) }
    // Set only once the preview player has actually rendered its first frame.
    // Until then the card keeps showing the thumbnail on top of the surface,
    // so hover → load → play never flashes a black screen.
    var previewReadyMessageId by remember { mutableStateOf<Long?>(null) }

    // First-frame signal from the shared preview player (player is created on
    // the main thread, so the callback also lands on main — safe to touch state).
    DisposableEffect(previewPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                previewReadyMessageId = previewingMessageId
            }
        }
        previewPlayer.addListener(listener)
        onDispose { previewPlayer.removeListener(listener) }
    }

    LaunchedEffect(focusedMessageId) {
        // Any focus change: stop the previous preview first.
        previewPlayer.stop()
        previewingMessageId = null
        previewLoadingMessageId = null
        previewReadyMessageId = null
        val id = focusedMessageId
        if (id == null) return@LaunchedEffect
        val item = items.firstOrNull { it.messageId == id } ?: return@LaunchedEffect
        if (item.type != MediaType.Video) return@LaunchedEffect
        delay(2500)
        if (focusedMessageId != id) return@LaunchedEffect // focus moved away
        previewLoadingMessageId = id
        val path = viewModel.ensurePreviewFile(item.fileId)
        previewLoadingMessageId = null
        if (path == null || focusedMessageId != id) return@LaunchedEffect
        previewingMessageId = id
        previewPlayer.setMediaItem(ExoMediaItem.fromUri("file://$path"))
        previewPlayer.prepare()
        previewPlayer.play()
    }

    val gridState = rememberLazyGridState()

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

    // Photo fullscreen paging: when viewing near the end of the loaded
    // list, prefetch the next search page so next/prev keeps working
    // past the current data boundary.
    LaunchedEffect(openedIndex, items.size) {
        val idx = openedIndex ?: return@LaunchedEffect
        if (idx >= items.size - 8 && !viewModel.mediaExhausted.value) {
            viewModel.loadMoreMedia()
        }
    }

    if (openedIndex != null) {
        val idx = openedIndex!!
        if (idx in items.indices) {
            // True fullscreen photo viewer: a dedicated Dialog window that
            // covers the whole screen (NavRail + chat sidebar included),
            // instead of replacing only the media pane.
            Dialog(
                onDismissRequest = { openedIndex = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
            ) {
                PhotoFullscreen(
                    item = items[idx],
                    hasPrev = idx > 0,
                    hasNext = idx < items.size - 1,
                    onPrev = { openedIndex = idx - 1 },
                    onNext = { openedIndex = idx + 1 },
                    onBack = { openedIndex = null },
                    viewModel = viewModel,
                )
            }
        } else {
            openedIndex = null
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loaded) {
                    Text(
                        stringResource(R.string.chats_media_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // Top/bottom padding gives the 8% focus-scale room to expand
            // without clipping the first/last row against the viewport.
            contentPadding = PaddingValues(vertical = 20.dp),
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { ev ->
                    // Only when focus is on a first-row card is there no
                    // upward candidate; consume so focus stays put.
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && firstRowFocused) {
                        true
                    } else {
                        false
                    }
                },
        ) {
            gridItemsIndexed(items, key = { _, item -> item.messageId }) { index, item ->
                val previewing = previewingMessageId == item.messageId
                val previewLoading = previewLoadingMessageId == item.messageId
                val previewReady = previewReadyMessageId == item.messageId
                val onFocusChange: (Boolean) -> Unit = { focused ->
                    if (focused) focusedMessageId = item.messageId
                    else if (focusedMessageId == item.messageId) focusedMessageId = null
                }
                if (index < 3) {
                    // Track focus on first-row cards so DirectionUp at the top
                    // of the grid is consumed (see onKeyEvent above).
                    Box(Modifier.onFocusChanged { firstRowFocused = it.hasFocus }) {
                        SidebarMediaCard(
                            item = item,
                            onClick = {
                                if (item.type == MediaType.Video) {
                                    onOpenPlayer(index)
                                } else {
                                    openedIndex = index
                                }
                            },
                            viewModel = viewModel,
                            previewing = previewing,
                            previewLoading = previewLoading,
                            previewReady = previewReady,
                            previewPlayer = previewPlayer,
                            onFocusChange = onFocusChange,
                        )
                    }
                } else {
                    SidebarMediaCard(
                        item = item,
                        onClick = {
                            if (item.type == MediaType.Video) {
                                onOpenPlayer(index)
                            } else {
                                openedIndex = index
                            }
                        },
                        viewModel = viewModel,
                        previewing = previewing,
                        previewLoading = previewLoading,
                        previewReady = previewReady,
                        previewPlayer = previewPlayer,
                        onFocusChange = onFocusChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    viewModel: MainViewModel,
    previewing: Boolean = false,
    previewLoading: Boolean = false,
    previewReady: Boolean = false,
    previewPlayer: ExoPlayer? = null,
    onFocusChange: (Boolean) -> Unit = {},
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
        scale = CardDefaults.scale(focusedScale = 1.10f),
        glow = CardDefaults.glow(
            // NOTE: CardGlow order is (enabled, focused, pressed) — the glow
            // must go in slot #2 (focused) so only the focused card shows it.
            Glow.None,
            Glow(elevationColor = Color.White.copy(alpha = 0.15f), elevation = 3.dp),
            Glow.None,
        ),
        border = CardDefaults.border(
            Border.None,
            Border.None,
            Border.None,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .onFocusChanged { onFocusChange(it.hasFocus) },
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (previewing && previewPlayer != null) {
                PlayerSurface(
                    player = previewPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
                // Keep the thumbnail on top until the player has rendered its
                // first frame — the live surface only appears once there is
                // actually something to show.
                if (!previewReady && thumbState != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(File(thumbState))
                            .crossfade(true)
                            .build(),
                        contentDescription = item.caption ?: "Media",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (thumbState != null) {
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
                // Thumbnail not downloaded yet — show a centered spinner
                // instead of the old "Photo"/"Video" placeholder.
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF202020)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            // Bottom-right badge only when the thumbnail is ready — no point
            // showing the play chip over a spinner.
            if (item.type == MediaType.Video && !previewing && thumbState != null) {
                // No dark chip behind the loading spinner — it sits directly on
                // the thumbnail. The idle play icon keeps its chip.
                val badgeModifier = if (previewLoading) {
                    Modifier
                } else {
                    Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .then(badgeModifier)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    if (previewLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoFullscreen(
    item: MediaItem,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel,
) {
    BackHandler(enabled = true) { onBack() }
    val focusRequester = remember { FocusRequester() }
    // Box must be focusable or onKeyEvent never fires; retry the focus
    // request a few frames until the Dialog window is attached.
    LaunchedEffect(item.fileId) {
        withFrameNanos { }
        repeat(5) {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {}
            delay(60)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
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
        val downloadTimedOut = stringResource(R.string.download_timed_out)
        LaunchedEffect(item.fileId) {
            try {
                val p = viewModel.fileRepo.ensureLocal(item.fileId, priority = 32, timeoutMs = 90_000L)
                if (p != null) localPath = p else error = downloadTimedOut
            } catch (e: Throwable) { error = e.message }
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Text(
                    stringResource(R.string.error_prefix, error ?: ""),
                    color = Color.White,
                )
                localPath == null -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
                else -> AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(localPath!!)).build(),
                    contentDescription = item.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Always-visible translucent arrows; hidden at the first/last edge.
        if (hasPrev) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp)
                    .size(48.dp),
            )
        }
        if (hasNext) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp)
                    .size(48.dp),
            )
        }
    }
}
