package com.haoli.swipegallery.feature.viewer

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 视频播放器。
 *
 * 刻意**不用** `PlayerView`：它内部有触摸处理，会消费掉指针事件，
 * 导致外层的滑卡手势失效 —— 用户对着视频上滑会发现删不掉。
 *
 * 这里改用裸的 `TextureView` 作为视频表面（不用 SurfaceView 是因为它在独立的
 * 窗口层渲染，Compose 覆盖层会被压到下面去），播放控制由 Compose 自己画，
 * 手势层压在最上面，滑卡优先级得到保证。
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

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        exoPlayer.addListener(listener)

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

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
