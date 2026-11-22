package com.airtouch.app.input.keyboard

import android.os.SystemClock

/**
 * 组合修饰键的「粘滞锁定」状态机（Sticky Keys）。
 *
 * 手机上只有一个手指能按住 Ctrl，而 `Ctrl+C` 需要「按住 Ctrl 的同时按下 C」。粘滞键把「按住」
 * 拆成两种可以单手完成的状态：
 *
 * ```
 *            单击                 (快速) 再次单击
 *   NONE ──────────► ONCE ──────────────────────► LOCKED
 *     ▲               │                              │
 *     │               │ 按下任意普通键后被消费掉        │ 再次点击
 *     └───────────────┴──────────────────────────────┘
 * ```
 *
 * - **[Lock.ONCE]（单次锁定）**：按一个普通键发完组合键后自动恢复未选中；
 * - **[Lock.LOCKED]（持续锁定）**：一直按在电脑上，直到再次点击才松开 —— 用来连续做
 *   `Ctrl+C`、`Ctrl+V`、`Ctrl+Z`。
 *
 * ## 为什么「双击」必须限时
 * 如果第二次点击无论隔多久都算双击，那么「先点一次 Ctrl、想了几秒、再点一次」会静默进入持续
 * 锁定，用户会以为键盘坏了（此后每个字母都变成快捷键）。所以只有落在 [doubleTapWindowMs]
 * 内的第二次点击才算双击；超时的第二次点击只是「又一次单击」，仍停在 [Lock.ONCE]。
 * 而 [Lock.ONCE] 一定会被下一次普通按键消费掉，所以它不会永久粘住。
 *
 * 本类**只管状态**，不发送任何东西：真正「什么时候按下/抬起修饰键」由 [KeyboardSession] 决定。
 * 把状态机与发送分开，是为了让「单击/双击」这种容易写错的判定能独立被读懂与修改。
 *
 * @param doubleTapWindowMs 双击判定窗口；要短于系统的双击超时（默认 300ms）
 * @param now 时间源，注入是为了让判定可测（也避免依赖 `SystemClock` 以外的假设）
 */
class StickyKeys(
    private val doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {

    /** 修饰键的粘滞状态 */
    enum class Lock {
        /** 未选中：不参与组合 */
        NONE,

        /** 单次锁定：下一次普通按键按下时按下它，该键抬起后立刻松开 */
        ONCE,

        /** 持续锁定：一直按着，直到用户再次点击 */
        LOCKED,
    }

    /** 用 LinkedHashMap 保留用户点选修饰键的顺序：按下顺序因此是稳定且符合直觉的 */
    private val states = LinkedHashMap<Int, Lock>()
    private val tapTimes = HashMap<Int, Long>()

    fun lockOf(vk: Int): Lock = states[vk] ?: Lock.NONE

    /** 是否处于激活（[Lock.ONCE] 或 [Lock.LOCKED]）状态 */
    fun isActive(vk: Int): Boolean = lockOf(vk) != Lock.NONE

    /** 当前所有激活的修饰键，按用户点选的先后顺序 */
    fun activeModifiers(): List<Int> = states.keys.filter { isActive(it) }

    /**
     * 用户点了一下修饰键，推进状态机并返回新状态。
     *
     * 调用方（[KeyboardSession]）按返回值决定「立刻按下」「等普通键按下时再按」还是「立刻松开」。
     */
    fun tap(vk: Int): Lock {
        val next = when (lockOf(vk)) {
            Lock.NONE -> Lock.ONCE
            Lock.ONCE ->
                if (now() - (tapTimes[vk] ?: 0L) <= doubleTapWindowMs) Lock.LOCKED else Lock.ONCE
            Lock.LOCKED -> Lock.NONE
        }

        if (next == Lock.NONE) {
            states.remove(vk)
            tapTimes.remove(vk)
        } else {
            states[vk] = next
            tapTimes[vk] = now()
        }

        return next
    }

    /** 单次锁定被一次组合键消费掉了，回到未选中 */
    fun consumeOneShot(vk: Int) {
        if (lockOf(vk) == Lock.ONCE) {
            states.remove(vk)
            tapTimes.remove(vk)
        }
    }

    /** 清空单个修饰键（不改变其他修饰键） */
    fun clear(vk: Int) {
        states.remove(vk)
        tapTimes.remove(vk)
    }

    /** 清空全部状态（键盘不可见 / 断开连接时） */
    fun clearAll() {
        states.clear()
        tapTimes.clear()
    }

    companion object {
        /**
         * 双击窗口。取 300ms：明显短于系统默认双击间隔（ViewConfiguration 一般是 300ms，
         * 用户习惯上「快速点两下」通常在 150~250ms），既能可靠识别双击，又不会把两次独立
         * 的单击误判成双击。
         */
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 300L
    }
}
