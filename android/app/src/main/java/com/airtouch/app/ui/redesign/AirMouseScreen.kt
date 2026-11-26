package com.airtouch.app.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * Compose Route: "air_mouse"
 * 第 2 项重构：
 *  - 滚轮区绑定 detectVerticalDragGestures，防滑刻度纹路随手势实时滚动；
 *    每跨一个刻度触发 HapticFeedbackConstants.CLOCK_TICK 机械震动并回调 onScroll(delta)；
 *  - 「复位」键从右下角移到滚轮凹槽正下方、左右键之间靠下，居中对称便于拇指盲操。
 * 其余：倾斜错落布局 + 左手模式 graphicsLayer(scaleX = -1f) 镜像。
 */

@Composable
fun AirMouseScreen(
    modifier: Modifier = Modifier,
    state: RedesignUiState = RedesignUiState(),
    onAction: (RedesignAction) -> Unit = redesignLogAction,
    /** 第二步直接把这里绑到 AirTouchClient 的 scroll 帧即可。 */
    onScroll: (Int) -> Unit = { delta -> onAction(RedesignAction.MouseScroll(delta)) },
) {
    val colors = AirTouchTokens.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .padding(AirTouchDimens.phonePadding),
        verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
    ) {
        // 顶部：状态胶囊 + 设置（圆钮 48dp / Icon 22dp）
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

        // 人体工学错落按键簇（左手模式水平镜像）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(390.dp)
                .graphicsLayer {
                    scaleX = if (state.leftHandMode) -1f else 1f
                },
            contentAlignment = Alignment.Center,
        ) {
            // 左右键 weight(1f) 等宽饱满；中间滚轮槽固定 60dp 宽；行内间距 12dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
                verticalAlignment = Alignment.Top,
            ) {
                // 左键：下沉 40dp 贴合拇指
                ErgonomicMouseKey(
                    modifier = Modifier
                        .weight(1f)
                        .height(AirTouchDimens.mouseKeyHeight)
                        .offset(y = 40.dp),
                    label = "左键",
                    sub = "LMB",
                    mirrored = state.leftHandMode,
                    held = state.heldButton == "left",
                    onHoldChange = { down -> onAction(RedesignAction.SetButton("left", down)) },
                )

                // 中央：可垂直拖动的滚轮凹槽（固定 60dp 宽 × 200dp 高）
                Column(
                    modifier = Modifier
                        .width(AirTouchDimens.wheelSlotWidth)
                        .offset(y = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScrollWheelArea(
                        mirrored = state.leftHandMode,
                        onAction = onAction,
                        onScroll = onScroll,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 右键：上抬 8dp
                ErgonomicMouseKey(
                    modifier = Modifier
                        .weight(1f)
                        .height(AirTouchDimens.mouseKeyHeight)
                        .offset(y = (-8).dp),
                    label = "右键",
                    sub = "RMB",
                    mirrored = state.leftHandMode,
                    held = state.heldButton == "right",
                    onHoldChange = { down -> onAction(RedesignAction.SetButton("right", down)) },
                )
            }

            // 复位（陀螺仪姿态归零）—— 128×48，居中位于滚轮凹槽正下方
            TactileSurfaceButton(
                onClick = { onAction(RedesignAction.Click("陀螺仪姿态归零复位")) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(AirTouchDimens.resetButtonWidth)
                    .height(AirTouchDimens.resetButtonHeight),
                corner = AirTouchDimens.radiusPill,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    "复位",
                    modifier = Modifier.unmirror(state.leftHandMode),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
        }

        // 把「灵敏度 + 返回/暂停/传文件」压到页面底部，和触控板页对齐
        // （鼠标页的按键簇是固定高度，不加这个 Spacer 时整块会浮在中间，两页位置就不一致）
        Spacer(Modifier.weight(1f))

        // 灵敏度 + 返回/暂停/传文件：与触控板页、键盘页完全一样
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

/** 可拖动的滚轮：纹路实时滚动 + CLOCK_TICK 机械刻度震动 + onScroll 回调。 */
@Composable
private fun ScrollWheelArea(
    mirrored: Boolean,
    onAction: (RedesignAction) -> Unit,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    // 滚轮刻度：稍强 click 档（真马达，见 rememberAirTouchHaptics）
    val haptics = rememberAirTouchHaptics()
    // State<Float> 传给 ribbedScrollWheel：拖动时只重绘纹路，不重组整页
    val ribOffset = remember { mutableFloatStateOf(0f) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    NeumorphicWell(
        modifier = modifier
            .width(AirTouchDimens.wheelSlotWidth)
            .height(AirTouchDimens.wheelSlotHeight),
        corner = AirTouchDimens.radiusPill,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 4.dp)
                .pointerInput(Unit) {
                    val tick = 14.dp.toPx()
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, drag ->
                            change.consume()
                            ribOffset.floatValue -= drag
                            accumulated += drag
                            while (accumulated >= tick) {
                                accumulated -= tick
                                haptics.click()
                                onScroll(1)
                            }
                            while (accumulated <= -tick) {
                                accumulated += tick
                                haptics.click()
                                onScroll(-1)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 真实滚轮形状的拟态圆柱：横向圆柱高光 + 随手势滚动的防滑肋纹 + 按下内凹/震动
            TactileScrollWheel(
                scrollOffset = ribOffset,
                onClick = { onAction(RedesignAction.Click("鼠标中键点击")) },
                modifier = Modifier.fillMaxSize(),
            )
            // 滚轮标签（镜像时反向一次以保持可读）
            Box(
                modifier = Modifier
                    .unmirror(mirrored)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.bgBase)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text("滚轮", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun ErgonomicMouseKey(
    label: String,
    sub: String,
    mirrored: Boolean,
    held: Boolean,
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    TactileHoldButton(
        onHoldChange = onHoldChange,
        held = held,
        modifier = modifier,
        corner = 28.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                modifier = Modifier.unmirror(mirrored),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
            )
            Text(
                sub,
                modifier = Modifier.unmirror(mirrored),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textMuted,
            )
        }
    }
}

/** `graphicsLayer(scaleX = -1f)` 二次翻转，使镜像后的文字仍可读。 */
private fun Modifier.unmirror(enabled: Boolean): Modifier = this.graphicsLayer {
    scaleX = if (enabled) -1f else 1f
}

@Preview(name = "03 · 鼠标模式 (Light)", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun AirMouseScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(
        darkTheme = state.darkTheme,
        accent = state.accent,
        leftHandMode = state.leftHandMode,
        hapticEnabled = state.hapticEnabled,
    ) {
        AirMouseScreen(state = state, onAction = onAction)
    }
}
