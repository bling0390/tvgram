@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.MediaType
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * ChatScreen — opens a chat, loads its media, shows them as a horizontal
 * grid of cards. OK on a card opens FullScreenMedia with ←/→ navigation.
 */
@Composable
fun ChatScreen(viewModel: MainViewModel, chatId: Long, onBack: () -> Unit) {
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val loaded by viewModel.mediaLoaded.collectAsStateWithLifecycle()
    val error by viewModel.mediaError.collectAsStateWithLifecycle()
    val chatTitle by viewModel.currentChatTitle.collectAsStateWithLifecycle()
    val loadingMore by viewModel.mediaLoadingMore.collectAsStateWithLifecycle()
    val exhausted by viewModel.mediaExhausted.collectAsStateWithLifecycle()

    // null = grid mode; non-null = fullscreen index into mediaItems
    var openedIndex by remember { mutableStateOf<Int?>(null) }

    val gridState = rememberLazyGridState()

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId)
    }

    // Auto-load-more when the user is within 6 cells of the end.
    // derivedStateOf ensures we only recompute when inputs actually change.
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 6
        }
    }
    LaunchedEffect(mediaItems.size) {
        snapshotFlow { nearEnd }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMoreMedia() }
    }

    // ── Fullscreen layer ─────────────────────────────────────────────
    if (openedIndex != null) {
        val idx = openedIndex!!
        if (idx in mediaItems.indices) {
            FullScreenMedia(
                item = mediaItems[idx],
                positionText = "${idx + 1} / ${mediaItems.size}",
                hasPrev = idx > 0,
                hasNext = idx < mediaItems.size - 1,
                onPrev = { openedIndex = idx - 1 },
                onNext = { openedIndex = idx + 1 },
                onBack = { openedIndex = null },
                viewModel = viewModel,
            )
        } else {
            // Index out of range (e.g. items list shrank) — fall back to grid
            openedIndex = null
        }
        return
    }

    // ── Grid layer ───────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Text(
            text  = "←  ${chatTitle ?: "Chat"}",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "${mediaItems.size} media items" +
                when {
                    loadingMore -> " · loading more…"
                    exhausted && mediaItems.isNotEmpty() -> " (all loaded)"
                    else -> ""
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading media…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (error != null && mediaItems.isEmpty()) {
            // Show error + a retry card. OK on the card re-runs openChat.
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { retryFocus.requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(
                    onClick = { viewModel.openChat(chatId) },
                    modifier = Modifier
                        .focusRequester(retryFocus)
                        .padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Press OK to retry",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            return@Column
        }
        if (mediaItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos or videos in this chat", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            items(mediaItems, key = { it.messageId }) { item ->
                val realIndex = mediaItems.indexOfFirst { it.messageId == item.messageId }
                MediaCard(
                    item = item,
                    onClick = { openedIndex = realIndex },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit, viewModel: MainViewModel) {
    val ctx = LocalContext.current
    val thumbId = item.thumbnailFileId
    val thumbState = if (thumbId != null) {
        (viewModel.fileStateFor(thumbId) as? FileDownloadState.Local)?.path
    } else null
    val fullState = viewModel.fileStateFor(item.fileId)
    val fullPath = (fullState as? FileDownloadState.Local)?.path

    // Kick off thumbnail download when the card is composed
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
            val toShow = thumbState ?: fullPath
            if (toShow != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(File(toShow))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.caption ?: "Media",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF202020)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (item.type == MediaType.Video) "▶  Video" else "🖼  Photo",
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
                    Text("▶", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * FullScreenMedia — view one media item fullscreen. Lean-back TV:
 *   D-pad ← / →       → previous / next item in chat
 *   D-pad Back / Esc  → close fullscreen (BackHandler)
 *   OK                → for photos: toggle Fit ↔ Actual size
 *
 * `positionText` shows "N / total" in the corner. A subtle
 * prev/next chevron fades in at the screen edges if hasPrev/hasNext.
 */
@Composable
private fun FullScreenMedia(
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
                    Key.DirectionLeft, Key.MediaPrevious -> {
                        if (hasPrev) onPrev()
                        true
                    }
                    Key.DirectionRight, Key.MediaNext -> {
                        if (hasNext) onNext()
                        true
                    }
                    else -> false
                }
            },
    ) {
        FullScreenMediaContent(item = item, viewModel = viewModel)

        // Top-left: "← Back" hint
        Text(
            "←  Back  ·  ◀ ▶  Prev / Next",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
        )

        // Top-right: position counter
        Text(
            positionText,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
        )

        // Bottom: prev/next chevrons
        if (hasPrev) {
            Text(
                "◀",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 48.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp),
            )
        }
        if (hasNext) {
            Text(
                "▶",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 48.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp),
            )
        }
    }
}

@Composable
private fun FullScreenMediaContent(item: MediaItem, viewModel: MainViewModel) {
    val ctx = LocalContext.current
    var localPath by remember(item.fileId) { mutableStateOf<String?>(null) }
    var error by remember(item.fileId) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.fileId) {
        try {
            val p = viewModel.fileRepo.ensureLocal(item.fileId, priority = 32, timeoutMs = 90_000L)
            if (p != null) localPath = p else error = "Download timed out"
        } catch (e: Throwable) {
            error = e.message
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Text("Error: $error", color = Color.White)
            localPath == null -> Text(
                if (item.type == MediaType.Video) "Loading video…" else "Loading image…",
                color = Color.White,
            )
            item.type == MediaType.Video -> VideoPlayer(localPath = localPath!!)
            else -> AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(File(localPath!!))
                    .build(),
                contentDescription = item.caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun VideoPlayer(localPath: String) {
    val ctx = LocalContext.current
    val exo = remember(localPath) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(ExoMediaItem.fromUri("file://$localPath"))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                player = exo
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
