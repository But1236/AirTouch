package com.airtouch.app.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState

/*
 * Compose Route: "touchpad"
 * 蓝图规范：将左键 / 滚轮 / 右键优化移至点阵区「正下方」，符合笔记本肌肉记忆；
 * 点阵区直接响应多指手势（见 [DotMatrixTouchpad]）：
 *  - 轻点 = 左键单击；双击 = 左键双击；双击后按住 = 按住左键拖拽；
 *  - 双指捏合 = 缩放（按协议发 down / move / up 三阶段，电脑端才认得出「一次完整手势」）。
 */

@Composable
fun TouchpadScreen(
    modifier: Modifier = Modifier,
    state: RedesignUiState = RedesignUiState(),
    onAction: (RedesignAction) -> Unit = redesignLogAction,
) {
    val colors = AirTouchTokens.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .padding(AirTouchDimens.phonePadding),
        verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
    ) {
        // 顶部：状态胶囊 + 设置（与鼠标页一致；原来的手势说明文字已删）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionPill(connected = state.connected, latencyMs = state.latencyMs)
            TactileCircleIconButton(
                onClick = { onAction(RedesignAction.Click("打开设置")) },
                size = AirTouchDimens.iconButtonSize,
            ) {
                GearIcon(modifier = Modifier.size(AirTouchDimens.iconSize), tint = colors.textPrimary)
            }
        }

        // 大面积点阵触控板（内凹槽）
        DotMatrixTouchpad(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            corner = 24.dp,
            zeroLatency = state.touchZeroLatency,
            sensitivity = state.sensitivity,
            onAction = onAction,
        )

        // 物理左 / 中(滚轮) / 右键 —— 严格位于点阵区正下方
        // 左右键 weight(1f) + 50dp/16dp，中间滚轮固定 56dp，行内间距 12dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
        ) {
            TouchpadPhysicalKey(
                label = "左键",
                held = state.heldButton == "left",
                onHoldChange = { down -> onAction(RedesignAction.SetButton("left", down)) },
                modifier = Modifier
                    .weight(1f)
                    .height(AirTouchDimens.pairButtonHeight),
            )
            TouchpadWheelKey(
                modifier = Modifier
                    .width(AirTouchDimens.touchpadWheelWidth)
                    .height(AirTouchDimens.pairButtonHeight),
                onClick = { onAction(RedesignAction.TouchpadClick("middle")) },
                onScroll = { delta -> onAction(RedesignAction.TouchpadScroll(delta, false)) },
            )
            TouchpadPhysicalKey(
                label = "右键",
                held = state.heldButton == "right",
                onHoldChange = { down -> onAction(RedesignAction.SetButton("right", down)) },
                modifier = Modifier
                    .weight(1f)
                    .height(AirTouchDimens.pairButtonHeight),
            )
        }

        // 灵敏度 + 返回/暂停/传文件：与鼠标页、键盘页完全一样
        ModeControls(
            sensitivity = state.sensitivity,
            paused = state.paused,
            onSensitivityChange = { onAction(RedesignAction.SetSensitivity(it)) },
            onBack = { onAction(RedesignAction.Click("返回主页")) },
            onTogglePause = { onAction(RedesignAction.SetPaused(!state.paused)) },
            onFile = { onAction(RedesignAction.OpenFilePicker) },
        )
    }
}

@Composable
private fun TouchpadPhysicalKey(
    label: String,
    held: Boolean,
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    TactileHoldButton(
        onHoldChange = onHoldChange,
        held = held,
        modifier = modifier,
        corner = AirTouchDimens.pairButtonCorner,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
    }
}

@Composable
private fun TouchpadWheelKey(
    onClick: () -> Unit,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    // 滚轮刻度：稍强 click 档（真马达，见 rememberAirTouchHaptics）
    val haptics = rememberAirTouchHaptics()
    val ribOffset = remember { mutableFloatStateOf(0f) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            val tick = 14.dp.toPx()
            detectVerticalDragGestures(
                onVerticalDrag = { change, drag ->
                    change.consume()
                    ribOffset.floatValue -= drag
                    accumulated += drag
                    while (accumulated >= tick) {
                        accumulated -= tick
                        haptics.click()
                        onScroll(15)
                    }
                    while (accumulated <= -tick) {
                        accumulated += tick
                        haptics.click()
                        onScroll(-15)
                    }
                },
            )
        },
    ) {
        TactileSurfaceButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            corner = AirTouchDimens.pairButtonCorner,
            contentPadding = PaddingValues(0.dp),
        ) { /* 纹路由上层覆盖 */ }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(AirTouchDimens.pairButtonCorner))
                .ribbedScrollWheel(ribOffset),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.bgBase)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("滚轮", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.textSecondary)
        }
    }
}

@Preview(name = "04 · 触控板模式 (Light)", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun TouchpadScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(darkTheme = state.darkTheme, accent = state.accent) {
        TouchpadScreen(state = state, onAction = onAction)
    }
}
