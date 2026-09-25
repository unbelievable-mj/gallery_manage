package com.haoli.swipegallery.feature.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.haoli.swipegallery.core.designsystem.theme.DeleteAccent
import com.haoli.swipegallery.core.designsystem.theme.KeepAccent
import kotlin.math.abs
import com.haoli.swipegallery.core.model.SwipeEffect

/** 退场动画时长。太短的话，视觉反馈还没来得及看清卡片就没了。 */
private const val EXIT_DURATION_MS = 320

/** 触发阈值：垂直位移超过屏高的这个比例即判定为一次决策。 */
private const val TRIGGER_FRACTION = 0.22f

/** 甩动速度阈值（px/s）。快速轻扫即使位移不够也应触发。 */
private const val FLICK_VELOCITY = 1200f

/** 最大拖拽距离，超过就不再跟随手指，避免卡片被拖出屏幕太远。 */
private const val MAX_DRAG_FRACTION = 0.6f

/**
 * 可上下滑出的卡片容器。
 *
 * 轴仲裁交给 [draggable]：它只拦截垂直拖拽，水平方向的指针事件原样漏给外层的
 * HorizontalPager。这比自己写 `pointerInput` 抢事件可靠得多 —— 手写方案里
 * 一旦消费时机不对，就会出现「想翻页却触发了删除」这类灾难性误操作。
 *
 * 上滑（负向）判定为删除，下滑（正向）判定为保留。
 */
@Composable
fun SwipeableCard(
    onSwipe: (SwipeDirection) -> Unit,
    effect: SwipeEffect = SwipeEffect.NONE,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val triggerPx = screenHeightPx * TRIGGER_FRACTION
    val maxDragPx = screenHeightPx * MAX_DRAG_FRACTION

    var offsetY by remember { mutableFloatStateOf(0f) }

    val dragState = rememberDraggableState { delta ->
        offsetY = (offsetY + delta).coerceIn(-maxDragPx, maxDragPx)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val current = offsetY
                    val shouldTrigger = abs(current) > triggerPx || abs(velocity) > FLICK_VELOCITY

                    if (shouldTrigger) {
                        val isDelete = current < 0f
                        val target = if (isDelete) -maxDragPx else maxDragPx

                        // 退场动画要够长，否则动效一闪而过看不见。
                        // 之前是 150ms，快速轻扫时几乎察觉不到。
                        animate(
                            initialValue = current,
                            targetValue = target,
                            animationSpec = tween(durationMillis = EXIT_DURATION_MS),
                        ) { value, _ -> offsetY = value }

                        onSwipe(if (isDelete) SwipeDirection.UP else SwipeDirection.DOWN)

                        // 队列已切换到下一张，立即复位偏移，让新卡片从正中开始
                        offsetY = 0f
                    } else {
                        animate(
                            initialValue = current,
                            targetValue = 0f,
                            animationSpec = spring(),
                        ) { value, _ -> offsetY = value }
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 只做垂直位移，不加旋转。
                    // 旋转会让卡片看起来沿弧线甩出去（像扇子展开），
                    // 而删除 / 保留这个动作是「直上直下」的，弧线反而误导方向感。
                    translationY = offsetY

                    // 视觉反馈只在「上滑 = 扔掉」这个方向做。
                    // 下滑是保留，语义不同 —— 给一个不该被强调的动作加动效，
                    // 反而会让人以为「保留」也是一种需要确认的操作。
                    val progress = if (offsetY < 0f) {
                        (abs(offsetY) / triggerPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    when (effect) {
                        SwipeEffect.NONE -> Unit

                        // 淡出：最克制，只是「这张要没了」
                        SwipeEffect.FADE -> {
                            alpha = 1f - progress * 0.8f
                        }

                        // 缩小：卡片向中心收拢，像被吸走
                        SwipeEffect.SHRINK -> {
                            val scale = 1f - progress * 0.22f
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - progress * 0.5f
                        }

                        // 压扁：纵向压扁，像被上方抽走
                        SwipeEffect.SQUASH -> {
                            scaleY = 1f - progress * 0.4f
                            alpha = 1f - progress * 0.7f
                        }
                    }
                },
        ) {
            content()
        }

        SwipeHint(offsetY = offsetY, triggerPx = triggerPx)
    }
}

/**
 * 拖拽过程中的意图提示。
 *
 * 位移过半阈值后才开始显现，避免轻微抖动就闪出图标。
 */
@Composable
private fun BoxScope.SwipeHint(offsetY: Float, triggerPx: Float) {
    if (abs(offsetY) < 12f) return

    val isDelete = offsetY < 0f
    val strength = (abs(offsetY) / triggerPx).coerceIn(0f, 1f)
    val accent = if (isDelete) DeleteAccent else KeepAccent
    val label = if (isDelete) "删除" else "保留"

    Box(
        modifier = Modifier
            .align(if (isDelete) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(vertical = 56.dp)
            .alpha(strength),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = accent.copy(alpha = 0.85f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
        }
    }
}
