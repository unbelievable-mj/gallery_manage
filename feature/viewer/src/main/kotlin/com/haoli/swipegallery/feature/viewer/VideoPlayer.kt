package com.haoli.swipegallery.feature.viewer

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.haoli.swipegallery.core.common.formatDuration
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val POSITION_POLL_INTERVAL_MS = 400L

/**
 * 视频播放器，含可拖拽的进度条。
 *
 * 刻意**不用** `PlayerView`：它内部有触摸处理，会消费掉指针事件，
 * 导致外层的滑卡手势失效 —— 用户对着视频上滑会发现删不掉。
 *
 * 也不用 `SurfaceView` 作视频表面：它在独立窗口层渲染，Compose 的覆盖层
 * （播放按钮、进度条）会被压到下面看不见。改用 `TextureView`。
 *
 * [active] 为 false 时暂停 —— HorizontalPager 会预组合相邻页，
 * 不控制的话会出现多个视频同时播放。
 */
@Composable
fun VideoPlayer(
    uri: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    var isPlaying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    // 拖拽期间以手指位置为准，否则位置轮询会把滑块拽回去
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

    // 视频的真实宽高比。0 表示还不知道，此时退回铺满。
    //
    // 这个值必须取到：TextureView 与 SurfaceView 不同，它**没有内建的比例处理**，
    // 会把画面拉伸到视图自身的尺寸。视图若是铺满整屏，横屏视频就会被拉成竖屏。
    var videoAspect by remember { mutableFloatStateOf(0f) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 时长要等播放器进入 READY 才可靠，否则会拿到 TIME_UNSET
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        // 注册监听器之前播放器可能已经拿到尺寸了，补读一次
        exoPlayer.videoSize.let { known ->
            if (known.width > 0 && known.height > 0) {
                videoAspect = known.width.toFloat() / known.height.toFloat()
            }
        }

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    LaunchedEffect(active) {
        exoPlayer.playWhenReady = active
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val known = exoPlayer.duration
                if (known > 0L) durationMs = known
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    // 居中：视频按原始比例缩放后，四周留黑边（与正常播放器一致），
    // 而不是拉伸铺满
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (videoAspect > 0f) {
                Modifier.aspectRatio(videoAspect)
            } else {
                // 尺寸未知时先铺满，拿到尺寸后会自动收敛到正确比例
                Modifier.fillMaxSize()
            },
            factory = { ctx ->
                TextureView(ctx).apply {
                    // 兜底：即使没有上层的 Compose 手势层，也不让视频表面吞事件
                    isClickable = false
                    isFocusable = false
                    setOnTouchListener { _, _ -> false }
                    exoPlayer.setVideoTextureView(this)
                }
            },
        )

        // 手势/点击层压在最上面。
        // clickable 只处理点击、不消费拖拽，因此外层的 draggable 依然拿得到垂直滑动。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
        )

        if (!isPlaying && !failed) {
            PlayOverlay()
        }

        if (failed) {
            Text(
                text = "这个视频无法播放",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        if (durationMs > 0L) {
            VideoSeekBar(
                positionMs = if (scrubbing) scrubMs else positionMs,
                durationMs = durationMs,
                onScrub = { target ->
                    scrubbing = true
                    scrubMs = target
                },
                onScrubFinished = { target ->
                    exoPlayer.seekTo(target)
                    positionMs = target
                    scrubbing = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 进度条。
 *
 * 自己画而不用 Material3 的 `Slider`，原因有二：
 *  1. Slider 的构造签名在两个大版本之间改过，写错会直接编译失败
 *  2. Slider 内部对水平拖拽的处理与外层 HorizontalPager 会打架 ——
 *     这里用 `draggable` 明确消费水平方向，拖进度条不会把整页翻走
 */
@Composable
private fun VideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableStateOf(1) }
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDuration(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .onSizeChanged { trackWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(durationMs, trackWidthPx) {
                        detectTapGestures { offset ->
                            val target = (offset.x / trackWidthPx * durationMs)
                                .toLong()
                                .coerceIn(0L, durationMs)
                            onScrub(target)
                            onScrubFinished(target)
                        }
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            val base = positionMs + (delta / trackWidthPx * durationMs).toLong()
                            onScrub(base.coerceIn(0L, durationMs))
                        },
                        onDragStopped = { onScrubFinished(positionMs) },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset((fraction * trackWidthPx).roundToInt() - 7, 0) }
                        .size(14.dp)
                        .background(Color.White, CircleShape),
                )
            }

            Spacer(Modifier.width(10.dp))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

/** 暂停态中央的播放三角。用图形拼而非图标字体，少一个依赖。 */
@Composable
private fun PlayOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
