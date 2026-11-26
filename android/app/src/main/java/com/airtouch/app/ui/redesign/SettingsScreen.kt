package com.airtouch.app.ui.redesign

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AccentPreset
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.accentFromHue
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState
import kotlin.math.roundToInt

/*
 * Compose Route: "settings"
 * 蓝图规范：开关采用「外凹槽 + 带防滑纹路曲面滑块」结构；强调色与左手模式存入
 * DataStore 并驱动全局 AirTouchTheme 状态重组。
 * 第一步：状态由 [RedesignUiState] 内存驱动（预览里即可切深浅/强调色/左手/震动），
 * 第二步把这四个字段换成 DataStore Preferences 的 Flow 即可。
 */

@Composable
fun SettingsScreen(
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
    ) {
        // 返回 + 标题（圆钮 48dp / Icon 22dp / 行内间距 12dp）
        Row(verticalAlignment = Alignment.CenterVertically) {
            TactileCircleIconButton(
                onClick = { onAction(RedesignAction.Click("返回上一页")) },
                size = AirTouchDimens.iconButtonSize,
            ) {
                BackArrowIcon(modifier = Modifier.size(AirTouchDimens.iconSize), tint = colors.textPrimary)
            }
            Spacer(Modifier.size(AirTouchDimens.rowGap))
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
        }
        Spacer(Modifier.size(AirTouchDimens.blockGap))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
        ) {
            // 深色模式
            SettingToggleRow(
                title = "深色模式",
                subtitle = "自适应暗光环境新拟物光影",
                checked = state.darkTheme,
                onCheckedChange = { onAction(RedesignAction.SetDarkTheme(it)) },
            )

            // 强调色
            AccentPicker(state = state, onAction = onAction)

            // 鼠标左手模式（水平镜像翻转空中鼠标）
            SettingToggleRow(
                title = "鼠标左手模式",
                subtitle = "水平镜像翻转空中鼠标倾斜按键",
                checked = state.leftHandMode,
                onCheckedChange = { onAction(RedesignAction.SetLeftHand(it)) },
            )

            // 空中鼠标轴向：手机握持姿势因人而异，上下/左右各给一个反相开关
            SettingToggleRow(
                title = "鼠标反向（上下）",
                subtitle = "顶部向自己倾斜时，光标改成向下移动",
                checked = state.airMouseInvertY,
                onCheckedChange = { onAction(RedesignAction.SetAirMouseInvertY(it)) },
            )
            SettingToggleRow(
                title = "鼠标反向（左右）",
                subtitle = "手腕向左转时，光标改成向右移动",
                checked = state.airMouseInvertX,
                onCheckedChange = { onAction(RedesignAction.SetAirMouseInvertX(it)) },
            )

            // 线性马达震动反馈：打开开关时来一次 80ms 长振自检 —— 立刻能分辨
            // 「本机没马达」/「系统总振动关了」/「代码没接上」三种情况。
            val hapticContext = LocalContext.current
            val haptics = rememberAirTouchHaptics()
            SettingToggleRow(
                title = "线性马达震动反馈",
                subtitle = if (!haptics.hasMotor) {
                    "本机系统报告没有振动马达，任何应用都振不了"
                } else {
                    "模拟鼠标微动与滚轮刻度触感；打开开关会试振一次，没感觉请检查系统设置里的振动"
                },
                checked = state.hapticEnabled,
                onCheckedChange = { on ->
                    onAction(RedesignAction.SetHaptic(on))
                    if (on) {
                        haptics.test() // 不看开关状态，就当"试振"用
                        if (!haptics.hasMotor) {
                            Toast.makeText(hapticContext, "本机没有振动马达", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )

            // 触摸零延时：单击响应速度 vs 双击拖拽起点精度，二选一
            SettingToggleRow(
                title = "触摸零延时",
                subtitle = "开启后单击零延迟响应，但双击拖拽会有约 3px 起点偏移；关闭时支持像素级精准拖拽",
                checked = state.touchZeroLatency,
                onCheckedChange = { onAction(RedesignAction.SetTouchZeroLatency(it)) },
            )

            // 重置信任（通栏主按钮 54dp / 18dp）
            TactileSurfaceButton(
                onClick = { onAction(RedesignAction.ResetTrust) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AirTouchDimens.primaryButtonHeight),
                corner = AirTouchDimens.primaryButtonCorner,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("🗑️", fontSize = 13.sp)
                Spacer(Modifier.size(AirTouchDimens.rowGap))
                Text("重置所有信任设备", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.danger)
            }
        }

        Text(
            text = "AirTouch v1.0 · Kotlin Jetpack Compose",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AirTouchDimens.blockGap),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AirTouchTokens.colors
    TactileSurface(
        modifier = Modifier.fillMaxWidth(),
        corner = 20.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Text(subtitle, fontSize = 11.sp, color = colors.textSecondary)
            }
            TactileToggleSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AccentPicker(state: RedesignUiState, onAction: (RedesignAction) -> Unit) {
    val colors = AirTouchTokens.colors
    TactileSurface(
        modifier = Modifier.fillMaxWidth(),
        corner = 20.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("强调色", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text("按钮激活、滑块与锁定键高亮", fontSize = 11.sp, color = colors.textSecondary)
                }
                NeumorphicWell(corner = 8.dp, contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.customAccent != null) "CUSTOM" else state.accent.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
            }
            Spacer(Modifier.size(AirTouchDimens.rowGap))
            NeumorphicWell(modifier = Modifier.fillMaxWidth(), corner = 16.dp) {
                // 4 个预设色板用 weight(1f) 均分，间距 12dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentPreset.entries.forEach { preset ->
                        TactileSurfaceButton(
                            onClick = { onAction(RedesignAction.SetAccent(preset)) },
                            modifier = Modifier
                                .weight(1f)
                                .height(AirTouchDimens.iconButtonSize),
                            corner = AirTouchDimens.radiusPill,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(preset.accent),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(AirTouchDimens.rowGap))
            // 自由选色：拖动色相条，任意颜色都能当强调色
            HuePicker(
                current = state.customAccent,
                onPick = { onAction(RedesignAction.SetCustomAccent(it)) },
            )
        }
    }
}

/** 色相滑杆：点击或左右拖动选任意 Hue，实时派生整套强调色。 */
@Composable
private fun HuePicker(current: Color?, onPick: (Color) -> Unit) {
    val colors = AirTouchTokens.colors
    val rainbow = listOf(
        Color(0xFFFF4B4B), Color(0xFFFFC24B), Color(0xFF8BE04B),
        Color(0xFF4BE0C8), Color(0xFF4B8BE0), Color(0xFF9B4BE0),
        Color(0xFFE04B9B), Color(0xFFFF4B4B),
    )
    var widthPx by remember { mutableFloatStateOf(1f) }
    var hue by remember {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV((current ?: Color(0xFFF5B800)).toArgb(), hsv)
        mutableFloatStateOf(hsv[0])
    }

    fun pick(x: Float) {
        val h = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * 360f
        hue = h
        onPick(accentFromHue(h))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("自由选色 · 拖动色相条", fontSize = 11.sp, color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.horizontalGradient(rainbow))
                .border(1.dp, colors.kbKeyBorder, RoundedCornerShape(13.dp))
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { pos -> pick(pos.x) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        pick(change.position.x)
                        change.consume()
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        val x = ((hue / 360f) * widthPx - 10.dp.toPx()).roundToInt()
                            .coerceAtLeast(0)
                        IntOffset(x, 3.dp.toPx().roundToInt())
                    }
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .border(2.dp, accentFromHue(hue), RoundedCornerShape(50)),
            )
        }
    }
}

@Preview(name = "02 · 设置 (Light)", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(
        darkTheme = state.darkTheme,
        accent = state.accent,
        leftHandMode = state.leftHandMode,
        hapticEnabled = state.hapticEnabled,
    ) {
        SettingsScreen(state = state, onAction = onAction)
    }
}
