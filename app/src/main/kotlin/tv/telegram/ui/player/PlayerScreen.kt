@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.telegram.R
import tv.telegram.td.FileDownloadState
import tv.telegram.td.MediaItem
import tv.telegram.td.MediaType
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * PlayerScreen — dedicated video player route (v0.7.0).
 *
 * Difference from FullScreenMedia in ChatScreen:
 *   - Photos are NOT handled here; the grid's FullScreenMedia still owns
 *     those. Photos won't trigger openPlayer().
 *   - Compose-native [PlayerSurface] (replaces the legacy PlayerView), so
 *     D-pad focus and Compose overlays integrate cleanly.
 *   - D-pad controller:
 *       ← / →       seek -10s / +10s   (or prev/next video if at edge)
 *       Up / Down   volume up / down    (v0.7.0 stub: no-op; v0.7.1 wires audio focus)
 *       OK / Center toggle play / pause
 *       Back        close (or release control overlay)
 *       Long OK / MenuButton cycle playback speed (1.0 → 1.25 → 1.5 → 2.0)
 *   - Auto-play next: when the current video ends, advance to the next
 *     Video item in [mediaItems] (skipping photos). Stops at the end of
 *     the chat's media list.
 *   - Resume from saved position (per fileId, in-memory for v0.7.0).
 */
@Composable
fun PlayerScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val index by viewModel.playerMediaIndex.collectAsStateWithLifecycle()
    val speed by viewModel.playerPlaybackSpeed.collectAsStateWithLifecycle()
    val resumeMap by viewModel.playerResumePositions.collectAsStateWithLifecycle()

    if (index == null || index !in mediaItems.indices) {
        // Defensive: nothing to play. Bounce back to grid.
        LaunchedEffect(Unit) { viewModel.closePlayer() }
        return
    }

    val current = mediaItems[index!!]

    // Build a sub-list of "video" indices so prev/next skips photos.
    val videoIndices = remember(mediaItems) {
        mediaItems.mapIndexedNotNull { i, m -> if (m.type == MediaType.Video) i else null }
    }
    val posInVideos = remember(videoIndices, index) { videoIndices.indexOf(index) }
    val hasPrevVideo = posInVideos > 0
    val hasNextVideo = posInVideos in 0 until videoIndices.lastIndex

    // Pre-download the current video file (TdFileRepository caches by fileId).
    val currentFileState = viewModel.fileStateFor(current.fileId)
    val currentPath = (currentFileState as? FileDownloadState.Local)?.path
    LaunchedEffect(current.fileId) {
        if (currentPath == null) {
            viewModel.ensureMediaFile(current.fileId, priority = 32)
        }
    }

    // Build the ExoPlayer. New instance on fileId change.
    val exo = remember(current.fileId) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

    // Set media item when path is ready, and seek to resume position once.
    var mediaPrepared by remember(current.fileId) { mutableStateOf(false) }
    LaunchedEffect(currentPath, current.fileId) {
        if (currentPath != null && !mediaPrepared) {
            exo.setMediaItem(ExoMediaItem.fromUri("file://$currentPath"))
            exo.prepare()
            val resume = resumeMap[current.fileId]
            if (resume != null && resume > 0L) {
                exo.seekTo(resume)
            }
            mediaPrepared = true
        }
    }

    // Sync speed from ViewModel.
    LaunchedEffect(speed) {
        exo.setPlaybackSpeed(speed)
    }

    // Periodic position save (every 2s while playing).
    LaunchedEffect(exo, current.fileId) {
        while (true) {
            delay(2000L)
            if (exo.isPlaying) {
                viewModel.savePlayerPosition(current.fileId, exo.currentPosition)
            }
        }
    }

    // Auto-advance to the next video on ENDED.
    LaunchedEffect(exo) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewModel.clearPlayerPosition(current.fileId)
                    if (hasNextVideo) {
                        viewModel.stepPlayer(videoIndices[posInVideos + 1] - (index ?: 0))
                    } else {
                        viewModel.closePlayer()
                    }
                }
            }
        })
    }

    // BackHandler: close player.
    BackHandler(enabled = true) { viewModel.closePlayer() }

    // Focus requester so D-pad events land here first.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Controller overlay visibility (auto-hide after 4s of inactivity).
    var showController by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showController) {
        if (showController) {
            delay(4000L)
            if (System.currentTimeMillis() - lastInteractionMs >= 4000L) {
                showController = false
            }
        }
    }
    val bumpController = {
        showController = true
        lastInteractionMs = System.currentTimeMillis()
    }

    // Presentation state from media3-ui-compose (drives buffering, video size).
    // Kept for future use in the controller overlay (v0.7.1+).
    @Suppress("UNUSED_VARIABLE")
    val presentation = rememberPresentationState(exo)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> {
                        exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                        bumpController()
                        true
                    }
                    Key.DirectionRight -> {
                        exo.seekTo(
                            (exo.currentPosition + 10_000L)
                                .coerceAtMost(exo.duration.coerceAtLeast(0L))
                        )
                        bumpController()
                        true
                    }
                    Key.MediaPrevious -> {
                        if (hasPrevVideo) {
                            viewModel.stepPlayer(
                                videoIndices[(posInVideos - 1).coerceAtLeast(0)] - (index ?: 0)
                            )
                        }
                        true
                    }
                    Key.MediaNext -> {
                        if (hasNextVideo) {
                            viewModel.stepPlayer(
                                videoIndices[(posInVideos + 1).coerceAtMost(videoIndices.lastIndex)] -
                                    (index ?: 0)
                            )
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay,
                    Key.MediaPause -> {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        bumpController()
                        true
                    }
                    Key.Menu -> {
                        viewModel.cyclePlayerSpeed()
                        bumpController()
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (currentPath == null || !mediaPrepared) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.player_loading),
                    color = Color.White,
                    fontSize = 18.sp,
                )
            }
        } else {
            PlayerSurface(
                player = exo,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Subtle top hint (always visible)
        Text(
            text = stringResource(R.string.player_key_hint, speed),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        )

        // Bottom controller overlay (auto-hide)
        AnimatedVisibility(
            visible = showController,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerController(
                positionMs = { exo.currentPosition },
                durationMs = { exo.duration.coerceAtLeast(0L) },
                isPlaying = { exo.isPlaying },
                speed = speed,
                positionText = "${index!! + 1} / ${mediaItems.size}",
                onPlayPause = {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    bumpController()
                },
                onSeekBack = {
                    exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                    bumpController()
                },
                onSeekFwd = {
                    exo.seekTo(
                        (exo.currentPosition + 10_000L)
                            .coerceAtMost(exo.duration.coerceAtLeast(0L))
                    )
                    bumpController()
                },
                onSpeedCycle = {
                    viewModel.cyclePlayerSpeed()
                    bumpController()
                },
                onPrev = if (hasPrevVideo) {
                    {
                        viewModel.stepPlayer(
                            videoIndices[(posInVideos - 1).coerceAtLeast(0)] - (index ?: 0)
                        )
                        bumpController()
                    }
                } else null,
                onNext = if (hasNextVideo) {
                    {
                        viewModel.stepPlayer(
                            videoIndices[(posInVideos + 1).coerceAtMost(videoIndices.lastIndex)] -
                                (index ?: 0)
                        )
                        bumpController()
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun PlayerController(
    positionMs: () -> Long,
    durationMs: () -> Long,
    isPlaying: () -> Boolean,
    speed: Float,
    positionText: String,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekFwd: () -> Unit,
    onSpeedCycle: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val pos by remember {
        derivedStateOf { positionMs() }
    }
    val dur by remember {
        derivedStateOf { durationMs() }
    }
    val playing by remember { derivedStateOf { isPlaying() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Progress bar (visual only for v0.7.0; clickable to seek would be v0.7.1).
        ProgressBar(positionMs = pos, durationMs = dur)
        Spacer(Modifier.size(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.player_position, formatMs(pos), formatMs(dur)),
                color = Color.White,
                fontSize = 14.sp,
            )
            Text(
                text = positionText,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
            Text(
                text = "${speed}x",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (onPrev != null) {
                ControllerButton(label = stringResource(R.string.player_btn_prev), onClick = onPrev)
                Spacer(Modifier.width(12.dp))
            }
            ControllerButton(label = stringResource(R.string.player_btn_seek_back), onClick = onSeekBack)
            Spacer(Modifier.width(12.dp))
            ControllerButton(
                label = if (playing) stringResource(R.string.player_btn_pause) else stringResource(R.string.player_btn_play),
                onClick = onPlayPause,
                emphasis = true,
            )
            Spacer(Modifier.width(12.dp))
            ControllerButton(label = stringResource(R.string.player_btn_seek_fwd), onClick = onSeekFwd)
            if (onNext != null) {
                Spacer(Modifier.width(12.dp))
                ControllerButton(label = stringResource(R.string.player_btn_next), onClick = onNext)
            }
            Spacer(Modifier.width(12.dp))
            ControllerButton(label = stringResource(R.string.player_btn_speed), onClick = onSpeedCycle)
        }
    }
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long) {
    val pct = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(pct)
                .height(6.dp)
                .background(Color(0xFFE53935), RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun ControllerButton(
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Button(
        onClick = onClick,
        colors = if (emphasis) {
            ButtonDefaults.colors(
                containerColor = Color(0xFFE53935),
                contentColor = Color.White,
                focusedContainerColor = Color(0xFFFF6F60),
                focusedContentColor = Color.White,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.30f),
                focusedContentColor = Color.White,
            )
        },
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
