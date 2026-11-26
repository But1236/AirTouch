package com.airtouch.app.ui.redesign.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 全局动态主题。
 * 蓝图 SettingsScreen 规范：「强调色与左手模式存入 DataStore 并驱动全局 AirTouchTheme 状态重组」。
 * 第一步先用 [RedesignUiState] 驱动（纯内存），第二步把 darkTheme/accent/leftHand/haptic
 * 换成 DataStore 的 Flow 即可，页面代码不需要改。
 */

val LocalAirTouchColors = staticCompositionLocalOf { lightAirTouchColors(AccentPreset.YELLOW) }
val LocalAirTouchAccent = staticCompositionLocalOf { AccentPreset.YELLOW }
val LocalLeftHandMode = staticCompositionLocalOf { false }
val LocalHapticEnabled = staticCompositionLocalOf { true }

/** 几何 Token —— 蓝图 `--radius-*` / 点阵 / 描边。 */
object AirTouchDimens {
    val radiusPhone: Dp = 38.dp
    val radiusCard: Dp = 26.dp
    val radiusBtn: Dp = 20.dp
    val radiusKey: Dp = 6.dp
    val radiusPill: Dp = 999.dp

    val dotSpacing: Dp = 16.dp
    val dotRadius: Dp = 1.45.dp
    val dotOffset: Dp = 8.dp

    /** 凸起按键为柔和光影预留的内边距（放大后光晕更舒展，不再干瘪）。 */
    val tactileEdge: Dp = 9.dp

    /** =============== 《AirTouch 按钮与间距统一设计规范》 =============== */

    /** 1. 统一间距：所有页面左右外边距 20dp */
    val pageMargin: Dp = 20.dp
    /** 同一行内并排按钮间距 12dp（Arrangement.spacedBy(rowGap)） */
    val rowGap: Dp = 12.dp
    /** 各功能区块之间的垂直间距 16dp */
    val blockGap: Dp = 16.dp

    /** 2. 圆形图标按钮 48×48，内部 Icon 22dp */
    val iconButtonSize: Dp = 48.dp
    val iconSize: Dp = 22.dp

    /** 通栏主按钮：fillMaxWidth() + 54dp 高 + 18dp 圆角 */
    val primaryButtonHeight: Dp = 54.dp
    val primaryButtonCorner: Dp = 18.dp

    /** 并排双按钮：50dp 高 + 16dp 圆角 + weight(1f) */
    val pairButtonHeight: Dp = 50.dp
    val pairButtonCorner: Dp = 16.dp
    /** 触控板中间滚轮固定宽 56dp */
    val touchpadWheelWidth: Dp = 56.dp

    /** 鼠标模式：左右键 215dp 等宽，滚轮槽 60×200，复位 128×48 */
    val mouseKeyHeight: Dp = 215.dp
    val wheelSlotWidth: Dp = 60.dp
    val wheelSlotHeight: Dp = 200.dp
    val resetButtonWidth: Dp = 128.dp
    val resetButtonHeight: Dp = 48.dp

    /** 底部模式切换器：整体 54dp / 圆角 27dp / 滑块 44dp / 段间 0dp */
    val segmentedHeight: Dp = 54.dp
    val segmentedCorner: Dp = 27.dp
    val segmentedThumbHeight: Dp = 44.dp

    /** 3. 键盘按键高度与间距 */
    val portraitKeyHeight: Dp = 44.dp
    val t9KeyHeight: Dp = 54.dp
    val landscapeKeyHeight: Dp = 40.dp
    val keyGap: Dp = 5.dp

    /** 纯内嵌圆柱滑块轨道高度 */
    val sliderTrackHeight: Dp = 24.dp

    val minTouchTarget: Dp = 48.dp
    val hairline: Dp = 1.dp
    val phonePadding: Dp = pageMargin
}

/** 只读访问器，避免在 Composable 里到处写 `LocalXxx.current`。 */
object AirTouchTokens {
    val colors: AirTouchColors
        @Composable @ReadOnlyComposable get() = LocalAirTouchColors.current

    val accent: AccentPreset
        @Composable @ReadOnlyComposable get() = LocalAirTouchAccent.current

    val leftHandMode: Boolean
        @Composable @ReadOnlyComposable get() = LocalLeftHandMode.current

    val hapticEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalHapticEnabled.current
}

/**
 * 唯一主题入口。浅/深两套色值 + 4 套预设强调色 + 自由自定义颜色，全部由参数决定。
 * [customAccent] 非空时从该基色派生整套强调色（设置页的色相滑杆），否则用 [accent] 预设。
 */
@Composable
fun AirTouchTheme(
    darkTheme: Boolean = false,
    accent: AccentPreset = AccentPreset.YELLOW,
    customAccent: Color? = null,
    leftHandMode: Boolean = false,
    hapticEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val tokens = customAccent?.let { customAccentTokens(it) } ?: accent.toTokens()
    val colors = remember(darkTheme, tokens) {
        if (darkTheme) darkAirTouchColors(tokens) else lightAirTouchColors(tokens)
    }
    CompositionLocalProvider(
        LocalAirTouchColors provides colors,
        LocalAirTouchAccent provides accent,
        LocalLeftHandMode provides leftHandMode,
        LocalHapticEnabled provides hapticEnabled,
        content = content,
    )
}
