@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    index: Int,
    onClose: () -> Unit,
    onNavigateTo: (Int) -> Unit,
) {
    val context = LocalContext.current
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val speed by viewModel.playerPlaybackSpeed.collectAsStateWithLifecycle()
    val resumeMap by viewModel.playerResumePositions.collectAsStateWithLifecycle()
    val mediaExhausted by viewModel.mediaExhausted.collectAsStateWithLifecycle()

    // Cross-page continuous playback: when playing near the end of the
    // loaded media list, prefetch the next search page so the playlist
    // keeps growing past the current data boundary.
    LaunchedEffect(index, mediaItems.size, mediaExhausted) {
        if (!mediaExhausted && index >= mediaItems.size - 8) {
            viewModel.loadMoreMedia()
        }
    }

    if (index !in mediaItems.indices) {

        LaunchedEffect(Unit) { onClose() }
        return
    }

    val current = mediaItems[index]

    val videoIndices = remember(mediaItems) {
        mediaItems.mapIndexedNotNull { i, m -> if (m.type == MediaType.Video) i else null }
    }
    val posInVideos = remember(videoIndices, index) { videoIndices.indexOf(index) }
    val hasPrevVideo = posInVideos > 0
    val hasNextVideo = posInVideos in 0 until videoIndices.lastIndex

    fun neighborVideo(delta: Int): Int? = videoIndices.getOrNull(posInVideos + delta)

    val currentFileState = viewModel.fileStateFor(current.fileId)
    val currentPath = (currentFileState as? FileDownloadState.Local)?.path
    LaunchedEffect(current.fileId) {
        if (currentPath == null) {
            viewModel.ensureMediaFile(current.fileId, priority = 32)
        }
    }

    val exo = remember(current.fileId) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

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

    LaunchedEffect(speed) {
        exo.setPlaybackSpeed(speed)
    }

    LaunchedEffect(exo, current.fileId) {
        while (true) {
            delay(2000L)
            if (exo.isPlaying) {
                viewModel.savePlayerPosition(current.fileId, exo.currentPosition)
            }
        }
    }

    LaunchedEffect(exo) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewModel.clearPlayerPosition(current.fileId)
                    val next = neighborVideo(+1)
                    if (next != null) {
                        onNavigateTo(next)
                    } else {
                        onClose()
                    }
                }
            }
        })
    }

    BackHandler(enabled = true) { onClose() }

    val focusRequester = remember { FocusRequester() }
    val progressFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        try { focusRequester.requestFocus() }
        catch (_: IllegalStateException) {}
    }

    // Hidden by default: entering the player shows pure video. The page
    // Box keeps focus (left/right seek without revealing the controller);
    // OK / Up / Down reveal the controller with focus on the progress bar.
    var showController by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var controllerShownBefore by remember { mutableStateOf(false) }
    // Auto-hide after 4s of inactivity — lastInteractionMs is a key, so any
    // bump (button press / progress seek) restarts the timer. Also handles
    // focus handover: reveal → progress bar (after enter composes); hide →
    // page Box (after fade-out finishes, so vanishing buttons don't eat
    // direction keys during the exit animation).
    LaunchedEffect(showController, lastInteractionMs) {
        if (showController) {
            controllerShownBefore = true
            delay(100L)
            try { progressFocusRequester.requestFocus() }
            catch (_: IllegalStateException) {}
            delay(4000L)
            showController = false
        } else if (controllerShownBefore) {
            delay(400L)
            try { focusRequester.requestFocus() }
            catch (_: IllegalStateException) {}
        }
    }
    val bumpController = {
        showController = true
        lastInteractionMs = System.currentTimeMillis()
    }

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
                    // Hidden mode: left/right seek without revealing the
                    // controller (pure scrubbing on the invisible progress bar).
                    Key.DirectionLeft -> {
                        exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        exo.seekTo(
                            (exo.currentPosition + 10_000L)
                                .coerceAtMost(exo.duration.coerceAtLeast(0L))
                        )
                        true
                    }
                    Key.MediaPrevious -> {
                        neighborVideo(-1)?.let { onNavigateTo(it) }
                        true
                    }
                    Key.MediaNext -> {
                        neighborVideo(+1)?.let { onNavigateTo(it) }
                        true
                    }
                    // Reveal the controller; focus lands on the progress bar
                    // (handled by the LaunchedEffect above).
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> {
                        showController = true
                        true
                    }
                    // Physical play keys toggle playback directly, no reveal.
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        true
                    }
                    // Speed cycle reveals the controller so the new rate shows.
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
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            PlayerSurface(
                player = exo,
                modifier = Modifier.fillMaxSize(),
            )
        }

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
                positionText = "${index + 1} / ${mediaItems.size}",
                progressFocusRequester = progressFocusRequester,
                onHideController = { showController = false },
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
                    { neighborVideo(-1)?.let { onNavigateTo(it) }; bumpController() }
                } else null,
                onNext = if (hasNextVideo) {
                    { neighborVideo(+1)?.let { onNavigateTo(it) }; bumpController() }
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
    progressFocusRequester: FocusRequester,
    onHideController: () -> Unit,
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
    // Focus target for the play/pause button — the progress bar hands focus
    // down here on DirectionDown.
    val playPauseFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {

        ProgressBar(
            positionMs = pos,
            durationMs = dur,
            focusRequester = progressFocusRequester,
            onSeekBack = onSeekBack,
            onSeekFwd = onSeekFwd,
            onMoveDown = { playPauseFocusRequester.requestFocus() },
            onHide = onHideController,
        )
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
                modifier = Modifier.focusRequester(playPauseFocusRequester),
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
private fun ProgressBar(
    positionMs: Long,
    durationMs: Long,
    focusRequester: FocusRequester,
    onSeekBack: () -> Unit,
    onSeekFwd: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: () -> Unit,
) {
    val pct = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var isFocused by remember { mutableStateOf(false) }
    val barHeight = if (isFocused) 8.dp else 6.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { onSeekBack(); true }
                    Key.DirectionRight -> { onSeekFwd(); true }
                    Key.DirectionDown -> { onMoveDown(); true }
                    Key.DirectionUp -> { onHide(); true }
                    // OK on the progress bar: no-op by design (consume it so
                    // it doesn't bubble up to the page-level handler).
                    Key.DirectionCenter, Key.Enter -> true
                    else -> false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(3.dp),
                )
                .background(
                    if (isFocused) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(3.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(barHeight)
                    .background(Color(0xFFE53935), RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun ControllerButton(
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
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
