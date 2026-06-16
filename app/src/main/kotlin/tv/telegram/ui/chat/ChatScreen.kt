@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.chat

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/**
 * ChatScreen — opens a chat, loads its media, shows them as a horizontal
 * grid of cards. OK on a card opens FullScreenMedia.
 */
@Composable
fun ChatScreen(viewModel: MainViewModel, chatId: Long, onBack: () -> Unit) {
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val loaded by viewModel.mediaLoaded.collectAsStateWithLifecycle()
    val chatTitle by viewModel.currentChatTitle.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId)
    }

    if (opened != null) {
        FullScreenMedia(item = opened!!, onBack = { opened = null }, viewModel = viewModel)
        return
    }

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
            text  = "${mediaItems.size} media items",
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
        if (mediaItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos or videos in this chat", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyHorizontalGrid(
            rows = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(mediaItems, key = { it.messageId }) { item ->
                MediaCard(item, onClick = { opened = item }, viewModel)
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit, viewModel: MainViewModel) {
    val ctx = LocalContext.current
    val thumbId = item.thumbnailFileId
    val thumbState = if (thumbId != null) {
        val s = viewModel.fileStateFor(thumbId)
        (s as? FileDownloadState.Local)?.path
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

@Composable
private fun FullScreenMedia(item: MediaItem, onBack: () -> Unit, viewModel: MainViewModel) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
    ) {
        Text(
            "←  Back",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier
                .padding(8.dp),
        )
        Spacer(Modifier.height(8.dp))

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
