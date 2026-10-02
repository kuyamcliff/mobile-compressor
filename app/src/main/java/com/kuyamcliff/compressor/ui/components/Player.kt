package com.kuyamcliff.compressor.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.delay

/**
 * Android-native playback (Media3/ExoPlayer) for previews — independent of the
 * compression engine (PRD §182). Formats the platform cannot play simply show
 * an error; compression still works.
 */
@Composable
fun rememberPlayer(uri: Uri?, clipStartUs: Long = 0, clipEndUs: Long = Long.MIN_VALUE): ExoPlayer {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    LaunchedEffect(uri, clipStartUs, clipEndUs) {
        if (uri != null) {
            val item = MediaItem.Builder().setUri(uri).apply {
                if (clipEndUs != Long.MIN_VALUE || clipStartUs > 0) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartUs / 1000)
                            .apply { if (clipEndUs != Long.MIN_VALUE) setEndPositionMs(clipEndUs / 1000) }
                            .build(),
                    )
                }
            }.build()
            player.setMediaItem(item)
            player.prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    return player
}

/** Video surface that respects the video's aspect ratio; supports Compose transforms. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: ExoPlayer, modifier: Modifier = Modifier, rotationDegrees: Int = 0) {
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val shown = if (rotationDegrees % 180 != 0) 1f / aspect else aspect
    Box(modifier.fillMaxWidth().aspectRatio(shown.coerceIn(0.3f, 4f)).background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            update = { player.setVideoTextureView(it) },
            modifier = Modifier
                .then(if (rotationDegrees % 180 != 0) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
                .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
        )
    }
}

/** Shared transport controls; drives one or more players in sync (split-screen compare). */
@Composable
fun PlayerControls(
    players: List<ExoPlayer>,
    fps: Double,
    modifier: Modifier = Modifier,
    onPosition: (Long) -> Unit = {},
    onRotate: (() -> Unit)? = null,
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
) {
    val main = players.firstOrNull() ?: return
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var muted by remember { mutableStateOf(false) }
    LaunchedEffect(main) {
        while (true) {
            playing = main.isPlaying
            position = main.currentPosition.coerceAtLeast(0)
            duration = main.duration.takeIf { it > 0 } ?: 0
            onPosition(position * 1000)
            // Keep secondary players aligned with the first (drift correction).
            players.drop(1).forEach { p ->
                if (kotlin.math.abs(p.currentPosition - position) > 120) p.seekTo(position)
            }
            delay(200)
        }
    }
    fun seek(ms: Long) = players.forEach { it.seekTo(ms.coerceIn(0, maxOf(duration, 0))) }
    val frameMs = if (fps > 0) (1000.0 / fps).toLong().coerceAtLeast(1) else 33L
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { seek((it * duration).toLong()) }; dragging = null },
            modifier = Modifier.semantics {
                contentDescription = "Seek"
                stateDescription = "${Format.duration(position)} of ${Format.duration(duration)}"
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = {
                if (playing) players.forEach { it.pause() } else players.forEach { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() }
            }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(if (playing) R.string.cd_pause_playback else R.string.cd_play))
            }
            IconButton(onClick = { players.forEach { it.pause() }; seek(position - frameMs) }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_frame_back))
            }
            IconButton(onClick = { players.forEach { it.pause() }; seek(position + frameMs) }) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_frame_forward))
            }
            Text("${Format.duration(position)} / ${Format.duration(duration)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = {
                muted = !muted
                // Only the first player is audible; the second is always muted in compare mode.
                players.forEachIndexed { i, p -> p.volume = if (muted || i > 0) 0f else 1f }
            }) {
                Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(if (muted) R.string.cd_unmute else R.string.cd_mute))
            }
            if (onRotate != null) IconButton(onClick = onRotate) { Icon(Icons.Filled.RotateRight, contentDescription = stringResource(R.string.cd_rotate_view)) }
            if (onFullscreen != null) IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen))
            }
        }
    }
    LaunchedEffect(players.size) { players.forEachIndexed { i, p -> if (i > 0) p.volume = 0f } }
}

/** Source preview with all controls (PRD §11). */
@Composable
fun SourcePlayer(uri: Uri, fps: Double, modifier: Modifier = Modifier, onPosition: (Long) -> Unit = {}) {
    val player = rememberPlayer(uri)
    var rotation by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    Column(modifier) {
        PlayerSurface(player, rotationDegrees = rotation)
        PlayerControls(listOf(player), fps, onPosition = onPosition, onRotate = { rotation = (rotation + 90) % 360 }, onFullscreen = { fullscreen = true })
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black), verticalArrangement = Arrangement.Center) {
                PlayerSurface(player, rotationDegrees = rotation)
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    PlayerControls(listOf(player), fps, onRotate = { rotation = (rotation + 90) % 360 }, fullscreen = true, onFullscreen = { fullscreen = false })
                }
            }
        }
    }
}
