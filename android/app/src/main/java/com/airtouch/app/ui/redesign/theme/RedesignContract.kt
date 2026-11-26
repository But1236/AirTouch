package com.airtouch.app.ui.redesign.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/*
 * UI 契约（第二步：真实接口对接后）。
 *  - 页面只接收 [RedesignUiState] 并发出 [RedesignAction]，仍然不直接依赖任何网络类；
 *  - 副作用全部集中在 integration/RedesignController.dispatch()，
 *    预览壳用 [rememberRedesignUiState] 的纯 reducer，真机用 RedesignController；
 *  - 所有连接态 / 文件进度 / 提示都由控制器写回 state，页面只读。
 */

/** 与蓝图 `Compose Route` 注释一一对应（顶部导航标签栏已移除，title 仅作内部标识）。 */
enum class RedesignRoute(val route: String, val title: String) {
    HOME("home", "主页"),
    SETTINGS("settings", "设置"),
    AIR_MOUSE("air_mouse", "鼠标模式"),
    TOUCHPAD("touchpad", "触控板模式"),
    KEYBOARD_PORTRAIT("keyboard_portrait", "竖屏键盘"),
    KEYBOARD_LANDSCAPE("keyboard_landscape", "全屏横屏键盘"),
}

data class RedesignUiState(
    val darkTheme: Boolean = false,
    val accent: AccentPreset = AccentPreset.YELLOW,
    /** 用户用色相滑杆自由选的颜色；非空时覆盖 [accent] 预设 */
    val customAccent: Color? = null,
    val leftHandMode: Boolean = false,
    val hapticEnabled: Boolean = true,
    val connected: Boolean = false,
    val sensitivity: Float = 1.2f,
    /** 设置项「触摸零延时」：true = 单击零延迟（双击拖拽约 3px 起点偏移），false = 0px 精准拖拽 */
    val touchZeroLatency: Boolean = false,
    /** 鼠标页反向（左右）/ 反向（上下）：勾上 = 把该轴反过来 */
    val airMouseInvertX: Boolean = false,
    val airMouseInvertY: Boolean = false,
    val paused: Boolean = false,
    val t9Mode: Boolean = false,
    /** 当前被按住还没松开的鼠标键（"left"/"right"/"middle"）；非空 = 正在拖拽，界面据此高亮该键 */
    val heldButton: String? = null,
    /** 九键拼音正在组合的数字串（按最优切分加了分隔符，如 `64'426`）；空 = 没在组合 */
    val t9Composition: String = "",
    /** 九键拼音候选（按优先级排序，点一个就上屏）；空 = 没有候选或没在组合 */
    val t9Candidates: List<String> = emptyList(),
    val miniTouchpadVisible: Boolean = false,
    val stickyModifiers: Set<String> = emptySet(),
    val lastKeyLog: String = "等待按键输入...",

    // ---------- 真实连接信息（由 RedesignController 写回） ----------
    val connectionLabel: String = "尚未连接",
    val deviceIdentity: String = "未信任设备",
    val latencyMs: Int = -1,
    val statusText: String = "未连接",
    val fileStatus: String = "",
    /** 协议里没有「截屏回传」帧；true 仅表示服务端曾推送过可打开的图片文件 */
    val screenshotAvailable: Boolean = false,
)

sealed interface RedesignAction {
    /** 语义化点击（副作用的映射集中在 RedesignController） */
    data class Click(val label: String) : RedesignAction

    data class SetDarkTheme(val value: Boolean) : RedesignAction
    data class SetAccent(val preset: AccentPreset) : RedesignAction
    /** 自由选色（色相滑杆），覆盖 4 个预设 */
    data class SetCustomAccent(val color: Color) : RedesignAction
    data class SetLeftHand(val value: Boolean) : RedesignAction
    data class SetHaptic(val value: Boolean) : RedesignAction
    data class SetConnected(val value: Boolean) : RedesignAction
    data class SetSensitivity(val value: Float) : RedesignAction
    /** 设置页「触摸零延时」开关（默认关闭） */
    data class SetTouchZeroLatency(val value: Boolean) : RedesignAction
    /** 鼠标页「反向（左右）」开关 */
    data class SetAirMouseInvertX(val value: Boolean) : RedesignAction
    /** 鼠标页「反向（上下）」开关 */
    data class SetAirMouseInvertY(val value: Boolean) : RedesignAction
    data class SetPaused(val value: Boolean) : RedesignAction
    data class SetT9(val value: Boolean) : RedesignAction
    data class SetMiniTouchpad(val value: Boolean) : RedesignAction

    /** 滚轮垂直拖动的机械刻度（+1 下滚 / -1 上滚） */
    data class MouseScroll(val delta: Int) : RedesignAction

    /** 底部一体式模式切换器（0 鼠标 / 1 触控板 / 2 键盘） */
    data class SetMode(val index: Int) : RedesignAction

    /** Ctrl / Shift / Alt / Win / Caps 单击粘滞锁定 */
    data class ToggleSticky(val modifier: String) : RedesignAction

    /** 普通按键 / Unicode 文本 */
    data class PressKey(val key: String) : RedesignAction

    // ---------- 九键拼音（T9）：键盘只管按键，组合与候选由控制器接 T9Composer ----------

    /**
     * 九键数字键。
     *
     * **它不产生任何协议帧** —— 九键的每个数字对应 3~4 个字母（`5` = jkl），按键层面根本不知道
     * 用户打的是哪个拼音，必须交给拼音状态机还原成候选；直接当数字发出去，九键就退化成数字小键盘了。
     */
    data class T9Digit(val digit: Int) : RedesignAction

    /** 九键退格：组合中退掉一位数字；组合为空时才是真正的退格键 */
    data object T9Backspace : RedesignAction

    /** 上屏第 [index] 个候选（Unicode 文本注入，协议 §2.7） */
    data class T9Commit(val index: Int) : RedesignAction

    /** 清空当前拼音组合（「重输」键 / 切布局 / 断线） */
    data object T9Clear : RedesignAction

    // ---------- 第二步新增：真实接口动作 ----------

    /** 免扫码重连上次设备（SettingsStore.lastHost/lastPort/lastSid/lastPin） */
    data object ConnectLast : RedesignAction

    /** 手动输入 IP / 端口 / token 连接 */
    data class ManualConnect(val host: String, val port: Int, val token: String) : RedesignAction

    /** 新版主页 1:1 取景框扫码；控制器只给提示，不再启动任何 Activity */
    data object ScanQr : RedesignAction

    /** 清除全部证书固定值 */
    data object ResetTrust : RedesignAction

    /** 打开 SAF 选文件并发送 */
    data object OpenFilePicker : RedesignAction

    /** 纯文本输入框：整段 Unicode 注入 */
    data class SendText(val text: String) : RedesignAction

    /** 截图：现有协议无回传帧，退化为发送 PrintScreen 按键 */
    data object Screenshot : RedesignAction

    /** 主动断开 */
    data object Disconnect : RedesignAction

    /** 点阵触控板：指针位移（屏幕像素 → 协议 move） */
    data class TouchpadMove(val dx: Int, val dy: Int) : RedesignAction

    /** 点阵触控板：单击 */
    data class TouchpadClick(val button: String) : RedesignAction

    /**
     * 鼠标键的**状态变化**（拖拽）：按下 = 开始拖，抬起 = 结束拖。
     *
     * 与 [TouchpadClick] 的区别：`click` 是「按下又抬起」的完整动作，电脑上只会得到一次点击；
     * 拖动必须拆成「按下 → 一连串 move → 抬起」，所以左键 / 右键改成按住即按下、松手才抬起，
     * 轻点一下就松开仍然等价于一次单击。
     */
    data class SetButton(val button: String, val down: Boolean) : RedesignAction

    /** 点阵触控板 / 迷你触控板：高精度滚轮 */
    data class TouchpadScroll(val delta: Int, val horizontal: Boolean) : RedesignAction

    /** 双指捏合几何（协议 touch） */
    data class TouchpadPinch(val phase: String, val scale: Double, val dx: Int, val dy: Int) : RedesignAction
}

/** 纯 reducer（只改 UI 状态，不做任何副作用）。 */
fun RedesignUiState.reduce(action: RedesignAction): RedesignUiState = when (action) {
    is RedesignAction.Click -> this
    is RedesignAction.SetDarkTheme -> copy(darkTheme = action.value)
    is RedesignAction.SetAccent -> copy(accent = action.preset, customAccent = null)
    is RedesignAction.SetCustomAccent -> copy(customAccent = action.color)
    is RedesignAction.SetLeftHand -> copy(leftHandMode = action.value)
    is RedesignAction.SetHaptic -> copy(hapticEnabled = action.value)
    is RedesignAction.SetConnected -> copy(connected = action.value)
    is RedesignAction.SetSensitivity -> copy(sensitivity = action.value)
    is RedesignAction.SetTouchZeroLatency -> copy(touchZeroLatency = action.value)
    is RedesignAction.SetAirMouseInvertX -> copy(airMouseInvertX = action.value)
    is RedesignAction.SetAirMouseInvertY -> copy(airMouseInvertY = action.value)
    is RedesignAction.SetPaused -> copy(paused = action.value)
    is RedesignAction.SetT9 -> copy(t9Mode = action.value)
    is RedesignAction.SetMiniTouchpad -> copy(miniTouchpadVisible = action.value)
    is RedesignAction.MouseScroll -> copy(
        lastKeyLog = "滚轮刻度: ${if (action.delta > 0) "+" else ""}${action.delta}"
    )
    // 路由由宿主 Activity 处理，state 不变
    is RedesignAction.SetMode -> this
    is RedesignAction.ToggleSticky -> copy(
        stickyModifiers = if (action.modifier in stickyModifiers) {
            stickyModifiers - action.modifier
        } else {
            stickyModifiers + action.modifier
        }
    )
    is RedesignAction.PressKey -> {
        val combo = if (stickyModifiers.isEmpty()) action.key
        else stickyModifiers.sorted().joinToString(" + ") + " + " + action.key
        // 只存按键本身：原来拼了「已发送: 」前缀，用户要求不要那个前缀（"最近发送按键"下面直接显示键名）
        copy(lastKeyLog = combo)
    }
    // 下列动作的效果全部由控制器写回，reducer 不改状态
    is RedesignAction.ConnectLast,
    is RedesignAction.ManualConnect,
    is RedesignAction.ScanQr,
    is RedesignAction.ResetTrust,
    is RedesignAction.OpenFilePicker,
    is RedesignAction.SendText,
    is RedesignAction.Screenshot,
    is RedesignAction.Disconnect,
    is RedesignAction.SetButton,
    is RedesignAction.T9Digit,
    is RedesignAction.T9Backspace,
    is RedesignAction.T9Commit,
    is RedesignAction.T9Clear,
    is RedesignAction.TouchpadMove,
    is RedesignAction.TouchpadClick,
    is RedesignAction.TouchpadScroll,
    is RedesignAction.TouchpadPinch -> this
}

/** 预览专用的有状态容器（纯内存 reducer，无任何网络/传感器）。 */
@Composable
fun rememberRedesignUiState(
    initial: RedesignUiState = RedesignUiState(),
): Pair<RedesignUiState, (RedesignAction) -> Unit> {
    var state by remember { mutableStateOf(initial) }
    return state to { action -> state = state.reduce(action) }
}
