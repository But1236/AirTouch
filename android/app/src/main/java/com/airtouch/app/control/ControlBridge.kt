package com.airtouch.app.control

import com.airtouch.app.net.AirTouchClient

/**
 * 控制界面与两个输入 Fragment（触摸板 / 空中鼠标）之间的最小契约。
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
