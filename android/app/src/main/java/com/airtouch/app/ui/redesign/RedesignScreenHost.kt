package com.airtouch.app.ui.redesign

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignRoute
import com.airtouch.app.ui.redesign.theme.RedesignUiState

/**
 * 6 个页面的统一宿主。
 *
 * 关键：**「鼠标 / 触控板 / 键盘」一体式滑块放在页面之外、由三个模式页共享同一个实例**。
 * 以前每个页面各自 new 一个分段控件，切页时是整页替换，滑块直接出现在新位置、没有过渡；
 * 现在 selectedIndex 变化会驱动同一个 [TactileSegmentedControl] 内部的
 * `animateDpAsState(spring)`，滑块会从旧位置平滑滑到新位置。
 *
 * 三个界面用的都是**同一个新拟物滑块**（键盘页不再用扁平版），保证一模一样。
 * 主页 / 设置页 / 横屏全屏键盘不显示该滑块。
 */
@Composable
fun RedesignScreenHost(
    state: RedesignUiState,
    route: RedesignRoute,
    onAction: (RedesignAction) -> Unit,
    onQrScanned: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    val modeIndex = when (route) {
        RedesignRoute.AIR_MOUSE -> 0
        RedesignRoute.TOUCHPAD -> 1
        RedesignRoute.KEYBOARD_PORTRAIT -> 2
        else -> -1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (route) {
                RedesignRoute.HOME -> HomeScreen(
                    state = state,
                    onAction = onAction,
                    onQrScanned = onQrScanned,
                )
                RedesignRoute.SETTINGS -> SettingsScreen(state = state, onAction = onAction)
                RedesignRoute.AIR_MOUSE -> AirMouseScreen(state = state, onAction = onAction)
                RedesignRoute.TOUCHPAD -> TouchpadScreen(state = state, onAction = onAction)
                RedesignRoute.KEYBOARD_PORTRAIT -> FlatKeyboardScreen(state = state, onAction = onAction)
                RedesignRoute.KEYBOARD_LANDSCAPE -> LandscapeKeyboardScreen(state = state, onAction = onAction)
            }
        }

        // 完全限定调用顶层 AnimatedVisibility，避开 ColumnScope 的同名扩展重载
        androidx.compose.animation.AnimatedVisibility(
            visible = modeIndex >= 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            TactileSegmentedControl(
                items = listOf("鼠标", "触控板", "键盘"),
                selectedIndex = modeIndex.coerceAtLeast(0),
                onSelected = { onAction(RedesignAction.SetMode(it)) },
                modifier = Modifier
                    .padding(horizontal = AirTouchDimens.pageMargin)
                    .padding(bottom = AirTouchDimens.blockGap),
            )
        }
    }
}
