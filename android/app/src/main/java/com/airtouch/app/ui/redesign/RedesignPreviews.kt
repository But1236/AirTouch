package com.airtouch.app.ui.redesign

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.airtouch.app.ui.redesign.theme.AccentPreset
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState

/*
 * 第一步的 @Preview 汇总：
 *  - 6 个页面各自的浅色预览在同文件的 XxxScreenPreview；
 *  - 本文件给出「深色 / 4 套强调色 / 左手镜像 / 9 键 / 悬浮微触控板」等状态变体，
 *    用于在 Android Studio 里逐屏核对质感与排版，全部不依赖任何业务接口。
 *
 * 所有预览都通过 rememberRedesignUiState() 驱动 reducer，开启 Interactive Mode 后
 * 点击开关 / 色板 / 粘滞键就能看到全局 AirTouchTheme 重组。
 */

@Composable
private fun ThemedPreview(
    initial: RedesignUiState,
    content: @Composable (state: RedesignUiState, onAction: (com.airtouch.app.ui.redesign.theme.RedesignAction) -> Unit) -> Unit,
) {
    val (state, onAction) = rememberRedesignUiState(initial)
    AirTouchTheme(
        darkTheme = state.darkTheme,
        accent = state.accent,
        leftHandMode = state.leftHandMode,
        hapticEnabled = state.hapticEnabled,
    ) {
        content(state, onAction)
    }
}

@Preview(name = "01 · 主页 · Dark", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun HomeDarkPreview() = ThemedPreview(RedesignUiState(darkTheme = true)) { s, a -> HomeScreen(state = s, onAction = a) }

@Preview(name = "02 · 设置 · Dark + Coral", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsDarkCoralPreview() = ThemedPreview(
    RedesignUiState(darkTheme = true, accent = AccentPreset.CORAL)
) { s, a -> SettingsScreen(state = s, onAction = a) }

@Preview(name = "02 · 设置 · Emerald", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsEmeraldPreview() = ThemedPreview(
    RedesignUiState(accent = AccentPreset.EMERALD)
) { s, a -> SettingsScreen(state = s, onAction = a) }

@Preview(name = "03 · 鼠标模式 · 左手镜像 + Blue", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun AirMouseLeftHandPreview() = ThemedPreview(
    RedesignUiState(leftHandMode = true, accent = AccentPreset.BLUE)
) { s, a -> AirMouseScreen(state = s, onAction = a) }

@Preview(name = "04 · 触控板 · Dark", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun TouchpadDarkPreview() = ThemedPreview(RedesignUiState(darkTheme = true)) { s, a -> TouchpadScreen(state = s, onAction = a) }

@Preview(name = "05 · 竖屏键盘 · 9键 + Dark", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun FlatKeyboardT9DarkPreview() = ThemedPreview(
    RedesignUiState(darkTheme = true, t9Mode = true, stickyModifiers = setOf("Shift"))
) { s, a -> FlatKeyboardScreen(state = s, onAction = a) }

@Preview(name = "05 · 竖屏键盘 · 全键盘 + Emerald", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun FlatKeyboardEmeraldPreview() = ThemedPreview(
    RedesignUiState(accent = AccentPreset.EMERALD, stickyModifiers = setOf("Ctrl", "Alt"))
) { s, a -> FlatKeyboardScreen(state = s, onAction = a) }

@Preview(name = "06 · 横屏键盘 · 悬浮微触控板", showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun LandscapeMiniTouchpadPreview() = ThemedPreview(
    RedesignUiState(miniTouchpadVisible = true)
) { s, a -> LandscapeKeyboardScreen(state = s, onAction = a) }

@Preview(name = "06 · 横屏键盘 · Dark", showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun LandscapeDarkPreview() = ThemedPreview(RedesignUiState(darkTheme = true)) { s, a -> LandscapeKeyboardScreen(state = s, onAction = a) }
