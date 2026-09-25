package com.haoli.swipegallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次启动的免责声明。
 *
 * 只在第一次启动时弹一次，确认后写进设置不再打扰。
 * 内容都是可以验证的事实，不是套话 —— 尤其「没有申请网络权限」这一条，
 * 用户可以在系统设置里自己看到。
 */
@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        // 点外部或返回键也算确认：这是一个「告知」，不是需要同意的协议，
        // 卡住用户没有意义
        onDismissRequest = onAccept,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "使用前请知悉",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Point(
                    title = "完全离线",
                    body = "应用没有申请网络权限，不会联网，也不存在上传数据的通道。" +
                        "你可以在系统设置的应用权限里自行核实。",
                )
                Spacer(Modifier.height(14.dp))
                Point(
                    title = "数据不出本机",
                    body = "照片、视频与设置都留在你的手机上。我们不收集、不上传任何信息。",
                )
                Spacer(Modifier.height(14.dp))
                Point(
                    title = "删除请谨慎",
                    body = "滑卡删除会先进回收站，可以随时取回；" +
                        "在回收站里点「永久删除」之后才真正释放空间，且不可恢复。",
                )
                Spacer(Modifier.height(14.dp))
                Point(
                    title = "自用工具，按现状提供",
                    body = "这是个人自用的小工具，开源出来给有同样需求的人用。" +
                        "不提供任何担保，也不对误删造成的损失负责。",
                )
            }
        },
        confirmButton = {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    text = "我知道了",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clickable(onClick = onAccept),
                )
            }
        },
    )
}

@Composable
private fun Point(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}