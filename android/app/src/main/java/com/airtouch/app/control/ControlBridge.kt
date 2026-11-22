package com.airtouch.app.control

import com.airtouch.app.net.AirTouchClient

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

    /** 滚轮，正数上滚、负数下滚（协议 §2.1） */
    fun sendScroll(amount: Int)

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
