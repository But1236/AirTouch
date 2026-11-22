package com.airtouch.app.control

import com.airtouch.app.input.TouchpadView
import com.airtouch.app.input.keyboard.Vk
import com.airtouch.app.net.AirTouchClient

/**
 * 三指手势要用到的两个字母键。
 *
 * [Vk] 只给了 `LETTER_A`..`LETTER_Z` 的区间端点，没有逐字母常量；这里只用到两个，
 * 写死比给 26 个字母各加一个常量更实在。
 */
private const val VK_D = 0x44
private const val VK_S = 0x53

/**
 * 控制界面与两个输入 Fragment（触摸板 / 鼠标）之间的最小契约。
 *
 * 之所以单独抽一个接口而不是让 Fragment 直接强转 Activity：Fragment 只需要「发指令」和
 * 「知道滚轮模式状态」这几件事，接口能明确边界，也避免 Fragment 拿到整个 Activity。
 * 所有方法都必须在主线程调用。
 */
interface ControlBridge {

    /** 当前连接状态（用于 Fragment 决定是否发送） */
    fun connectionState(): AirTouchClient.State

    /** 连接信息文本，如 "192.168.1.5:8443"，未连接时为空串 */
    fun connectionLabel(): String

    /** 移动光标（dx/dy 单位：屏幕像素，右下为正，范围由 Protocol 截断） */
    fun sendMove(dx: Int, dy: Int)

    /** 单击，button 取 "left" / "right" */
    fun sendClick(button: String)

    /**
     * 鼠标按键的一次状态变化（协议 §2.1 的 `button`）：按下 / 抬起分成两次发。
     *
     * 触摸板用它实现「双击后按住不放 = 拖拽」：先按下左键，中间走一串 [sendMove]，最后抬起。
     * **抬起是调用方的责任**——电脑侧只在断线或收到 `reset` 时代为释放，所以任何中断路径
     * （手指抬起、切页、onPause）都必须补一次 `down = false`。
     *
     * @return false 表示帧没能发出去（多半是没连接）。调用方**必须**据此放弃拖拽状态，
     *   否则会出现「手指已经抬起、电脑上的左键还按着」这种最糟糕的状态。
     */
    fun sendButton(button: String, down: Boolean): Boolean

    /** 滚轮（整格，一次一格 = 120 单位）：陀螺仪滚轮模式用，正数上滚、负数下滚（协议 §2.1） */
    fun sendScrollNotches(amount: Int)

    /**
     * **高精度滚轮**（协议 §2.1 的 `delta` / `axis`）：触摸板两指上下 / 左右滑动用。
     *
     * @param delta 原生滚轮单位，120 = 一格；触摸板发的是 15 这种小增量
     * @param horizontal true = 横向（`MOUSEEVENTF_HWHEEL`）
     */
    fun sendScroll(delta: Int, horizontal: Boolean)

    /**
     * **双指捏合 / 张开**（协议 §2.1 的 `touch`）：只把几何报给服务端，
     * 由服务端挑最合适的 Windows API —— 有触摸输入栈的机器走 `InjectTouchInput` 注入**真双指触摸**
     * （应用得到的是和真实触摸屏一样的无级缩放），没有触摸栈的机器由服务端自动回退到
     * Ctrl + 高精度滚轮。手机端不需要知道对面有没有触摸屏，这也是「把每个手势交给最适合它的
     * Windows API」这条分工的落点。
     *
     * @param phase 协议里的 `down` / `move` / `up`
     * @param scale 当前两指间距 ÷ 手势开始时的间距
     * @param dx 手势中心相对上一帧的横向移动（屏幕像素）
     * @param dy 手势中心相对上一帧的纵向移动（屏幕像素）
     */
    fun sendPinch(phase: String, scale: Double, dx: Int, dy: Int)

    /**
     * 设置电脑端的缩放偏好（协议 §2.1 的 `zoom_mode`）。
     *
     * @param mode [Protocol.ZOOM_PREF_NATIVE] = 优先原生触控注入；[Protocol.ZOOM_PREF_WHEEL] =
     *   强制 Ctrl+滚轮回退（即使电脑接着触摸屏，用来对比手感或躲开不兼容触摸屏的老软件）；
     *   [Protocol.ZOOM_PREF_AUTO] = 交回给电脑端自己挑
     */
    fun sendZoomMode(mode: String)

    /**
     * 请电脑端**立刻重新探测**触控能力（协议 §2.1 的 `zoom_probe`）。
     *
     * 用途是「重新检测这台电脑的触控能力」这一步：电脑端重新读 `SM_MAXIMUMTOUCHES`，一旦大于 0 就热切换到
     * `InjectTouchInput`，既不用重启服务端也不用断开手机。结果通过 `zoom_status` 推回来。
     */
    fun sendZoomProbe()

    /**
     * **三指手势 → 系统快捷键**（走已有的 `key` 帧，不需要新协议）。
     *
     * Windows 上「三指」这套手势本来就是系统级切换，而系统级切换只能靠快捷键稳定触发：
     * 合成 `WM_GESTURE` 要目标窗口配合、注入触摸在多数台式机/无触摸屏笔记本上根本没有触摸栈，
     * 而快捷键是 SendInput 就能发、且不受权限限制的一条路。
     *
     * 默认实现放在接口里，是因为两个触摸板（触摸板页、全屏键盘上的微型触控板）共用同一套映射，
     * 写在两处迟早会不一致。
     */
    fun sendThreeFinger(action: String) {
        when (action) {
            // 三指左右滑 = 切换应用（与 Windows 默认一致）：左滑上一个、右滑下一个
            TouchpadView.ThreeFinger.LEFT -> tapKeys(Vk.TAB, Vk.LMENU, Vk.LSHIFT)
            TouchpadView.ThreeFinger.RIGHT -> tapKeys(Vk.TAB, Vk.LMENU)
            // 三指上滑 = 任务视图
            TouchpadView.ThreeFinger.UP -> tapKeys(Vk.TAB, Vk.LWIN)
            // 三指下滑 = 显示桌面
            TouchpadView.ThreeFinger.DOWN -> tapKeys(VK_D, Vk.LWIN)
            // 三指轻点 = 搜索（与 Windows 11 默认一致）
            TouchpadView.ThreeFinger.TAP -> tapKeys(VK_S, Vk.LWIN)
        }
    }

    /**
     * 按下若干修饰键 → 敲一下普通键 → **逆序**抬起修饰键。
     *
     * 顺序必须是「修饰键先按下、最后抬起」：反过来的话普通键会变成没有修饰的裸按键，
     * 例如 Alt+Tab 会先敲一个裸 Tab（切到某个控件）再按 Alt。
     */
    private fun tapKeys(key: Int, vararg modifiers: Int) {
        for (m in modifiers) sendKey(m, down = true)
        sendKey(key, down = true)
        sendKey(key, down = false)
        for (m in modifiers.reversed()) sendKey(m, down = false)
    }

    /** 光标复位 */
    fun sendReset()

    /**
     * 键盘：发送一次按键状态变化（协议 §2.6）。
     *
     * 修饰键组合由调用方（`KeyboardSession`）自行拆成有序的若干次调用，本方法只负责转发。
     *
     * @param vk Win32 虚拟键码
     * @param down true = 按下，false = 抬起
     * @param extended 显式指定扩展键标志；null 表示由服务端按 vk 推断
     */
    fun sendKey(vk: Int, down: Boolean, extended: Boolean? = null)

    /**
     * 键盘：注入一段 Unicode 文本（协议 §2.7）。
     *
     * 中文用虚拟键码表达不了，所以九键选中的候选字、纯文本输入框的一整段话都走这一条。
     * 长文本由调用方或 [AirTouchClient] 分片，本方法只负责转发。
     */
    fun sendUnicodeText(text: String)

    /**
     * 弹出「纯文本输入框」：打大段文字时的补充通道。
     *
     * 这是**唯一刻意使用系统输入法**的地方（多行编辑、粘贴、长文本都靠系统能力）；
     * 自绘键盘本身从不调起系统输入法。
     */
    fun showTextSender()

    /**
     * 进入 / 退出横屏全屏键盘。
     *
     * 由键盘页的「全屏展开」按钮或屏幕旋转触发。全屏键盘是覆盖在同一个 Activity 上的一个层
     * （不是新 Activity），所以这件事必须由持有覆盖层的 Activity 做，键盘页只是提请求。
     */
    fun setKeyboardFullscreen(enabled: Boolean)

    /** 当前是否处于横屏全屏键盘 */
    fun isKeyboardFullscreen(): Boolean

    /** 滚轮模式当前是否开启 */
    fun isScrollMode(): Boolean

    /** 切换滚轮模式，会同步持久化设置、同步另一个 Fragment、更新工具栏按钮外观 */
    fun setScrollMode(enabled: Boolean)

    /**
     * Wi-Fi 低延迟锁的状态说明。
     *
     * @return `null` 表示一切正常（锁已持有）；否则返回给用户看的短句，例如
     *   `Wi-Fi 低延迟锁未获取：SecurityException`。蓝牙/省电模式导致的「帧成串到达」与此直接相关，
     *   所以必须能在界面上看见，而不是只写在日志里。
     */
    fun wifiLatencyNote(): String?

    /** 轻量提示（Toast/Snackbar） */
    fun showHint(message: String)
}
