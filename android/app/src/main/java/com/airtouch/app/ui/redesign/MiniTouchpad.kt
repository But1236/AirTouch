package com.airtouch.app.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction

/**
 * 扁平键盘之上的新拟物点阵微触控板（4:3，1dp 细描边）。
 * 没有标题文字、没有关闭叉：整块几乎全是点阵区，顶部只有一条极细的拖动把手。
 *
 * 原本是 `LandscapeKeyboardScreen.kt` 里的私有实现（全屏横屏键盘页在用）。竖屏（半屏）键盘页也要
 * 放同一块触控板，所以抽成这个文件并改成 `internal` —— 两页共用同一份 UI 与同一套拖动把手逻辑，
 * 以后只改一处。尺寸由调用方用 `modifier` 决定（两页各自按可用空间算 4:3）。
 *
 * @param onDrag 拖动把手的水平位移（像素）：调用方据此移动整块触控板，并决定它被夹在什么范围内。
 */
@Composable
internal fun MiniTouchpadBubble(
    modifier: Modifier,
    onAction: (RedesignAction) -> Unit,
    zeroLatency: Boolean,
    sensitivity: Float,
    onDrag: (Float) -> Unit,
) {
    val colors = AirTouchTokens.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.bgBase)
            .border(1.dp, colors.accent, RoundedCornerShape(18.dp))
            .padding(3.dp),
    ) {
        // 极细拖动把手：按住这里可左右移动触控板
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {},
                        onDragCancel = {},
                        onDrag = { change, drag -> onDrag(drag.x); change.consume() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent.copy(alpha = 0.65f)),
            )
        }
        DotMatrixTouchpad(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            corner = 12.dp,
            showCoordsLabel = false,
            zeroLatency = zeroLatency,
            sensitivity = sensitivity,
            onAction = onAction,
        )
    }
}
