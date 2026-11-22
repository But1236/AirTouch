package com.airtouch.app.input

import com.airtouch.app.input.keyboard.KeyAction
import com.airtouch.app.input.keyboard.KeySpec
import com.airtouch.app.input.keyboard.KeyboardLayouts
import com.airtouch.app.input.keyboard.KeyboardSession
import com.airtouch.app.input.keyboard.StickyKeys

/**
 * [KeyboardView] 与 [OneLineDPadView] 的事件接收器。
 *
 * 两个自绘控件各有自己的回调风格（键帽是「事件 + 阶段」，一字滑条是「方向 + 起止」），但最终都要变成
 * 同一串 `key` 帧。把「控件回调 → [KeyboardSession]」的这一层单独放一个类，好处是：
 *
 * - 竖屏键盘和横屏全屏键盘可以**各自持有一个**（也就各自有一份粘滞状态），不需要把 Activity 变成
 *   两个 Listener 的实现类；
 * - 一字滑条那套「起 / 连发 / 止」到「键盘键按下 / 连发 / 抬起」的映射只写一遍；
 * - [KeyboardSession] 与两个 View 之间没有互相引用，三者都能单独读懂。
 *
 * 所有方法都必须在主线程调用（View 触摸与 Handler 连发都在主线程）。
 *
 * @param onActionRequested 动作键（切换布局 / 全屏展开 / 标点面板）被点了一下
 * @param onModifierChanged 修饰键的粘滞状态发生了变化，参数是 (vk, 新状态)
 * @param interceptor 事件的第一道关口：返回 true 表示「这次事件我已处理完，不要发给电脑」。
 *   九键拼音用它把数字键、组合中的退格/空格接走（见 [com.airtouch.app.control.KeyboardFragment]）。
 *   全屏键盘不需要它，传 null 即可 —— 那时所有按键都老老实实变成 `key` 帧。
 */
class KeyboardInputHandler(
    private val session: KeyboardSession,
    private val onActionRequested: (KeyAction) -> Unit = {},
    private val onModifierChanged: (vk: Int, lock: StickyKeys.Lock) -> Unit = { _, _ -> },
    private val interceptor: ((KeySpec, KeyboardView.Phase) -> Boolean)? = null,
) : KeyboardView.Listener, OneLineDPadView.Listener {

    /** 一字滑条当前按着的方向键；滑条只报方向，键在这里查出来并存住，抬起时才能发对同一个键 */
    private var dpadKey: KeySpec? = null

    /** 供 UI 直接挂到 `KeyboardView.listener` 上 */
    val keyboardListener: KeyboardView.Listener get() = this

    /** 供 UI 直接挂到 `OneLineDPadView.listener` 上 */
    val dpadListener: OneLineDPadView.Listener get() = this

    // ---------------- KeyboardView.Listener ----------------

    override fun onKeyEvent(key: KeySpec, phase: KeyboardView.Phase) {
        if (interceptor?.invoke(key, phase) == true) return

        when (phase) {
            // 按下立刻发 KEY_DOWN：延迟最低，长按连发也才有意义
            KeyboardView.Phase.DOWN -> session.pressKey(key)
            KeyboardView.Phase.UP -> session.releaseKey(key)
            // 连发节拍刻意不走 press/release：否则第一次重复就会把「单次锁定」的修饰键消费掉
            KeyboardView.Phase.REPEAT -> session.repeatTick(key)
        }
    }

    override fun onModifierTap(key: KeySpec) {
        val lock = session.tapModifier(key.vk)
        onModifierChanged(key.vk, lock)
    }

    override fun onAction(action: KeyAction, key: KeySpec) {
        onActionRequested(action)
    }

    override fun modifierLock(vk: Int): StickyKeys.Lock = session.lockOf(vk)

    // ---------------- OneLineDPadView.Listener ----------------

    override fun onDirectionStart(direction: OneLineDPadView.Direction) {
        val key = arrow(direction)
        dpadKey = key
        // 第一次触发就在这一刻发出去，方向键没有「按住 320ms 才开始动」的道理
        session.pressKey(key)
    }

    override fun onDirectionRepeat(direction: OneLineDPadView.Direction) {
        val key = dpadKey ?: arrow(direction)
        session.repeatTick(key)
    }

    override fun onDirectionEnd(direction: OneLineDPadView.Direction) {
        val key = dpadKey ?: return
        dpadKey = null
        session.releaseKey(key)
    }

    // ---------------- 供 Fragment / Activity 调用 ----------------

    /**
     * 功能栏上的「一次性按键」（Esc / Del / prtsc）。
     *
     * 按钮不是键帽，没有「按住不放」的概念，所以按下与抬起在同一刻发出：电脑侧看到的就是一次
     * 极短的击键，和真实键盘敲一下完全一样。已锁定的修饰键仍然会被带上（Ctrl+Esc = 开始菜单）。
     */
    fun tapKey(key: KeySpec) {
        session.pressKey(key)
        session.releaseKey(key)
    }

    /**
     * 松开全部按键并清空粘滞状态。
     *
     * 键盘被切走、退出全屏、连接断开时必须调用：粘滞锁定的 Ctrl 只在手机内存里，电脑端一旦
     * 收不到抬起事件，那个 Ctrl 就会一直按着（服务端虽然有会话结束的兜底，但不该依赖它兜底）。
     */
    fun releaseAll() {
        dpadKey = null
        session.releaseAll()
    }

    private fun arrow(direction: OneLineDPadView.Direction): KeySpec = when (direction) {
        OneLineDPadView.Direction.LEFT -> KeyboardLayouts.ToolKeys.ARROW_LEFT
        OneLineDPadView.Direction.RIGHT -> KeyboardLayouts.ToolKeys.ARROW_RIGHT
    }
}
